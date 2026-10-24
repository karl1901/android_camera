package com.wheeltech.blindcam.recorder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.wheeltech.blindcam.domain.CameraFrame
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.util.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拍照 / 录像管理（FrameListener）：
 * - native 回调线程只做"拷贝字节 + 投递"，绝不写文件
 * - 每个槽位一个独立串行 IO 线程，负责 JPEG 落盘与 AVI 帧封装
 * - 拍照直接保存设备输出的原始 JPEG（最高保真、零重编码）
 * - 录像用 [AviWriter] 把 MJPEG 封装为 AVI，分辨率/帧率取当前预览档位
 */
class CaptureManager(
    context: Context,
    private val mediaSaver: MediaSaver
) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "capture"
        private val NAME_FMT get() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private const val VIDEO_NAME_FMT = "yyyyMMdd_HHmmss"
    }

    private class RecState(
        val writer: AviWriter,
        val cacheFile: java.io.File,
        val videoDirUri: String,
        val displayName: String,
        val mirrorH: Boolean,
        val rotationDeg: Int,
        val osd: OsdRenderer.Overlay?,
        var frames: Int = 0,
        var firstFrameMs: Long = 0L,
        var lastFrameMs: Long = 0L
    )

    /** 每槽位串行 IO 线程 */
    private val ioThreads = ConcurrentHashMap<Int, HandlerThread>()
    private val ioHandlers = ConcurrentHashMap<Int, Handler>()

    /** 待拍照请求（取一次后清除），携带该画框当前镜像/旋转设置 */
    private class PhotoReq(
        val dirUri: String,
        val mirrorH: Boolean,
        val rotationDeg: Int,
        val cb: (Result<String>) -> Unit
    )
    private val pendingPhotos = ConcurrentHashMap<Int, PhotoReq>()

    /** 进行中的录像 */
    private val recordings = ConcurrentHashMap<Int, RecState>()

    /** 总录像（多路合成） */
    private val composite = CompositeRecorder(mediaSaver)

    /** 每槽位待写帧计数（背压：超过阈值丢帧，避免无限堆积 OOM） */
    private val pendingWrites = ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicInteger>()

    /** 录像最大排队帧数（约 2 秒，超过说明写盘跟不上，主动丢帧） */
    private val maxPendingFrames = 60

    private val videoFmt = SimpleDateFormat(VIDEO_NAME_FMT, Locale.US)

    private fun handlerFor(slot: Int): Handler {
        return ioHandlers.getOrPut(slot) {
            val t = HandlerThread("capture-$slot").apply { start() }
            ioThreads[slot] = t
            Handler(t.looper)
        }
    }

    /** 拍照：抓下一帧，按当前镜像/旋转变换后保存 JPEG（与预览所见一致） */
    fun takePhoto(
        slot: Int,
        photoDirUri: String,
        mirrorH: Boolean,
        rotationDeg: Int,
        cb: (Result<String>) -> Unit
    ) {
        pendingPhotos[slot] = PhotoReq(photoDirUri, mirrorH, rotationDeg, cb)
    }

    /** 开始录像（分辨率/帧率 = 当前取流档位；镜像/旋转/OSD 应用于输出，旋转 90/270 时宽高互换） */
    fun startRecording(
        slot: Int,
        width: Int,
        height: Int,
        fps: Int,
        videoDirUri: String,
        mirrorH: Boolean,
        rotationDeg: Int,
        osd: OsdRenderer.Overlay? = null
    ): Boolean {
        if (recordings.containsKey(slot)) return false
        return try {
            // 旋转 90/270 后输出画面宽高互换，AVI 头按输出尺寸写
            val outW = if (JpegTransformer.swapped(rotationDeg)) height else width
            val outH = if (JpegTransformer.swapped(rotationDeg)) width else height
            val name = "BLIND_${videoFmt.format(Date())}_${outW}x${outH}.avi"
            val cache = mediaSaver.newCacheFile("avi")
            val writer = AviWriter(cache, outW, outH, fps)
            recordings[slot] = RecState(writer, cache, videoDirUri, name, mirrorH, rotationDeg, osd)
            Logger.i(TAG, "recording start slot=$slot $name ${outW}x${outH}@$fps " +
                "mirror=$mirrorH rot=$rotationDeg osd=${osd?.any == true}")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "startRecording failed", t)
            false
        }
    }

    /** 停止录像并发布文件，返回最终文件名（回调） */
    fun stopRecording(slot: Int, cb: (Result<String>) -> Unit) {
        val state = recordings.remove(slot)
        if (state == null) {
            cb(Result.failure(IllegalStateException("该通道未在录像")))
            return
        }
        handlerFor(slot).post {
            try {
                // 按真实帧时间戳算平均帧率（设备实际常为 15–21fps，而非协商的 30fps），
                // 回填到 AVI 头，保证回放时长=真实时长，不再加速
                val n = state.frames
                val durMs = state.lastFrameMs - state.firstFrameMs
                val realFps = if (n >= 2 && durMs > 0) {
                    kotlin.math.round(((n - 1) * 1000.0) / durMs).toInt().coerceIn(1, 60)
                } else {
                    null
                }
                state.writer.finish(realFps)
                val result = mediaSaver.publishVideo(state.cacheFile, state.displayName, state.videoDirUri)
                Logger.i(TAG, "recording saved slot=$slot frames=$n realFps=$realFps " +
                    "dur=${durMs}ms -> $result")
                cb(Result.success(result))
            } catch (t: Throwable) {
                mediaSaver.discardCache(state.cacheFile)
                Logger.e(TAG, "publish video failed", t)
                cb(Result.failure(t))
            }
        }
    }

    fun isRecording(slot: Int): Boolean = recordings.containsKey(slot)

    // ------------------------------------------------------------------
    // 总录像（多路合成）
    // ------------------------------------------------------------------
    fun startComposite(
        cells: List<CompositeRecorder.Cell>,
        osdName: Boolean,
        osdClock: Boolean,
        osdSpeed: Boolean,
        videoDirUri: String
    ): Boolean = composite.start(cells, osdName, osdClock, osdSpeed, videoDirUri)

    fun stopComposite(cb: (Result<String>) -> Unit) = composite.stop(cb)

    fun isCompositeRecording(): Boolean = composite.isRunning

    override fun onFrame(frame: CameraFrame) {
        val slot = frame.cameraId
        // 总录像：录制中始终转发最新帧（内部会拷贝）
        if (composite.isRunning) composite.onFrame(slot, frame.data)

        val photoReq = pendingPhotos.remove(slot)
        val rec = recordings[slot]
        if (photoReq == null && rec == null && !composite.isRunning) return

        // native 数组在回调返回后会被下一帧覆盖，需要立即拷贝。
        // 拍照：拷贝一份
        if (photoReq != null) {
            val req = photoReq
            // 回调内先拷贝原始字节；解码/旋转/镜像/重编码放到 IO 线程
            val raw = frame.data.copyOf(frame.data.size)
            handlerFor(slot).post {
                try {
                    val jpeg = JpegTransformer.transform(raw, req.mirrorH, req.rotationDeg)
                    val name = "BLIND_${NAME_FMT.format(Date())}.jpg"
                    val path = mediaSaver.savePhoto(jpeg, name, req.dirUri)
                    req.cb(Result.success(path))
                } catch (t: Throwable) {
                    Logger.e(TAG, "save photo failed", t)
                    req.cb(Result.failure(t))
                }
            }
        }
        // 录像：仅在写盘队列未满时才拷贝投递（满则丢帧，不拷贝，避免 OOM）
        if (rec != null) {
            val state = rec
            val pending = pendingWrites.getOrPut(slot) {
                java.util.concurrent.atomic.AtomicInteger(0)
            }
            if (pending.get() < maxPendingFrames) {
                pending.incrementAndGet()
                // 回调内拷贝原始字节；镜像/旋转重编码在 IO 线程完成
                val raw = frame.data.copyOf(frame.data.size)
                handlerFor(slot).post {
                    try {
                        val jpeg = JpegTransformer.transform(
                            raw, state.mirrorH, state.rotationDeg, state.osd
                        )
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        if (state.frames == 0) state.firstFrameMs = nowMs
                        state.lastFrameMs = nowMs
                        state.writer.addFrame(jpeg)
                        state.frames++
                    } catch (t: Throwable) {
                        Logger.w(TAG, "write frame failed: ${t.message}")
                    } finally {
                        pending.decrementAndGet()
                    }
                }
            }
        }
    }

    fun shutdown() {
        // 退出时收尾未完成录像
        recordings.forEach { (slot, state) ->
            runCatching { state.writer.abort() }
        }
        recordings.clear()
        runCatching { composite.shutdown() }
        ioThreads.values.forEach { it.quitSafely() }
        ioThreads.clear()
        ioHandlers.clear()
    }
}
