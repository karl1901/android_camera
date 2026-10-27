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

    /** 当前画框是否在可见宫格中；隐藏时直接丢弃帧，省解码/降噪开销 */
    @Volatile var active: Boolean = true

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

    /** 该路当前帧率，用于按帧间隔计算降噪预算（需求：每个画框按自身分辨率/帧率独立处理） */
    @Volatile private var targetFps: Int = 30

    /** Activity 退出后禁止再触碰 Surface / 位图，避免 release 与解码线程竞态 */
    @Volatile private var released = false

    // 双缓冲输入（RGB_565：4K 时内存减半，降噪仍按 ARGB 输出）
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

    /**
     * 设置降噪档位。
     * @param fps 该画框当前帧率；预算按 1000/fps 计算（15fps 比 60fps 有更多处理时间）
     */
    fun setDenoiseLevel(level: Int, fps: Int = 30) {
        val l = level.coerceIn(0, 3)
        targetFps = fps.coerceIn(1, 60)
        denoiser?.apply {
            setBudget(budgetMsFor(targetFps))
            setLevel(l)
        }
        if (l == denoiseLevel) return
        denoiseLevel = l
        if (l == 0) {
            // 关闭：立即停止投递；不在这里回收双缓冲——降噪线程可能正在使用，
            // 回收会在 release() 时统一完成（避免高档切低档瞬间偶发 use-after-recycle）。
            pending.set(null)
        }
    }

    private fun budgetMsFor(fps: Int): Int {
        val base = 1000f / fps.coerceAtLeast(1)
        // 按启动时设备画像放宽/收紧预算：低端车机多用几毫秒换取不回落原图
        val scaled = base * com.wheeltech.blindcam.domain.DevicePerformance.denoiseBudgetScale
        return scaled.toInt().coerceIn(12, 160)
    }

    /**
     * 提交一帧（MJPEG 解码后）。
     * 降噪关闭或处于回退窗口：直接画。
     * 降噪开启：拷贝到空闲缓冲并投递，立即返回（不阻塞调用线程）。
     */
    fun submitBitmap(bmp: Bitmap) {
        if (released || !active) return
        val l = denoiseLevel
        if (l == 0 || System.currentTimeMillis() < rawFallbackUntil) {
            drawDirect(bmp)
            return
        }
        ensureBuffers(bmp.width, bmp.height, l)
        // ensureBuffers 可能因 OOM 自动关闭降噪：本帧直接画原图，保证预览不断流
        if (denoiseLevel == 0 || denoiser == null) {
            drawDirect(bmp)
            return
        }

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
        if (released) return
        val src = pending.getAndSet(null)
        if (src == null) {
            scheduled.set(false)
            return
        }
        inUse.set(src)
        try {
            val dn = denoiser
            if (dn != null && !released) {
                val out = dn.process(src)
                if (out !== src && !released) drawDirect(out)
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
            if (denoiseLevel == 0) {
                // 运行中关闭降噪：等当前帧处理完再释放降噪位图，避免 use-after-recycle
                releaseDenoiseResources()
            }
            val more = pending.get()
            if (more != null && scheduled.compareAndSet(false, true)) {
                dnHandler?.post { processTask() }
            } else {
                scheduled.set(false)
            }
        }
    }

    /** 页面不可见时暂停绘制：等待正在进行的 frame 画完，避免 Activity stop 时 Surface 被销毁的竞态 */
    fun pause(timeoutMs: Long = 300) {
        active = false
        pending.set(null)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!painting.compareAndSet(false, true)) {
            if (System.currentTimeMillis() > deadline) break
            try { Thread.sleep(1) } catch (_: InterruptedException) { break }
        }
        painting.set(false)
    }

    /** 清黑屏（设备切换 / 面板被移出时调用，避免定格残留） */
    fun clear() {
        if (released) return
        if (!textureView.isAvailable) return
        if (!painting.compareAndSet(false, true)) {
            // 降噪/绘制线程正在画：等它结束后再清一次，否则断开瞬间可能留下定格帧
            textureView.post { clear() }
            return
        }
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
        if (released) return
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

    @Synchronized
    private fun ensureBuffers(w: Int, h: Int, level: Int) {
        if (released) {
            denoiseLevel = 0
            return
        }
        if (w == bufW && h == bufH && denoiser != null) {
            denoiser?.setLevel(level)
            denoiser?.setBudget(budgetMsFor(targetFps))
            return
        }
        releaseDenoiseResources()
        try {
            srcA = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            srcB = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            bufW = w; bufH = h
            // 预算按该画框实际帧率；弱设备由 FrameDenoiser 自适应块大小
            denoiser = FrameDenoiser(level, budgetMs = budgetMsFor(targetFps))
            if (dnThread == null) {
                val t = HandlerThread("denoise-$instanceId").apply { start() }
                dnThread = t
                dnHandler = Handler(t.looper)
            }
        } catch (oom: OutOfMemoryError) {
            Logger.e(TAG, "allocate denoise buffers failed ${w}x$h, fallback raw", oom)
            releaseDenoiseResources()
            denoiseLevel = 0
        } catch (t: Throwable) {
            Logger.e(TAG, "allocate denoise buffers failed ${w}x$h, fallback raw", t)
            releaseDenoiseResources()
            denoiseLevel = 0
        }
    }

    /** 只释放降噪位图/实例；HandlerThread 在 release() 里退出 */
    @Synchronized
    private fun releaseDenoiseResources() {
        pending.set(null)
        runCatching { denoiser?.release() }
        denoiser = null
        runCatching { srcA?.recycle() }
        runCatching { srcB?.recycle() }
        srcA = null; srcB = null
        bufW = 0; bufH = 0
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        // 关键：等待正在进行的 frame 绘制结束，再 release 它正在使用的 Surface。
        // 否则 RenderThread/GLThread 仍可能持有已释放的 Surface，vivo/Android17 上
        // 会触发 "Pointer tag ... truncated" 的 native SIGABRT。
        val deadline = System.currentTimeMillis() + 200
        while (!painting.compareAndSet(false, true)) {
            if (System.currentTimeMillis() > deadline) break
            try { Thread.sleep(1) } catch (_: InterruptedException) { break }
        }
        try {
            runCatching { drawSurface?.release() }
            drawSurface = null
        } finally {
            painting.set(false)
        }
        val dn = denoiser
        val a = srcA
        val b = srcB
        val t = dnThread
        val h = dnHandler
        denoiser = null
        srcA = null
        srcB = null
        bufW = 0; bufH = 0
        pending.set(null)
        dnThread = null
        dnHandler = null
        // 等降噪线程上已排队的任务跑完再回收位图，避免 use-after-recycle
        if (h != null) {
            h.post {
                runCatching { dn?.release() }
                runCatching { a?.recycle() }
                runCatching { b?.recycle() }
                runCatching { t?.quitSafely() }
            }
        } else {
            runCatching { dn?.release() }
            runCatching { a?.recycle() }
            runCatching { b?.recycle() }
            runCatching { t?.quitSafely() }
        }
    }
}
