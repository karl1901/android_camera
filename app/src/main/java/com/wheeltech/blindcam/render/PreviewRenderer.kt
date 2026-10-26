package com.wheeltech.blindcam.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import com.wheeltech.blindcam.imaging.FrameDenoiser
import com.wheeltech.blindcam.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 单路预览渲染器：把 MJPEG 解码后的位图绘制到 [TextureView]。
 *
 * 关键性能修复（降噪不再卡死）：
 * - 降噪开关时，[submitBitmap] 立即返回，绝不阻塞帧投递/解码线程；
 *   降噪在每路独立的 HandlerThread 上执行，latest-only（忙时只保留最新帧，不排队）。
 * - 双缓冲输入位图：被降噪线程处理或已排队的缓冲不会被覆盖，避免撕裂。
 * - 降噪最粗块仍超预算时，临时回退「直接画原图」（raw fallback），保证预览永远流动、
 *   画面永不冻结；每隔一小段时间再尝试恢复降噪。
 * - 降噪关闭时零拷贝、直接绘制（与旧行为一致）。
 */
class PreviewRenderer(
    private val textureView: TextureView,
    mirrorHInit: Boolean = false
) {
    companion object {
        private const val TAG = "preview"
        private const val RAW_FALLBACK_MS = 1200L
        private val seq = java.util.concurrent.atomic.AtomicInteger(0)
    }

    private val instanceId = seq.getAndIncrement()

    /** 水平镜像（外部可读写，状态持久化） */
    @Volatile var mirrorH: Boolean = mirrorHInit
    /** 旋转角度（0/90/180/270，外部可读写） */
    @Volatile var rotationDegrees: Int = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /** 绘制并发保护：同一时刻只允许一个线程画该 surface */
    private val painting = AtomicBoolean(false)

    @Volatile private var denoiseLevel = 0
    private var denoiser: FrameDenoiser? = null
    private var dnThread: HandlerThread? = null
    private var dnHandler: Handler? = null

    // 双缓冲输入
    private var srcA: Bitmap? = null
    private var srcB: Bitmap? = null
    private var bufW = 0
    private var bufH = 0

    /** 待处理的输入缓冲（latest-only） */
    private val pending = AtomicReference<Bitmap?>(null)
    /** 降噪线程正在处理的缓冲 */
    private val inUse = AtomicReference<Bitmap?>(null)
    private val scheduled = AtomicBoolean(false)

    /** 原图回退截止时间（毫秒） */
    @Volatile private var rawFallbackUntil = 0L

    fun setDenoiseLevel(level: Int) {
        val l = level.coerceIn(0, 3)
        if (l == denoiseLevel) return
        denoiseLevel = l
        if (l == 0) {
            // 关闭：释放降噪资源（当前帧之后直接画原图）
            pending.set(null)
            denoiser?.release()
            denoiser = null
            srcA?.recycle(); srcB?.recycle()
            srcA = null; srcB = null
            bufW = 0; bufH = 0
        } else {
            denoiser?.setLevel(l)
        }
    }

    /**
     * 提交一帧（MJPEG 解码后）。
     * 降噪关闭或处于回退窗口：直接画。
     * 降噪开启：拷贝到空闲缓冲并投递，立即返回（不阻塞调用线程）。
     */
    fun submitBitmap(bmp: Bitmap) {
        val l = denoiseLevel
        if (l == 0 || System.currentTimeMillis() < rawFallbackUntil) {
            drawDirect(bmp)
            return
        }
        ensureBuffers(bmp.width, bmp.height, l)

        // 选一个既不在处理、也不在排队的缓冲
        val using = inUse.get()
        val queued = pending.get()
        val target: Bitmap? = when {
            srcA !== using && srcA !== queued -> srcA
            srcB !== using && srcB !== queued -> srcB
            else -> null // 一个在处理、一个在排队：丢弃本帧（latest-only）
        }
        if (target == null) return
        copyInto(target, bmp)
        pending.set(target)
        if (scheduled.compareAndSet(false, true)) {
            dnHandler?.post { processTask() }
        }
    }

    /** 把当前帧像素拷贝到内部缓冲（同尺寸，纯绘制，不阻塞） */
    private fun copyInto(target: Bitmap, src: Bitmap) {
        val c = Canvas(target)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawBitmap(src, 0f, 0f, paint)
    }

    /** 从 TextureView 的 SurfaceTexture 建立绘制 Surface（懒创建，随视图生命周期复用） */
    private var drawSurface: Surface? = null
    private fun obtainSurface(): Surface? {
        val st = textureView.surfaceTexture ?: return null
        if (drawSurface == null) drawSurface = Surface(st)
        return drawSurface
    }

    private fun processTask() {
        val src = pending.getAndSet(null)
        if (src == null) {
            scheduled.set(false)
            return
        }
        inUse.set(src)
        try {
            val dn = denoiser
            if (dn != null) {
                val out = dn.process(src)
                if (out !== src) drawDirect(out)
                if (dn.overBudget) {
                    // 最粗块仍超时：进入原图回退窗口，保证预览不冻结
                    rawFallbackUntil = System.currentTimeMillis() + RAW_FALLBACK_MS
                    Logger.w(TAG, "denoise over budget, raw fallback ${RAW_FALLBACK_MS}ms " +
                            "cost=${dn.lastCostMs}ms")
                }
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "denoise task failed: ${t.message}")
        } finally {
            inUse.set(null)
            val more = pending.get()
            if (more != null && scheduled.compareAndSet(false, true)) {
                dnHandler?.post { processTask() }
            } else {
                scheduled.set(false)
            }
        }
    }

    /** 清黑屏（设备切换 / 面板被移出时调用，避免定格残留） */
    fun clear() {
        if (!textureView.isAvailable) return
        if (!painting.compareAndSet(false, true)) return
        try {
            val surface = obtainSurface() ?: return
            val canvas = surface.lockHardwareCanvas()
            try {
                canvas.drawColor(Color.BLACK)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "clear failed: ${t.message}")
        } finally {
            painting.set(false)
        }
    }

    /** 直接绘制位图（持有绘制锁，跨线程安全） */
    private fun drawDirect(bmp: Bitmap) {
        if (!textureView.isAvailable) return
        if (!painting.compareAndSet(false, true)) return
        try {
            val surface: Surface = obtainSurface() ?: return
            val canvas: Canvas = surface.lockHardwareCanvas()
            canvas.drawColor(Color.BLACK)
            val vw = textureView.width
            val vh = textureView.height
            if (vw > 0 && vh > 0) {
                // center-crop 铺满
                val br = bmp.width.toFloat() / bmp.height
                val vr = vw.toFloat() / vh
                val dw: Float; val dh: Float
                if (br > vr) {
                    dh = vh.toFloat(); dw = dh * br
                } else {
                    dw = vw.toFloat(); dh = dw / br
                }
                val left = (vw - dw) / 2f
                val top = (vh - dh) / 2f
                val save = canvas.save()
                if (rotationDegrees != 0) {
                    canvas.rotate(rotationDegrees.toFloat(), vw / 2f, vh / 2f)
                }
                if (mirrorH) {
                    canvas.scale(-1f, 1f, vw / 2f, vh / 2f)
                }
                canvas.drawBitmap(bmp, null,
                    android.graphics.RectF(left, top, left + dw, top + dh), paint)
                canvas.restoreToCount(save)
            } else {
                canvas.drawBitmap(bmp, 0f, 0f, paint)
            }
            surface.unlockCanvasAndPost(canvas)
        } catch (t: Throwable) {
            Logger.w(TAG, "draw failed: ${t.message}")
        } finally {
            painting.set(false)
        }
    }

    private fun ensureBuffers(w: Int, h: Int, level: Int) {
        if (w == bufW && h == bufH && denoiser != null) {
            denoiser?.setLevel(level)
            return
        }
        srcA?.recycle(); srcB?.recycle()
        srcA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        srcB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bufW = w; bufH = h
        denoiser?.release()
        // 预览预算：按 30fps 目标约 30ms；弱设备可由 FrameDenoiser 自适应块大小
        val dn = FrameDenoiser(level, budgetMs = 30)
        denoiser = dn
        if (dnThread == null) {
            val t = HandlerThread("denoise-$instanceId").apply { start() }
            dnThread = t
            dnHandler = Handler(t.looper)
        }
    }

    fun release() {
        try {
            dnThread?.quitSafely()
        } catch (_: Throwable) {}
        dnThread = null
        dnHandler = null
        denoiser?.release()
        denoiser = null
        srcA?.recycle(); srcB?.recycle()
        srcA = null; srcB = null
        bufW = 0; bufH = 0
        drawSurface?.release()
        drawSurface = null
    }
}
