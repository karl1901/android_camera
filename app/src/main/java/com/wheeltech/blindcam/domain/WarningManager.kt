package com.wheeltech.blindcam.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.media.Ringtone
import android.os.Handler
import android.os.Looper
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.util.Logger
import kotlin.math.abs

/**
 * 盲区预警管理（FrameListener）：
 * - 对开启预警的通道做"运动检测"（块级、运动自适应、带证据/预热）
 * - 仅当画面内有真实物体运动时：该画面显示红色边框 + 蜂鸣
 * - 静态画面（无运动）：不显示红框、不响铃（修复静态误报）
 *
 * 静态误报修复：
 * - 提高预热帧数；需要连续多帧确认才进入预警，连续多帧静止才退出（避免单帧噪声抖动）
 * - 块级判定 + 全局闪光过滤：MJPEG 噪声/自动曝光抖动常表现为"全屏均匀变化"，
 *   若运动块占比过高（全局亮度变化）则不视为局部目标
 * - 运动停止立即清边框、停响铃
 *
 * 真实分级（人/车/红绿灯）由 [com.wheeltech.blindcam.vision.SmartVisionEngine] 负责；
 * 本类在无模型时提供轻量运动检测兜底。
 */
class WarningManager(
    private val context: Context,
    private val slots: kotlinx.coroutines.flow.StateFlow<List<CameraSupervisor.SlotRuntime>>,
    private val settingsState: kotlinx.coroutines.flow.StateFlow<AppSettings>
) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "warning"
        private const val GRAY_SCALE = 64
        /** 单像素亮度变化阈值（提高，过滤 MJPEG 噪声） */
        private const val PIXEL_DIFF = 42
        /** 块内变化像素占比阈值（块算运动） */
        private const val BLOCK_PIXEL_RATIO = 0.18f
        /** 触发预警的运动块占比下限（局部目标） */
        private const val MOTION_BLOCK_MIN = 0.05f
        /** 运动块占比上限：超过视为全局亮度变化（AE/闪光），不触发 */
        private const val MOTION_BLOCK_MAX = 0.62f
        /** 块网格边长（像素） */
        private const val BLOCK_SIZE = 16
        /** 预热帧数（开始取流后先学习，不触发） */
        private const val WARMUP_FRAMES = 14
        /** 进入预警需连续运动帧 */
        private const val ENTER_EVIDENCE = 2
        /** 退出预警需连续静止帧 */
        private const val EXIT_EVIDENCE = 2
    }

    private class ChannelState {
        var prevGray: IntArray? = null
        var warmup = 0
        var motionStreak = 0
        var stillStreak = 0
        var active = false
    }

    private val states = HashMap<Int, ChannelState>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ringtone: Ringtone? = null
    private var ringtoneCreated = false

    private fun ensureRingtone() {
        if (ringtoneCreated) return
        ringtoneCreated = true
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(context, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "ringtone init failed: ${t.message}")
        }
    }

    override fun onFrame(frame: CameraFrame) {
        val slot = frame.cameraId
        val rt = slots.value.getOrNull(slot) ?: return
        val cfg = rt.config ?: return
        if (!cfg.warn) {
            // 该通道关闭预警：若之前在响/在亮，立即清掉
            val st = states[slot]
            if (st != null && st.active) {
                st.active = false
                updateUi(slot, false)
            }
            return
        }

        val bmp = BitmapFactory.decodeByteArray(frame.data, 0, frame.data.size) ?: return
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

        val prev = st.prevGray
        if (st.warmup < WARMUP_FRAMES || prev == null) {
            st.prevGray = gray
            st.warmup++
            st.motionStreak = 0
            st.stillStreak = 0
            if (st.active) { st.active = false; updateUi(slot, false) }
            return
        }

        // 块级运动统计：以灰度网格 2x2 为一个判定块（简单稳定）
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
                updateUi(slot, true)
            }
            st.active && st.stillStreak >= EXIT_EVIDENCE -> {
                st.active = false
                updateUi(slot, false)
            }
        }
    }

    /** 降采样为灰度（GRAY_SCALE×GRAY_SCALE），返回长度 = gw*gh（按宽高比） */
    private fun downscaleGray(bmp: Bitmap): IntArray {
        val ratio = bmp.width.toFloat() / bmp.height
        val gw = GRAY_SCALE
        val gh = (GRAY_SCALE / ratio).toInt().coerceAtLeast(8)
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

    /** 各通道是否处于预警（红框）状态；UI 观察此流渲染红框 */
    private val _alerts = kotlinx.coroutines.flow.MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val alerts: kotlinx.coroutines.flow.StateFlow<Map<Int, Boolean>> = _alerts

    private fun updateUi(slot: Int, active: Boolean) {
        mainHandler.post {
            // 红框状态（MainActivity 观察 alerts 渲染）
            _alerts.value = _alerts.value.toMutableMap().apply { this[slot] = active }
            // 蜂鸣（运动才响，停止立即停）
            if (active) {
                ensureRingtone()
                runCatching { ringtone?.let { if (!it.isPlaying) it.play() } }
            } else {
                val anyActive = states.values.any { it.active }
                if (!anyActive) runCatching { if (ringtone?.isPlaying == true) ringtone?.stop() }
            }
        }
    }

    /** 按设置调整蜂鸣音量（0–100） */
    fun applyVolume(volume: Int) {
        try {
            ringtone?.volume = volume / 100f
        } catch (_: Throwable) { }
    }
}
