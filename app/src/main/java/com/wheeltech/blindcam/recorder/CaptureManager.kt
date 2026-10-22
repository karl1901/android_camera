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
        var frames: Int = 0
    )

    /** 每槽位串行 IO 线程 */
    private val ioThreads = ConcurrentHashMap<Int, HandlerThread>()
    private val ioHandlers = ConcurrentHashMap<Int, Handler>()

    /** 待拍照请求（取一次后清除） */
    private class PhotoReq(val dirUri: String, val cb: (Result<String>) -> Unit)
    private val pendingPhotos = ConcurrentHashMap<Int, PhotoReq>()

    /** 进行中的录像 */
    private val recordings = ConcurrentHashMap<Int, RecState>()

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

    /** 拍照：抓下一帧保存 JPEG */
    fun takePhoto(slot: Int, photoDirUri: String, cb: (Result<String>) -> Unit) {
        pendingPhotos[slot] = PhotoReq(photoDirUri, cb)
    }

    /** 开始录像（分辨率/帧率 = 当前取流档位） */
    fun startRecording(slot: Int, width: Int, height: Int, fps: Int, videoDirUri: String): Boolean {
        if (recordings.containsKey(slot)) return false
        return try {
            val name = "BLIND_${videoFmt.format(Date())}_${width}x${height}.avi"
            val cache = mediaSaver.newCacheFile("avi")
            val writer = AviWriter(cache, width, height, fps)
            recordings[slot] = RecState(writer, cache, videoDirUri, name)
            Logger.i(TAG, "recording start slot=$slot $name ${width}x${height}@$fps")
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
                state.writer.finish()
                val result = mediaSaver.publishVideo(state.cacheFile, state.displayName, state.videoDirUri)
                Logger.i(TAG, "recording saved slot=$slot frames=${state.frames} -> $result")
                cb(Result.success(result))
            } catch (t: Throwable) {
                mediaSaver.discardCache(state.cacheFile)
                Logger.e(TAG, "publish video failed", t)
                cb(Result.failure(t))
            }
        }
    }

    fun isRecording(slot: Int): Boolean = recordings.containsKey(slot)

    override fun onFrame(frame: CameraFrame) {
        val slot = frame.cameraId
        val photoReq = pendingPhotos.remove(slot)
        val rec = recordings[slot]
        if (photoReq == null && rec == null) return

        // native 数组在回调返回后会被下一帧覆盖，需要立即拷贝。
        // 拍照：拷贝一份
        if (photoReq != null) {
            val req = photoReq
            val jpeg = frame.data.copyOf(frame.data.size)
            handlerFor(slot).post {
                try {
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
                val jpeg = frame.data.copyOf(frame.data.size)
                handlerFor(slot).post {
                    try {
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
        ioThreads.values.forEach { it.quitSafely() }
        ioThreads.clear()
        ioHandlers.clear()
    }
}
