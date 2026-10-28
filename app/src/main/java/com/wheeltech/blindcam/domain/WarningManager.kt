package com.wheeltech.blindcam.domain

import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.render.MjpegDecoder
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 盲区预警管理（FrameListener）。
 *
 * 硬性约束（需求 8 + 性能）：
 * - **绝不在 USB 帧回调线程做解码**。USB 回调只判断是否需要分析、限流后拷贝一份
 *   最新 JPEG，然后交给独立的 `warning` HandlerThread 串行解码/检测。
 * - 每路独立状态、latest-only：分析线程忙时只保留最新帧，不排队、不堆积。
 * - 按帧率限流到约 5fps/路，并按原图短边估算采样率（1080p 约 1/8），
 *   避免 4K 多路时把 CPU 吃满。
 * - 只有检测到局部运动时才红框 + 蜂鸣；运动停止、摄像头拔出/关闭预警时立即清除。
 * - 状态只在分析线程读写；UI 红框状态通过主线程 StateFlow 发布，避免 HashMap 并发损坏。
 * - 预警音量跟随设置实时生效（ToneGenerator 警报流，不依赖系统通知铃声是否静音）。
 */
class WarningManager(
    private val slots: StateFlow<List<CameraSupervisor.SlotRuntime>>,
    private val settingsState: StateFlow<AppSettings>
) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "warning"
        /** 灰度网格宽度 */
        private const val GRAY_SCALE = 64
        /** 单像素亮度变化阈值（过滤 MJPEG 噪声） */
        private const val PIXEL_DIFF = 42
        /** 块内变化像素占比阈值（块算运动） */
        private const val BLOCK_PIXEL_RATIO = 0.18f
        /** 触发预警的运动块占比下限（局部目标） */
        private const val MOTION_BLOCK_MIN = 0.05f
        /** 运动块占比上限：超过视为全局亮度变化（AE/闪光），不触发 */
        private const val MOTION_BLOCK_MAX = 0.62f
        /** 预热帧数（开始取流后先学习，不触发） */
        private const val WARMUP_FRAMES = 10
        /** 进入预警需连续运动帧 */
        private const val ENTER_EVIDENCE = 2
        /** 退出预警需连续静止帧 */
        private const val EXIT_EVIDENCE = 2
        /** 每路分析间隔由 DevicePerformance.warningIntervalMs 按设备性能动态决定 */
        /** 超过该时间没有新帧，自动清除该路红框/声音（拔线、取流中断） */
        private const val STALE_FRAME_MS = 3_000L
        private const val WATCHDOG_INTERVAL_MS = 1_500L
    }

    private class ChannelState {
        var prevGray: IntArray? = null
        var warmup = 0
        var motionStreak = 0
        var stillStreak = 0
        var active = false
        var lastAnalyzedAt = 0L
    }

    private class Pending(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int
    )

    /** 每路最多一份待分析帧（latest-only） */
    private val pending = ConcurrentHashMap<Int, Pending>()

    /** 上次投递分析的时间（USB 线程读写） */
    private val lastSubmitAt = ConcurrentHashMap<Int, Long>()

    /** 已请求关闭预警的槽位，避免关闭状态每帧重复投递任务 */
    private val warnOffRequested = ConcurrentHashMap.newKeySet<Int>()

    /** 分析线程独占：每路运动状态 */
    private val states = HashMap<Int, ChannelState>()

    private val worker = HandlerThread("warning").apply { start() }
    private val handler = Handler(worker.looper)
    private val scheduled = AtomicBoolean(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var volume: Int = 70

    private var tone: ToneGenerator? = null
    private var beeping = false

    /** 各通道是否处于预警（红框）状态；UI 观察此流渲染红框 */
    private val _alerts = kotlinx.coroutines.flow.MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val alerts: StateFlow<Map<Int, Boolean>> = _alerts

    // ------------------------------------------------------------------
    // 帧入口（USB 回调线程）：只做轻量判断 + 最新帧拷贝
    // ------------------------------------------------------------------

    override fun onFrame(frame: CameraFrame) {
        val slot = frame.cameraId
        val rt = slots.value.getOrNull(slot) ?: return
        val cfg = rt.config ?: return
        if (!cfg.warn) {
            if (warnOffRequested.add(slot)) requestDeactivate(slot)
            return
        }
        warnOffRequested.remove(slot)

        val now = SystemClock.elapsedRealtime()
        val last = lastSubmitAt[slot] ?: 0L
        val interval = com.wheeltech.blindcam.domain.DevicePerformance.warningIntervalMs
        if (now - last < interval) return
        lastSubmitAt[slot] = now

        // USB 缓冲会在回调返回后被下一帧覆盖，必须立即拷贝（约 100–500KB @5fps）
        pending[slot] = Pending(
            frame.data.copyOf(),
            frame.width,
            frame.height
        )
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (scheduled.compareAndSet(false, true)) {
            handler.post(drainTask)
        }
    }

    private val drainTask = Runnable {
        try {
            // 一次把当前所有待分析帧处理完（每路最多一张）
            val snapshot = pending.entries.toList()
            for (entry in snapshot) {
                val slot = entry.key
                val p = pending.remove(slot) ?: continue
                analyze(slot, p)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "analyze drain failed: ${t.message}")
        } finally {
            scheduled.set(false)
            if (pending.isNotEmpty()) scheduleDrain()
        }
    }

    // ------------------------------------------------------------------
    // 分析（独立线程）
    // ------------------------------------------------------------------

    private fun analyze(slot: Int, p: Pending) {
        val rt = slots.value.getOrNull(slot)
        if (rt?.config?.warn != true) {
            deactivate(slot)
            return
        }
        val sample = MjpegDecoder.sampleSizeFor(p.width, p.height)
        val bmp = MjpegDecoder.decode(p.jpeg, sampleSize = sample) ?: return
        try {
            processFrame(slot, bmp)
        } finally {
            bmp.recycle()
        }
    }

    private fun processFrame(slot: Int, bmp: Bitmap) {
        val st = states.getOrPut(slot) { ChannelState() }
        val gray = downscaleGray(bmp)
        val gw = GRAY_SCALE
        val gh = gray.size / gw
        st.lastAnalyzedAt = SystemClock.elapsedRealtime()

        val prev = st.prevGray
        if (st.warmup < WARMUP_FRAMES || prev == null || prev.size != gray.size) {
            st.prevGray = gray
            st.warmup++
            st.motionStreak = 0
            st.stillStreak = 0
            if (st.active) {
                st.active = false
                publish(slot, false, anyActive())
            }
            return
        }

        // 块级运动统计：以灰度网格 2x2 为一个判定块
        var motionBlocks = 0
        var totalBlocks = 0
        var by = 0
        while (by < gh) {
            var bx = 0
            while (bx < gw) {
                var c = 0
                var counted = 0
                var yy = by
                while (yy < minOf(gh, by + 2)) {
                    var xx = bx
                    while (xx < minOf(gw, bx + 2)) {
                        val cur = gray[yy * gw + xx]
                        val old = prev[yy * gw + xx]
                        if (abs(cur - old) > PIXEL_DIFF) c++
                        counted++
                        xx++
                    }
                    yy++
                }
                totalBlocks++
                if (counted > 0 && c.toFloat() / counted >= BLOCK_PIXEL_RATIO) motionBlocks++
                bx += 2
            }
            by += 2
        }
        st.prevGray = gray

        val ratio = if (totalBlocks > 0) motionBlocks.toFloat() / totalBlocks else 0f
        val isLocalMotion = ratio in MOTION_BLOCK_MIN..MOTION_BLOCK_MAX

        if (isLocalMotion) {
            st.motionStreak++
            st.stillStreak = 0
        } else {
            st.stillStreak++
            st.motionStreak = 0
        }

        when {
            !st.active && st.motionStreak >= ENTER_EVIDENCE -> {
                st.active = true
                publish(slot, true, true)
            }
            st.active && st.stillStreak >= EXIT_EVIDENCE -> {
                st.active = false
                publish(slot, false, anyActive())
            }
        }
    }

    /** 降采样为 64 宽的灰度网格（输入已按 sampleSize 解码，开销可控） */
    private fun downscaleGray(bmp: Bitmap): IntArray {
        val ratio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val gw = GRAY_SCALE
        val gh = (GRAY_SCALE / ratio).toInt().coerceIn(8, GRAY_SCALE * 2)
        val small = Bitmap.createScaledBitmap(bmp, gw, gh, true)
        val px = IntArray(gw * gh)
        small.getPixels(px, 0, gw, 0, 0, gw, gh)
        small.recycle()
        for (i in px.indices) {
            val p = px[i]
            px[i] = (((p shr 16) and 0xFF) * 299 +
                    ((p shr 8) and 0xFF) * 587 +
                    (p and 0xFF) * 114) / 1000
        }
        return px
    }

    /** 仅在分析线程调用 */
    private fun anyActive(): Boolean = states.values.any { it.active }

    private fun deactivate(slot: Int) {
        warnOffRequested.add(slot)
        val st = states[slot]
        if (st != null) {
            st.active = false
            st.prevGray = null
            st.warmup = 0
            st.motionStreak = 0
            st.stillStreak = 0
        }
        publish(slot, false, anyActive())
    }

    private fun requestDeactivate(slot: Int) {
        handler.post { deactivate(slot) }
    }

    // ------------------------------------------------------------------
    // UI / 声音（主线程）
    // ------------------------------------------------------------------

    private fun publish(slot: Int, active: Boolean, anyActive: Boolean) {
        mainHandler.post {
            _alerts.value = _alerts.value.toMutableMap().apply { this[slot] = active }
            if (active) {
                startBeep()
            } else if (!anyActive) {
                stopBeep()
            }
        }
    }

    /**
     * 强提醒蜂鸣：用 [ToneGenerator] 走 STREAM_ALARM，而不是系统通知铃声。
     * 车机上通知铃声经常被静音/被系统音效替换，导致“音量 100 也没声音”；
     * 警报流 + 合成提示音更可靠，也更符合盲区预警的强提醒需求。
     */
    private fun startBeep() {
        if (beeping) return
        beeping = true
        ensureTone()
        Logger.i(TAG, "beep start volume=$volume tone=${tone != null}")
        mainHandler.removeCallbacks(beepTask)
        mainHandler.post(beepTask)
    }

    private fun stopBeep() {
        if (!beeping) return
        beeping = false
        Logger.i(TAG, "beep stop")
        mainHandler.removeCallbacks(beepTask)
        runCatching { tone?.stopTone() }
    }

    private fun ensureTone() {
        if (tone != null) return
        runCatching {
            // ToneGenerator 的音量只能在构造时指定；设置变化时重建实例
            tone = ToneGenerator(AudioManager.STREAM_ALARM, volume.coerceIn(0, 100))
        }.onFailure { Logger.w(TAG, "ToneGenerator init failed: ${it.message}") }
    }

    private val beepTask = object : Runnable {
        override fun run() {
            if (!beeping) return
            val started = runCatching {
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 220) == true
            }.getOrDefault(false)
            if (!started) {
                runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220) }
            }
            // 连续运动时每 420ms 重复一次，形成明显的警报节奏
            mainHandler.postDelayed(this, 420)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                val now = SystemClock.elapsedRealtime()
                val stale = states.filter { (_, st) ->
                    st.active && now - st.lastAnalyzedAt > STALE_FRAME_MS
                }.keys.toList()
                stale.forEach { slot ->
                    Logger.w(TAG, "no frame ${STALE_FRAME_MS}ms, clear alert slot=$slot")
                    deactivate(slot)
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "watchdog failed: ${t.message}")
            } finally {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    /** 兼容旧调用：按设置调整蜂鸣音量（0–100） */
    fun applyVolume(volume: Int) {
        this.volume = volume.coerceIn(0, 100)
        mainHandler.post { onVolumeChanged() }
    }

    /** 设置页“测试蜂鸣”：让用户确认音量/扬声器是否正常（响约 1 秒） */
    fun testBeep() {
        mainHandler.post {
            startBeep()
            mainHandler.postDelayed({ stopBeep() }, 1_000)
        }
    }

    /**
     * 设置页“测试预警”：强制该路红框+蜂鸣 4 秒，方便确认红色警示特效与声音。
     * 不参与运动检测状态，只改 UI 的 alerts 流；到时自动恢复。
     */
    fun testAlert(slot: Int, durationMs: Long = 4_000) {
        mainHandler.post {
            _alerts.value = _alerts.value.toMutableMap().apply { this[slot] = true }
            startBeep()
            mainHandler.postDelayed({
                val next = _alerts.value.toMutableMap().apply { this[slot] = false }
                _alerts.value = next
                if (next.values.none { it }) stopBeep()
            }, durationMs)
        }
    }

    private fun onVolumeChanged() {
        if (beeping) {
            // ToneGenerator 音量不可动态修改：停掉，按新音量重建，再继续警报节奏
            stopBeep()
            runCatching { tone?.release() }
            tone = null
            startBeep()
        } else {
            runCatching { tone?.release() }
            tone = null
        }
    }

    init {
        // 音量实时跟随设置（0–100）
        scope.launch {
            settingsState.collect { s ->
                volume = s.warnVolume.coerceIn(0, 100)
                mainHandler.post { onVolumeChanged() }
            }
        }
        // 摄像头拔出 / 关闭预警 / 停止取流：立即清掉残留红框与声音
        scope.launch {
            slots.collect { list ->
                val live = list.filter { it.state != CameraSupervisor.SlotState.EMPTY }
                val keep = live.filter { it.config?.warn == true }.map { it.slot }.toSet()
                for (slot in 0 until UvcCameraManager.MAX_SLOTS) {
                    if (slot !in keep) requestDeactivate(slot)
                }
            }
        }
        // 看门狗：取流卡住时不再有帧，不能让红框/蜂鸣一直挂着
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }
}
