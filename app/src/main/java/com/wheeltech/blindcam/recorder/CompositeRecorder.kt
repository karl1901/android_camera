package com.wheeltech.blindcam.recorder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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

/**
 * 总录像：把多路画面按当前宫格布局合成到一张 [OUT_W]x[OUT_H] 画布，
 * 再通过 [Mp4Recorder] 硬件编码为单个 MP4（H.264），回放时一屏看到所有画面。
 *
 * - 每格用当前帧（含自定义名/系统时间 OSD），按 UI 一致的 1/2/3/4 宫格布局绘制。
 * - 合成节拍按设备性能 12–18fps（[intervalMs]），帧时间戳取真实节拍，
 *   回放时长=真实时长。
 * - 合成、编码都在独立 HandlerThread 串行完成；native 回调线程只拷贝最新帧。
 */
class CompositeRecorder(private val mediaSaver: MediaSaver) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "composite"
        private const val OUT_W = 1280
        private const val OUT_H = 720
        private const val DEFAULT_BITRATE = 7_000_000 // 720p 总录像默认高画质（可被设置覆盖）
    }

    /** 按启动时设备画像决定合成帧率（低端 12fps / 高端 18fps） */
    private val fpsHint: Int =
        com.wheeltech.blindcam.domain.DevicePerformance.compositeFps.coerceIn(10, 30)
    private val intervalMs: Long = (1000L / fpsHint).coerceAtLeast(30L)

    /** 一路参与合成的画面：slot 序号、展示名及几何设置；jpeg 为合成时填充的最新帧；
     *  denoiseLevel 为该路独立降噪档位（0关/1弱/2中/3强），与预览/单路录像一致 */
    class Cell(
        val slot: Int,
        val name: String,
        val mirror: Boolean,
        val rotation: Int,
        val denoiseLevel: Int = 0,
        @Volatile var jpeg: ByteArray? = null
    )

    private var cells: List<Cell> = emptyList()
    private val latest = ConcurrentHashMap<Int, ByteArray>()
    /** 每路解码采样率缓存：录制期间分辨率不变，首帧算好后复用，省去每帧 bounds 解码 */
    private val sampleCache = ConcurrentHashMap<Int, Int>()
    /** 每格独立降噪器（合成线程内调用，与预览/单路相互独立，按各画框分辨率处理） */
    private val denoisers = HashMap<Int, FrameDenoiser>()

    private val thread = HandlerThread("capture-composite").apply { start() }
    private val handler = Handler(thread.looper)

    // ---- 当前分段（一个分段 = 一个 Mp4Recorder + 一个缓存文件） ----
    private var recorder: Mp4Recorder? = null
    private var cacheFile: java.io.File? = null
    private var videoDirUri: String = ""
    private var displayName: String = ""
    private var segmentStartMs = 0L
    private var segmentMs: Long = 0L
    private var targetBitrate = DEFAULT_BITRATE

    // ---- 循环录像配置 ----
    private var loopEnabled = false
    private var keepFreeMb = 1024L

    @Volatile var isRunning = false
        private set

    /** 正在收尾上一段（finish/落盘/删旧段）：此期间禁止立即重新开始，避免状态竞态 */
    @Volatile private var stopping = false

    val isStopping: Boolean get() = stopping

    private var osdName = true
    private var osdClock = true
    private var frameCount = 0

    // 常驻可复用资源，避免每帧分配
    private val reusableCanvas = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(reusableCanvas)
    private val blackPaint = Paint().apply { color = Color.BLACK }
    private val destRect = RectF()

    fun start(
        cells: List<Cell>,
        osdName: Boolean,
        osdClock: Boolean,
        videoDirUri: String,
        bitrate: Int = DEFAULT_BITRATE,
        loopEnabled: Boolean = false,
        segmentMinutes: Int = 3,
        keepFreeMb: Int = 1024
    ): Boolean {
        if (isRunning || stopping) return false
        if (cells.isEmpty()) return false
        return try {
            this.cells = cells
            this.osdName = osdName
            this.osdClock = osdClock
            this.videoDirUri = videoDirUri
            this.targetBitrate = if (bitrate > 0) bitrate else DEFAULT_BITRATE
            this.loopEnabled = loopEnabled
            this.segmentMs = segmentMinutes.coerceIn(1, 60) * 60_000L
            this.keepFreeMb = keepFreeMb.toLong()
            latest.clear()
            sampleCache.clear()
            denoisers.values.forEach { runCatching { it.release() } }
            denoisers.clear()
            cells.forEach { c ->
                if (c.denoiseLevel > 0)
                    denoisers[c.slot] = FrameDenoiser(c.denoiseLevel, budgetMs = (1000 / fpsHint))
            }
            frameCount = 0
            // 开录前先确保空间充足（循环模式删最旧）。目录扫描放到合成线程，
            // 避免几百个分段时在 UI 线程做文件扫描造成点“总录像”卡顿。
            if (this.loopEnabled) handler.post { enforceFreeSpace() }
            openSegment()
            isRunning = true
            handler.postDelayed(compositeTask, intervalMs)
            Logger.i(
                TAG, "总录像开始 cells=${cells.size} bitrate=${this.targetBitrate / 1000}kbps " +
                    "loop=$loopEnabled seg=${segmentMinutes}min keepFree=${keepFreeMb}MB -> $displayName"
            )
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "composite start failed", t)
            false
        }
    }

    /** 开新分段：新缓存文件 + 新编码器；分段时间戳用于唯一命名。 */
    private fun openSegment() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        displayName = "ALL_${ts}_${OUT_W}x${OUT_H}.mp4"
        cacheFile = mediaSaver.newCacheFile("mp4")
        recorder = Mp4Recorder(cacheFile!!, OUT_W, OUT_H, targetBitrate, fpsHint)
        segmentStartMs = SystemClock.elapsedRealtime()
    }

    /**
     * 收尾当前分段并发布到目标目录（在合成线程执行）。
     * 发布后在循环模式下按保留空间删除最旧分段。
     * @return 发布结果文件名；无帧返回 null。
     */
    private fun finishAndPublishSegment(): String? {
        val rec = recorder
        val cache = cacheFile
        var result: String? = null
        try {
            val out = rec?.finish()
            if (out != null && cache != null) {
                result = mediaSaver.publishVideo(cache, displayName, videoDirUri)
                // 记录本段参与合成的画面名称与路数：回看时按录制时的画面切换，
                // 即使摄像头已拔掉/顺序变化也能显示正确的自定义名称与裁剪区域。
                mediaSaver.publishCompositeMeta(
                    displayName, cells.map { it.name }, OUT_W, OUT_H, videoDirUri
                )
                Logger.i(TAG, "分段已保存 frames=$frameCount -> $result")
            }
        } catch (t: Throwable) {
            runCatching { rec?.abort() }
            cache?.let { mediaSaver.discardCache(it) }
            Logger.w(TAG, "分段保存失败: ${t.message}")
        } finally {
            recorder = null
            cacheFile = null
        }
        if (loopEnabled && result != null) enforceFreeSpace()
        return result
    }

    /** 循环覆盖：可用空间低于阈值时，从最旧的总录像段开始删除，直到达标或无段可删。 */
    private fun enforceFreeSpace() {
        var guard = 0
        while (mediaSaver.videoFreeSpaceMb(videoDirUri) < keepFreeMb && guard < 200) {
            val oldest = mediaSaver.compositeSegments(videoDirUri).firstOrNull() ?: break
            val name = oldest.name
            // 绝不能删正在录制的当前段（尚未发布，列表里本就没有，双保险）
            if (name == displayName) break
            val ok = mediaSaver.deleteVideoByName(videoDirUri, name)
            Logger.i(TAG, "循环覆盖：删除最旧总录像段 $name ok=$ok")
            if (!ok) break
            guard++
        }
    }

    fun stop(cb: (Result<String>) -> Unit) {
        if (!isRunning) {
            cb(Result.failure(IllegalStateException("总录像未在运行")))
            return
        }
        isRunning = false
        stopping = true
        handler.removeCallbacks(compositeTask)
        handler.post {
            try {
                // 收尾最后一个分段（循环模式下每个分段已在录制中陆续发布）
                val result = finishAndPublishSegment()
                if (result != null) {
                    cb(Result.success(result))
                } else {
                    cb(Result.failure(IllegalStateException("未录制到任何帧")))
                }
            } finally {
                stopping = false
            }
        }
    }

    /** 帧到达：只保留每路最新帧（拷贝），合成在独立线程 */
    override fun onFrame(frame: CameraFrame) {
        if (!isRunning) return
        if (cells.none { it.slot == frame.cameraId }) return
        latest[frame.cameraId] = frame.data.copyOf(frame.data.size)
    }

    /** CaptureManager 直接调用：等价转发（带 slot） */
    fun onFrame(slot: Int, data: ByteArray) {
        if (!isRunning) return
        if (cells.none { it.slot == slot }) return
        latest[slot] = data.copyOf(data.size)
    }

    private val compositeTask = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                compose()
            } catch (t: Throwable) {
                Logger.w(TAG, "compose failed: ${t.message}")
            }
            handler.postDelayed(this, intervalMs)
        }
    }

    private fun compose() {
        // 黑底
        canvas.drawRect(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(), blackPaint)
        val n = cells.size
        for ((index, cell) in cells.withIndex()) {
            val rect = cellRect(index, n)
            destRect.set(
                rect.left.toFloat(), rect.top.toFloat(),
                rect.right.toFloat(), rect.bottom.toFloat()
            )
            val jpeg = latest[cell.slot] ?: cell.jpeg
            cell.jpeg = jpeg
            if (jpeg != null) {
                drawCell(jpeg, destRect, cell, n)
            }
        }
        // 全局：右上角统一时钟（格内已绘制名称；车速已移除）
        if (osdClock) {
            OsdRenderer.draw(
                canvas, OUT_W, OUT_H,
                OsdRenderer.Overlay(
                    showName = false, name = null, showClock = true
                )
            )
        }
        recorder?.frame(reusableCanvas, SystemClock.elapsedRealtimeNanos())
        frameCount++

        // 循环录像：到达分段时长，收尾并发布当前段、开新段（发布期间极少数帧跳过，无丢帧累积）
        if (loopEnabled && SystemClock.elapsedRealtime() - segmentStartMs >= segmentMs) {
            finishAndPublishSegment()
            openSegment()
            frameCount = 0
        }
    }

    /** 解码一帧并按目标格区域 center-crop 绘制，含几何与 OSD */
    private fun drawCell(jpeg: ByteArray, dest: RectF, cell: Cell, n: Int) {
        val bmp = decodeSampled(cell.slot, jpeg, dest.width().toInt(), dest.height().toInt(), n) ?: return
        var draw = bmp
        var recycled = false
        // 实际绘制位图：开启该路降噪时为降噪器内部复用位图（不能回收）
        var render = bmp
        var denoiserOwns = false
        try {
            // 几何（旋转/镜像）
            if (cell.rotation != 0 || cell.mirror) {
                val m = Matrix()
                if (cell.rotation != 0) m.postRotate(cell.rotation.toFloat())
                if (cell.mirror) m.preScale(-1f, 1f)
                val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (t != bmp) {
                    bmp.recycle(); recycled = true; draw = t
                }
            }
            // 该路独立实时降噪（按格尺寸；总录像每格较小，开销很低）
            val dn = denoisers[cell.slot]
            if (dn != null) {
                val out = dn.process(draw)
                if (out !== draw) {
                    render = out
                    denoiserOwns = true
                }
            } else {
                render = draw
            }
            // center-crop：源按目标宽高比居中裁剪
            val targetRatio = dest.width() / dest.height()
            val srcRatio = render.width.toFloat() / render.height
            var sx = 0f; var sy = 0f; var sw = render.width.toFloat(); var sh = render.height.toFloat()
            if (srcRatio > targetRatio) {
                sw = render.height * targetRatio
                sx = (render.width - sw) / 2f
            } else {
                sh = render.width / targetRatio
                sy = (render.height - sh) / 2f
            }
            val src = android.graphics.Rect(
                sx.toInt().coerceAtLeast(0),
                sy.toInt().coerceAtLeast(0),
                (sx + sw).toInt().coerceAtMost(render.width),
                (sy + sh).toInt().coerceAtMost(render.height)
            )
            canvas.drawBitmap(render, src, dest, null)

            // 格内 OSD：左上角名称（时间统一在全局右上角）
            if (osdName) {
                val overlay = OsdRenderer.Overlay(
                    showName = true,
                    name = cell.name,
                    showClock = false
                )
                // 用裁剪区域建立临时画布坐标，OsdRenderer 在该区域内绘制
                val sx2 = dest.left
                val sy2 = dest.top
                val cw = dest.width().toInt()
                val ch = dest.height().toInt()
                canvas.save()
                canvas.translate(sx2, sy2)
                OsdRenderer.draw(canvas, cw, ch, overlay)
                canvas.restore()
            }
        } finally {
            // 降噪内部位图由降噪器复用，不能回收；只回收降噪前的临时位图
            if (denoiserOwns) {
                runCatching { draw.recycle() }
            } else if (!recycled) {
                runCatching { bmp.recycle() }
            } else {
                runCatching { draw.recycle() }
            }
        }
    }

    /**
     * 按目标格尺寸选合适的 inSampleSize 解码，降低 CPU / 内存开销。
     * - 单路（n=1）：全分辨率解码（仅一路，负担小，保画质），采样只向下取整到不小于目标。
     * - 多路（n>=2）：分屏每格较小，选「解码尺寸最接近目标格」的采样（允许小幅放大），
     *   显著减少多路并发解码量，保证合成帧率流畅；720p 总录像下分屏清晰度损失可接受。
     * - 使用 RGB_565（每像素 2 字节）降低内存与带宽；采样率按 slot 缓存。
     */
    private fun decodeSampled(slot: Int, jpeg: ByteArray, reqW: Int, reqH: Int, n: Int): Bitmap? {
        val cached = sampleCache[slot]
        val sample = if (cached != null) cached else {
            val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bo)
            val s = if (bo.outWidth <= 0 || bo.outHeight <= 0) 1 else computeSample(bo.outWidth, bo.outHeight, reqW, reqH, n)
            sampleCache[slot] = s
            s
        }
        val o = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
    }

    private fun computeSample(ow: Int, oh: Int, reqW: Int, reqH: Int, n: Int): Int {
        if (n <= 1) {
            // 单路：保证解码尺寸不小于目标（Google 官方 floor 策略）
            var s = 1; var w = ow; var h = oh
            while (w / 2 >= reqW && h / 2 >= reqH) { w /= 2; h /= 2; s *= 2 }
            return s
        }
        // 多路：在 1/2/4/... 中选解码尺寸与目标格偏差最小的（允许小幅放大）
        var s = 1; var best = 1; var bestScore = Float.MAX_VALUE
        while (s <= 16) {
            val dw = ow / s; val dh = oh / s
            if (dw < reqW / 2 || dh < reqH / 2) break // 解码尺寸不足目标一半，过于模糊
            val score = maxOf(
                dw.toFloat() / reqW, dh.toFloat() / reqH,
                reqW.toFloat() / dw, reqH.toFloat() / dh
            )
            if (score < bestScore) { bestScore = score; best = s }
            s *= 2
        }
        return best
    }

    /** 与预览/回看一致的布局矩形（统一由 [GridLayoutSpec] 提供） */
    private fun cellRect(index: Int, n: Int): android.graphics.Rect {
        val r = com.wheeltech.blindcam.domain.GridLayoutSpec.rect(n, index, OUT_W, OUT_H)
        return android.graphics.Rect(r.left, r.top, r.right, r.bottom)
    }

    fun shutdown() {
        isRunning = false
        handler.removeCallbacks(compositeTask)
        runCatching {
            recorder?.abort()
            recorder = null
        }
        thread.quitSafely()
        latest.clear()
        denoisers.values.forEach { runCatching { it.release() } }
        denoisers.clear()
        runCatching { reusableCanvas.recycle() }
    }
}

