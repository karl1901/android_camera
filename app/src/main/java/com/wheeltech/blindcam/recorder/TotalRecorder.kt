package com.wheeltech.blindcam.recorder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.wheeltech.blindcam.domain.CameraFrame
import com.wheeltech.blindcam.domain.GridLayoutSpec
import com.wheeltech.blindcam.imaging.FrameDenoiser
import com.wheeltech.blindcam.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 总录像（新架构）：只录制当前界面实际显示的画框，每个画框单独一个 MP4。
 *
 * 不再把多路合成到一个视频里，因此回看时：
 * - “全部画面”同时播放这些独立文件；
 * - 切到某一路时直接播放该路文件，不需要裁剪/放大，清晰度不受损。
 *
 * 存储约定（groupKey = ALL_yyyyMMdd_HHmmss）：
 * - 每个画框：`<groupKey>_c<slot>_<设备名>.mp4`
 * - 全画面预览图：`<groupKey>_preview.jpg`
 * - 元数据：`<groupKey>.json`（画面名、各部分文件名、预览图名）
 */
class TotalRecorder(private val mediaSaver: MediaSaver) {

    companion object {
        private const val TAG = "total-rec"
        private const val PREVIEW_W = 1280
        private const val PREVIEW_H = 720
        private const val MAX_PENDING = 30
    }

    class Cell(
        val slot: Int,
        val name: String,
        val mirror: Boolean,
        val rotation: Int,
        val denoiseLevel: Int,
        val srcWidth: Int,
        val srcHeight: Int,
        val fps: Int
    )

    private class State(
        val cell: Cell,
        val outW: Int,
        val outH: Int,
        val partName: String,
        val recorder: Mp4Recorder,
        val cache: File,
        val denoiser: FrameDenoiser,
        @Volatile var firstJpeg: ByteArray? = null,
        @Volatile var frames: Int = 0
    )

    private val handlerThreads = ConcurrentHashMap<Int, HandlerThread>()
    private val handlers = ConcurrentHashMap<Int, Handler>()
    private val pending = ConcurrentHashMap<Int, AtomicInteger>()
    private val states = ConcurrentHashMap<Int, State>()
    private val main = Handler(Looper.getMainLooper())

    private var cells: List<Cell> = emptyList()
    private var groupKey = ""
    private var videoDirUri = ""
    private var osdName = true
    private var osdClock = true

    @Volatile var isRunning = false
        private set
    @Volatile private var stopping = false
    val isStopping: Boolean get() = stopping

    private fun handlerFor(slot: Int): Handler = handlers.getOrPut(slot) {
        val t = HandlerThread("total-rec-$slot").apply { start() }
        handlerThreads[slot] = t
        Handler(t.looper)
    }

    fun start(
        cells: List<Cell>,
        osdName: Boolean,
        osdClock: Boolean,
        videoDirUri: String
    ): Boolean {
        if (isRunning || stopping || cells.isEmpty()) return false
        return try {
            this.cells = cells
            this.osdName = osdName
            this.osdClock = osdClock
            this.videoDirUri = videoDirUri
            groupKey = "ALL_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            states.clear()
            pending.clear()
            cells.forEach { c ->
                val swap = c.rotation == 90 || c.rotation == 270
                val rawW = if (swap) c.srcHeight else c.srcWidth
                val rawH = if (swap) c.srcWidth else c.srcHeight
                val maxW = if (cells.size >= 3) 1280 else 1920
                val maxH = if (cells.size >= 3) 720 else 1080
                val scale = minOf(1f, maxW.toFloat() / rawW, maxH.toFloat() / rawH)
                val outW = ((rawW * scale).toInt() / 2 * 2).coerceAtLeast(2)
                val outH = ((rawH * scale).toInt() / 2 * 2).coerceAtLeast(2)
                val partName = "${groupKey}_c${c.slot}_${CaptureManager.safeName(c.name)}.mp4"
                val cache = mediaSaver.newCacheFile("mp4")
                val bitrate = Mp4Recorder.suggestBitrate(outW, outH, c.fps)
                states[c.slot] = State(
                    cell = c,
                    outW = outW,
                    outH = outH,
                    partName = partName,
                    recorder = Mp4Recorder(cache, outW, outH, bitrate, c.fps),
                    cache = cache,
                    denoiser = FrameDenoiser(c.denoiseLevel, (1000 / c.fps.coerceAtLeast(1)))
                )
            }
            isRunning = true
            Logger.i(TAG, "start group=$groupKey cells=${cells.size} names=${cells.map { it.name }}")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "start failed", t)
            abortStates()
            false
        }
    }

    fun onFrame(frame: CameraFrame) {
        if (!isRunning) return
        val st = states[frame.cameraId] ?: return
        if (st.firstJpeg == null) {
            synchronized(st) {
                if (st.firstJpeg == null) st.firstJpeg = frame.data.copyOf(frame.data.size)
            }
        }
        val counter = pending.getOrPut(frame.cameraId) { AtomicInteger(0) }
        if (counter.get() >= MAX_PENDING) return
        counter.incrementAndGet()
        val raw = frame.data.copyOf(frame.data.size)
        val pts = SystemClock.elapsedRealtimeNanos()
        handlerFor(frame.cameraId).post {
            var transformed: Bitmap? = null
            var scaled: Bitmap? = null
            var encoded: Bitmap? = null
            try {
                transformed = JpegTransformer.transformToBitmap(
                    raw, st.cell.mirror, st.cell.rotation,
                    OsdRenderer.Overlay(
                        showName = osdName,
                        name = st.cell.name,
                        showClock = osdClock
                    )
                ) ?: return@post
                val fit = if (transformed.width != st.outW || transformed.height != st.outH) {
                    Bitmap.createScaledBitmap(transformed, st.outW, st.outH, true).also {
                        if (it !== transformed) {
                            scaled = it
                            transformed.recycle()
                        }
                    }
                } else transformed
                encoded = st.denoiser.process(fit)
                st.recorder.frame(encoded, pts)
                st.frames++
            } catch (t: Throwable) {
                Logger.w(TAG, "encode slot=${st.cell.slot} failed: ${t.message}")
            } finally {
                runCatching {
                    if (st.cell.denoiseLevel > 0) {
                        // 降噪输出是内部复用位图，不能回收；只回收缩放/旋转产生的临时输入
                        if (scaled != null) scaled?.recycle()
                        else if (transformed !== encoded) transformed?.recycle()
                    } else {
                        if (encoded != null && encoded !== transformed) encoded.recycle()
                        else transformed?.recycle()
                    }
                }
                counter.decrementAndGet()
            }
        }
    }

    fun stop(cb: (Result<String>) -> Unit) {
        if (!isRunning) {
            cb(Result.failure(IllegalStateException("总录像未在运行")))
            return
        }
        isRunning = false
        stopping = true
        val snapshot = states.values.sortedBy { it.cell.slot }
        if (snapshot.isEmpty()) {
            stopping = false
            cb(Result.failure(IllegalStateException("未录制到任何帧")))
            return
        }
        val remaining = AtomicInteger(snapshot.size)
        val results = ConcurrentHashMap<Int, String>()
        val names = ConcurrentHashMap<Int, String>()
        snapshot.forEach { st ->
            handlerFor(st.cell.slot).post {
                try {
                    val out = st.recorder.finish()
                    if (out != null && st.frames > 0 && out.exists() && out.length() > 0L) {
                        val published = mediaSaver.publishVideo(out, st.partName, videoDirUri)
                        results[st.cell.slot] = published
                        names[st.cell.slot] = st.cell.name
                    } else {
                        Logger.w(
                            TAG,
                            "discard empty slot=${st.cell.slot} frames=${st.frames} " +
                                "size=${out?.length() ?: -1}"
                        )
                        mediaSaver.discardCache(st.cache)
                    }
                } catch (t: Throwable) {
                    runCatching { st.recorder.abort() }
                    mediaSaver.discardCache(st.cache)
                    Logger.w(TAG, "finish slot=${st.cell.slot} failed: ${t.message}")
                } finally {
                    st.denoiser.release()
                    if (remaining.decrementAndGet() == 0) {
                        main.post { publishGroup(snapshot, results, names, cb) }
                    }
                }
            }
        }
    }

    private fun publishGroup(
        snapshot: List<State>,
        results: Map<Int, String>,
        names: Map<Int, String>,
        cb: (Result<String>) -> Unit
    ) {
        try {
            val previewName = "${groupKey}_preview.jpg"
            val previewFile = composePreview(snapshot)
            val previewPublished = if (previewFile != null) {
                runCatching { mediaSaver.publishPreview(previewFile, previewName, videoDirUri) }
                    .onFailure { mediaSaver.discardCache(previewFile) }
                    .getOrNull()
            } else null
            val cellNames = snapshot.mapNotNull { names[it.cell.slot] }
            val partNames = snapshot.mapNotNull { results[it.cell.slot] }
            if (partNames.isEmpty()) {
                cb(Result.failure(IllegalStateException("未录制到任何帧")))
            } else {
                mediaSaver.publishTotalMeta(
                    groupKey,
                    cellNames,
                    partNames,
                    previewPublished ?: "",
                    PREVIEW_W,
                    PREVIEW_H,
                    videoDirUri
                )
                cb(Result.success(groupKey))
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "publish group failed: ${t.message}")
            cb(Result.failure(t))
        } finally {
            states.clear()
            stopping = false
        }
    }

    private fun composePreview(snapshot: List<State>): File? {
        val bmp = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dest = RectF()
        snapshot.forEachIndexed { index, st ->
            val jpeg = st.firstJpeg ?: return@forEachIndexed
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            val src = runCatching { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) }
                .getOrNull() ?: return@forEachIndexed
            try {
                val rect = GridLayoutSpec.rect(snapshot.size, index, PREVIEW_W, PREVIEW_H)
                dest.set(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
                val targetRatio = dest.width() / dest.height()
                val srcRatio = src.width.toFloat() / src.height
                val crop = Rect()
                if (srcRatio > targetRatio) {
                    val sw = (src.height * targetRatio).toInt().coerceAtLeast(1)
                    val sx = (src.width - sw) / 2
                    crop.set(sx, 0, sx + sw, src.height)
                } else {
                    val sh = (src.width / targetRatio).toInt().coerceAtLeast(1)
                    val sy = (src.height - sh) / 2
                    crop.set(0, sy, src.width, sy + sh)
                }
                canvas.drawBitmap(src, crop, dest, paint)
            } finally {
                runCatching { src.recycle() }
            }
        }
        return try {
            val f = File(mediaSaver.cacheDir(), "preview_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 84, it) }
            f
        } finally {
            runCatching { bmp.recycle() }
        }
    }

    private fun abortStates() {
        states.values.forEach { st ->
            runCatching { st.recorder.abort() }
            mediaSaver.discardCache(st.cache)
            runCatching { st.denoiser.release() }
        }
        states.clear()
        isRunning = false
        stopping = false
    }

    fun shutdown() {
        runCatching { if (isRunning) abortStates() }
        handlerThreads.values.forEach { it.quitSafely() }
        handlerThreads.clear()
        handlers.clear()
        pending.clear()
    }
}

