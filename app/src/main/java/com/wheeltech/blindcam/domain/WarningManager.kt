package com.wheeltech.blindcam.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 盲区预警（FrameListener）：轻量移动目标检测。
 *
 * - 对每路开启预警的画面，按 ~8fps 抽帧，降采样到 64x36 灰度后做帧间差分；
 *   变化像素占比超过阈值并连续命中即判定「有物体进入盲区」。
 * - 检测在独立串行线程完成（native 回调线程只拷贝字节），不阻塞取流 / 渲染。
 * - 命中后通过 [alerts] 通知界面画红框，并用 [ToneGenerator] 按用户设置音量响铃；
 *   运动持续期间周期响铃，停止约 1.5s 后解除。
 *
 * 这是不依赖模型 / 网络、开销极小的运动检测，足以识别人/车/物体进入视野；
 * 不做物体分类（不区分具体是人还是车）。
 */
class WarningManager(
    context: Context,
    private val slots: StateFlow<List<CameraSupervisor.SlotRuntime>>,
    private val settings: StateFlow<AppSettings>
) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "warning"
        private const val GW = 64
        private const val GH = 36
        private const val PROCESS_INTERVAL_MS = 120L
        private const val PIXEL_DIFF = 28
        private const val MOTION_RATIO = 0.05f
        private const val HIT_REQUIRED = 2
        private const val ALERT_HOLD_MS = 1500L
        private const val BEEP_INTERVAL_MS = 1200L
    }

    private class MotionState {
        var prev: IntArray? = null
        var hits = 0
        var alertUntil = 0L
        var lastBeep = 0L
        var lastProcessMs = 0L
    }

    private val states = ConcurrentHashMap<Int, MotionState>()
    private val thread = HandlerThread("warning").apply { start() }
    private val handler = Handler(thread.looper)
    private val appContext = context.applicationContext

    private val _alerts = kotlinx.coroutines.flow.MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val alerts: StateFlow<Map<Int, Boolean>> = _alerts

    override fun onFrame(frame: CameraFrame) {
        val slot = frame.cameraId
        val rt = slots.value.getOrNull(slot)
        val enabled = rt?.config?.warn == true &&
            rt.state == CameraSupervisor.SlotState.STREAMING
        if (!enabled) {
            if (states.remove(slot) != null) setAlert(slot, false)
            return
        }
        val state = states.getOrPut(slot) { MotionState() }
        val now = SystemClock.elapsedRealtime()
        if (now - state.lastProcessMs < PROCESS_INTERVAL_MS) return
        state.lastProcessMs = now
        // 回调内只拷贝；decode / 差分 / 响铃全部到独立线程
        val raw = frame.data.copyOf(frame.data.size)
        handler.post { runCatching { analyze(slot, state, raw, now) }.onFailure { Logger.w(TAG, "analyze failed: ${it.message}") } }
    }

    private fun analyze(slot: Int, state: MotionState, jpeg: ByteArray, nowMs: Long) {
        val gray = downsampleGray(jpeg) ?: return
        val prev = state.prev
        if (prev != null && prev.size == gray.size) {
            var changed = 0
            for (i in gray.indices) {
                if (abs(gray[i] - prev[i]) > PIXEL_DIFF) changed++
            }
            val ratio = changed.toFloat() / gray.size
            if (ratio >= MOTION_RATIO) state.hits++ else state.hits = 0
        }
        state.prev = gray

        val wasAlert = state.alertUntil > nowMs
        if (state.hits >= HIT_REQUIRED) {
            state.alertUntil = nowMs + ALERT_HOLD_MS
            state.hits = 0
            if (!wasAlert) {
                setAlert(slot, true)
                Logger.i(TAG, "motion alert slot=$slot")
            }
            maybeBeep(state)
        } else if (state.alertUntil > nowMs) {
            // 运动持续，红框保持并周期响铃
            maybeBeep(state)
        } else if (wasAlert) {
            setAlert(slot, false)
        }
    }

    private fun downsampleGray(jpeg: ByteArray): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = maxOf(1, minOf(bounds.outWidth / GW, bounds.outHeight / GH))
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        var bmp = decoded
        try {
            if (bmp.width != GW || bmp.height != GH) {
                val scaled = Bitmap.createScaledBitmap(bmp, GW, GH, true)
                if (scaled != bmp) {
                    bmp.recycle()
                    bmp = scaled
                }
            }
            val pixels = IntArray(GW * GH)
            bmp.getPixels(pixels, 0, GW, 0, 0, GW, GH)
            val gray = IntArray(GW * GH)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[i] = (r * 299 + g * 587 + b * 114) / 1000
            }
            return gray
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    private fun maybeBeep(state: MotionState) {
        val vol = settings.value.warnVolume
        if (vol <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - state.lastBeep < BEEP_INTERVAL_MS) return
        state.lastBeep = now
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, vol.coerceIn(0, 100))
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
            handler.postDelayed({ runCatching { tone.release() } }, 220L)
        }
    }

    private fun setAlert(slot: Int, on: Boolean) {
        val cur = _alerts.value.toMutableMap()
        if (on) cur[slot] = true else cur.remove(slot)
        _alerts.value = cur
    }

    fun shutdown() {
        thread.quitSafely()
        states.clear()
    }
}
