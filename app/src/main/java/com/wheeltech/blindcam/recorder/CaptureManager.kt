package com.wheeltech.blindcam.recorder

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.wheeltech.blindcam.domain.CameraFrame
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.imaging.FrameDenoiser
import com.wheeltech.blindcam.util.Logger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 拍照 / 录像管理（FrameListener）：
 * - native 回调线程只做"拷贝字节 + 记录时间戳 + 投递"，绝不写文件 / 不做编码
 * - 每个槽位一个独立串行 IO 线程，负责 JPEG 落盘与 MP4 硬件编码
 * - 拍照直接保存设备输出的原始 JPEG（最高保真、零重编码）
 * - 录像用 [Mp4Recorder] 硬件编码为通用 MP4（H.264），每帧用真实时间戳，回放时长=真实时长
 */
class CaptureManager(
    context: Context,
    private val mediaSaver: MediaSaver
) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "capture"
        private val PHOTO_TS get() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        private val VIDEO_TS get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        /** 把设备名清理为文件名安全片段（保留中文，去掉分隔符/空白/特殊字符） */
        fun safeName(name: String?): String {
            val n = name?.trim().orEmpty().ifBlank { "CAM" }
            return n.replace(Regex("[\\\\/:*?\"<>|\\[\\]\\s]"), "_")
                .replace(Regex("_+"), "_")
                .trim('_')
                .take(24)
                .ifBlank { "CAM" }
        }
    }

    private class RecState(
        val recorder: Mp4Recorder,
        val cacheFile: java.io.File,
        val videoDirUri: String,
        val displayName: String,
        val mirrorH: Boolean,
        val rotationDeg: Int,
        val osd: OsdRenderer.Overlay?,
        val denoiser: FrameDenoiser,
        var frames: Int = 0
    )

    /** 每槽位串行 IO 线程（MP4 的 EGL/编码器在该线程创建并使用） */
    private val ioThreads = ConcurrentHashMap<Int, HandlerThread>()
    private val ioHandlers = ConcurrentHashMap<Int, Handler>()

    /** 待拍照请求（取一次后清除），携带该画框当前镜像/旋转/设备名 */
    private class PhotoReq(
        val dirUri: String,
        val deviceName: String,
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
    private val pendingWrites = ConcurrentHashMap<Int, AtomicInteger>()

    /** 录像最大排队帧数（约 2 秒，超过说明编码跟不上，主动丢帧） */
    private val maxPendingFrames = 60

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
        deviceName: String,
        mirrorH: Boolean,
        rotationDeg: Int,
        cb: (Result<String>) -> Unit
    ) {
        pendingPhotos[slot] = PhotoReq(photoDirUri, safeName(deviceName), mirrorH, rotationDeg, cb)
    }

    /** 开始录像（分辨率/帧率 = 当前取流档位；镜像/旋转/OSD/降噪应用于输出，旋转 90/270 时宽高互换） */
    fun startRecording(
        slot: Int,
        width: Int,
        height: Int,
        fps: Int,
        videoDirUri: String,
        deviceName: String,
        mirrorH: Boolean,
        rotationDeg: Int,
        osd: OsdRenderer.Overlay? = null,
        denoiseLevel: Int = 0,
        bitrateOverride: Int = 0
    ): Boolean {
        if (recordings.containsKey(slot)) return false
        return try {
            // 旋转 90/270 后输出画面宽高互换
            val swapped = JpegTransformer.swapped(rotationDeg)
            val outW = if (swapped) height else width
            val outH = if (swapped) width else height
            val name = "V_${safeName(deviceName)}_${VIDEO_TS.format(Date())}_${outW}x${outH}.mp4"
            val cache = mediaSaver.newCacheFile("mp4")
            val bitrate = if (bitrateOverride > 0) bitrateOverride
                else Mp4Recorder.suggestBitrate(outW, outH, fps)
            // 编码器在首帧（IO 线程）才真正初始化，保证 EGL 上下文与编码调用在同一线程
            val recorder = Mp4Recorder(cache, outW, outH, bitrate, fps)
            // 该路录像独立降噪器（与预览同档位，但历史帧独立维护），预算按该路帧率
            val denoiser = FrameDenoiser(denoiseLevel, budgetMs = (1000 / fps.coerceAtLeast(1)))
            recordings[slot] = RecState(
                recorder, cache, videoDirUri, name, mirrorH, rotationDeg, osd, denoiser
            )
            Logger.i(TAG, "recording start slot=$slot $name ${outW}x${outH}@$fps " +
                "mirror=$mirrorH rot=$rotationDeg osd=${osd?.any == true} " +
                "denoise=$denoiseLevel bitrate=${bitrate / 1000}kbps")
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
                val mp4 = state.recorder.finish()
                if (mp4 == null) {
                    state.denoiser.release()
                    cb(Result.failure(IllegalStateException("未录制到任何帧")))
                    return@post
                }
                val result = mediaSaver.publishVideo(state.cacheFile, state.displayName, state.videoDirUri)
                state.denoiser.release()
                Logger.i(TAG, "recording saved slot=$slot frames=${state.frames} -> $result")
                cb(Result.success(result))
            } catch (t: Throwable) {
                runCatching { state.recorder.abort() }
                state.denoiser.release()
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
        videoDirUri: String,
        bitrate: Int = 0,
        loopEnabled: Boolean = false,
        segmentMinutes: Int = 3,
        keepFreeMb: Int = 1024
    ): Boolean = composite.start(
        cells, osdName, osdClock, osdSpeed, videoDirUri,
        bitrate, loopEnabled, segmentMinutes, keepFreeMb
    )

    fun stopComposite(cb: (Result<String>) -> Unit) = composite.stop(cb)

    fun isCompositeRecording(): Boolean = composite.isRunning

    override fun onFrame(frame: CameraFrame) {
        val slot = frame.cameraId
        // 总录像：录制中始终转发最新帧（内部会拷贝）
        if (composite.isRunning) composite.onFrame(slot, frame.data)

        val photoReq = pendingPhotos.remove(slot)
        val rec = recordings[slot]
        if (photoReq == null && rec == null && !composite.isRunning) return

        // 帧到达真实时间（在 native 回调线程取，单调），随任务投递给 IO 线程，保证录像时间戳真实
        val arriveNs = SystemClock.elapsedRealtimeNanos()

        // 拍照：回调内拷贝原始字节；解码/旋转/重编码放到 IO 线程
        if (photoReq != null) {
            val req = photoReq
            val raw = frame.data.copyOf(frame.data.size)
            handlerFor(slot).post {
                try {
                    val jpeg = JpegTransformer.transform(raw, req.mirrorH, req.rotationDeg)
                    val name = "P_${req.deviceName}_${PHOTO_TS.format(Date())}.jpg"
                    val path = mediaSaver.savePhoto(jpeg, name, req.dirUri)
                    req.cb(Result.success(path))
                } catch (t: Throwable) {
                    Logger.e(TAG, "save photo failed", t)
                    req.cb(Result.failure(t))
                }
            }
        }
        // 录像：仅在编码队列未满时才拷贝投递（满则丢帧，不拷贝，避免 OOM）
        if (rec != null) {
            val state = rec
            val pending = pendingWrites.getOrPut(slot) { AtomicInteger(0) }
            if (pending.get() < maxPendingFrames) {
                pending.incrementAndGet()
                val raw = frame.data.copyOf(frame.data.size)
                val ptsNs = arriveNs
                handlerFor(slot).post {
                    var bmp: android.graphics.Bitmap? = null
                    var enc: android.graphics.Bitmap? = null
                    try {
                        bmp = JpegTransformer.transformToBitmap(
                            raw, state.mirrorH, state.rotationDeg, state.osd
                        )
                        if (bmp != null) {
                            // 该路独立实时降噪（关闭时返回原图，零拷贝）
                            enc = state.denoiser.process(bmp)
                            state.recorder.frame(enc, ptsNs)
                            state.frames++
                        }
                    } catch (t: Throwable) {
                        Logger.w(TAG, "encode frame failed: ${t.message}")
                    } finally {
                        // 降噪开启时 enc 是降噪器内部复用位图（不能回收），只回收解码原图；
                        // 关闭时 enc===bmp，回收 enc 即可。
                        if (enc != null && enc !== bmp) runCatching { bmp?.recycle() }
                        else runCatching { enc?.recycle() }
                        pending.decrementAndGet()
                    }
                }
            }
        }
    }

    fun shutdown() {
        // 退出时收尾未完成录像
        recordings.forEach { (_, state) ->
            runCatching { state.recorder.abort() }
            runCatching { state.denoiser.release() }
        }
        recordings.clear()
        runCatching { composite.shutdown() }
        ioThreads.values.forEach { it.quitSafely() }
        ioThreads.clear()
        ioHandlers.clear()
    }
}
