package com.wheeltech.blindcam.recorder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.wheeltech.blindcam.util.Logger
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * 总录像（大录像按钮）：把所有画面按当前 1/2/3/4 宫格布局合成到一个视频里。
 *
 * - 输出固定 1280x720 横屏 AVI（MJPEG），降低多路合成开销，车机也跑得动。
 * - 固定节拍合成（[FPS]），每拍取各路最新帧；停止时按真实节拍回填帧率（同单路）。
 * - 每路先做与预览一致的镜像/旋转，再 centerCrop 铺进对应格子；每格左上画名称，
 *   全局右上角画系统时间（由 OSD 开关控制）。
 * - native 回调线程只拷贝字节，decode/合成/编码全在独立串行线程，不阻塞取流。
 */
class CompositeRecorder(private val mediaSaver: MediaSaver) {

    data class Cell(
        val slot: Int,
        val name: String,
        val mirror: Boolean,
        val rotation: Int
    )

    companion object {
        private const val TAG = "composite"
        private const val OUT_W = 1280
        private const val OUT_H = 720
        private const val FPS = 15
        private const val INTERVAL_MS = 1000L / FPS
        private const val VIDEO_FMT = "yyyyMMdd_HHmmss"
    }

    private val thread = HandlerThread("capture-composite").apply { start() }
    private val handler = Handler(thread.looper)

    private val cells = ArrayList<Cell>()
    private val latest = ConcurrentHashMap<Int, ByteArray>()

    private var writer: AviWriter? = null
    private var cache: java.io.File? = null
    private var dirUri = ""
    private var displayName = ""
    private var osdName = false
    private var osdClock = false
    private var osdSpeed = false

    @Volatile
    private var running = false
    private var frames = 0
    private var firstMs = 0L
    private var lastMs = 0L

    private val reusableCanvas = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)

    private val tick = Runnable { compose() }

    val isRunning: Boolean get() = running

    fun start(cells: List<Cell>, osdName: Boolean, osdClock: Boolean, osdSpeed: Boolean, dirUri: String): Boolean {
        if (running) return false
        if (cells.isEmpty()) return false
        return try {
            this.cells.clear()
            this.cells.addAll(cells)
            latest.clear()
            this.osdName = osdName
            this.osdClock = osdClock
            this.osdSpeed = osdSpeed
            cache = mediaSaver.newCacheFile("avi")
            writer = AviWriter(cache!!, OUT_W, OUT_H, FPS)
            displayName = "BLIND_ALL_${
                java.text.SimpleDateFormat(VIDEO_FMT, java.util.Locale.US).format(Date())
            }_${OUT_W}x${OUT_H}.avi"
            this.dirUri = dirUri
            frames = 0
            firstMs = 0L
            lastMs = 0L
            running = true
            handler.post(tick)
            Logger.i(TAG, "composite recording start cells=${cells.map { it.slot }}")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "composite start failed", t)
            running = false
            false
        }
    }

    /** native 帧回调线程调用：仅在录制中保留该路最新帧（拷贝） */
    fun onFrame(slot: Int, jpeg: ByteArray) {
        if (running && cells.any { it.slot == slot }) {
            latest[slot] = jpeg.copyOf(jpeg.size)
        }
    }

    private fun compose() {
        if (!running) return
        try {
            val c = Canvas(reusableCanvas)
            c.drawColor(Color.BLACK)
            val n = cells.size
            for (index in 0 until n) {
                val cell = cells[index]
                val jpeg = latest[cell.slot] ?: continue
                val rect = cellRect(n, index)
                var src: Bitmap? = null
                var geo: Bitmap? = null
                try {
                    src = decodeSampled(jpeg, rect.width().toInt(), rect.height().toInt()) ?: continue
                    geo = applyGeo(src, cell.mirror, cell.rotation)
                    drawCenterCrop(c, geo, rect)
                    // 每格左上画名称（在格子局部坐标系）
                    c.save()
                    c.translate(rect.left, rect.top)
                    c.clipRect(0f, 0f, rect.width(), rect.height())
                    if (osdName) {
                        OsdRenderer.draw(
                            c, rect.width().toInt(), rect.height().toInt(),
                            OsdRenderer.Overlay(
                                showName = true, name = cell.name,
                                showClock = false, showSpeed = false
                            )
                        )
                    }
                    c.restore()
                } catch (t: Throwable) {
                    Logger.w(TAG, "compose cell ${cell.slot} failed: ${t.message}")
                } finally {
                    if (geo != null && geo != src) runCatching { geo.recycle() }
                    runCatching { src?.recycle() }
                }
            }
            // 全局右上角系统时间
            if (osdClock) {
                OsdRenderer.draw(
                    c, OUT_W, OUT_H,
                    OsdRenderer.Overlay(
                        showName = false, name = null,
                        showClock = true, showSpeed = false
                    )
                )
            }
            val bos = ByteArrayOutputStream(64 * 1024)
            reusableCanvas.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            writer?.addFrame(bos.toByteArray())
            val now = SystemClock.elapsedRealtime()
            if (frames == 0) firstMs = now
            lastMs = now
            frames++
        } catch (t: Throwable) {
            Logger.w(TAG, "compose frame failed: ${t.message}")
        } finally {
            if (running) handler.postDelayed(tick, INTERVAL_MS)
        }
    }

    fun stop(cb: (Result<String>) -> Unit) {
        if (!running) {
            cb(Result.failure(IllegalStateException("未在总录像")))
            return
        }
        running = false
        handler.removeCallbacks(tick)
        handler.post {
            try {
                val n = frames
                val durMs = lastMs - firstMs
                val realFps = if (n >= 2 && durMs > 0) {
                    kotlin.math.round(((n - 1) * 1000.0) / durMs).toInt().coerceIn(1, FPS)
                } else {
                    null
                }
                writer?.finish(realFps)
                val f = cache
                val result = if (f != null) {
                    mediaSaver.publishVideo(f, displayName, dirUri)
                } else {
                    throw IllegalStateException("录像文件不存在")
                }
                Logger.i(TAG, "composite saved frames=$n realFps=$realFps -> $result")
                latest.clear()
                cb(Result.success(result))
            } catch (t: Throwable) {
                cache?.let { mediaSaver.discardCache(it) }
                Logger.e(TAG, "composite publish failed", t)
                cb(Result.failure(t))
            }
        }
    }

    fun shutdown() {
        running = false
        handler.removeCallbacks(tick)
        runCatching {
            writer?.abort()
            reusableCanvas.recycle()
        }
        thread.quitSafely()
    }

    // ------------------------------------------------------------------
    // 布局（与 MainActivity.cellSpec 一致：2 列 × 2 行网格）
    // ------------------------------------------------------------------
    private fun cellRect(n: Int, index: Int): RectF {
        val unitW = OUT_W / 2f
        val unitH = OUT_H / 2f
        val (row, col, rowSpan, colSpan) = when (n) {
            1 -> intArrayOf(0, 0, 2, 2)
            2 -> intArrayOf(0, index, 2, 1)
            3 -> when (index) {
                0 -> intArrayOf(1, 0, 1, 1)
                1 -> intArrayOf(1, 1, 1, 1)
                else -> intArrayOf(0, 0, 1, 2)
            }
            else -> intArrayOf(index / 2, index % 2, 1, 1)
        }
        return RectF(
            col * unitW,
            row * unitH,
            (col + colSpan) * unitW,
            (row + rowSpan) * unitH
        )
    }

    private fun decodeSampled(jpeg: ByteArray, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        var w = bounds.outWidth
        var h = bounds.outHeight
        while (w / 2 >= targetW.coerceAtLeast(1) && h / 2 >= targetH.coerceAtLeast(1)) {
            w /= 2
            h /= 2
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private fun applyGeo(src: Bitmap, mirror: Boolean, rotation: Int): Bitmap {
        val rot = ((rotation % 360) + 360) % 360
        var bmp = src
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (r != src) bmp = r
        }
        if (mirror) {
            val m = Matrix().apply { preScale(-1f, 1f) }
            val f = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (f != bmp) {
                if (bmp != src) runCatching { bmp.recycle() }
                bmp = f
            }
        }
        return bmp
    }

    private fun drawCenterCrop(c: Canvas, bmp: Bitmap, t: RectF) {
        val sw = bmp.width.toFloat()
        val sh = bmp.height.toFloat()
        val scale = maxOf(t.width() / sw, t.height() / sh)
        val dw = sw * scale
        val dh = sh * scale
        val dx = t.left + (t.width() - dw) / 2f
        val dy = t.top + (t.height() - dh) / 2f
        c.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), null)
    }
}
