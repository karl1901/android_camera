package com.wheeltech.blindcam.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import com.wheeltech.blindcam.imaging.FrameDenoiser
import com.wheeltech.blindcam.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 渲染模块：把 MJPEG 解码后的 Bitmap 绘制到 [TextureView] 的 Surface。
 *
 * - 支持水平镜像（mirrorH，盲区摄像头必备）、垂直翻转（flipV）
 * - TextureView 内置 transform Matrix 也可以做镜像，但为了给后续
 *   OpenGL 畸变矫正 / 全景拼接预留通道，这里统一走 Canvas 绘制矩阵，
 *   后期替换为 GLSurfaceView 时只改本类内部实现
 * - 内置简单丢帧保护：上一帧还没绘制完，直接丢弃新帧
 *
 * 注意：本类为第一阶段 Canvas 实现，OpenGL 通道在扩展阶段替换。
 */
class PreviewRenderer(
    private val textureView: TextureView
) {
    companion object {
        private const val TAG = "render"
    }

    @Volatile var mirrorH: Boolean = false
    @Volatile var flipV: Boolean = false
    @Volatile var rotationDegrees: Int = 0

    /** 本路独立的多帧时域降噪器（按本画框分辨率/帧率自适应），仅在本路渲染线程使用 */
    private val denoiser = FrameDenoiser(0)

    /** 本路降噪档位（0关/1弱/2中/3强），由设置持久化并随槽位绑定 */
    var denoiseLevel: Int = 0
        set(v) {
            field = v
            denoiser.setLevel(v)
        }

    private val painting = AtomicBoolean(false)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    private var surface: Surface? = null

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                Logger.i(TAG, "surface available ${w}x${h}")
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    /**
     * 提交一帧；native 线程调用。
     * 必须在回调线程完成解码（耗时工作），这里只做绘制。
     */
    fun submitBitmap(bitmap: Bitmap) {
        val currentSurface = surface
        if (currentSurface == null || textureView.surfaceTexture == null) return
        // 丢帧保护：上一帧绘制未完成，跳过本帧
        if (!painting.compareAndSet(false, true)) return

        var canvas: Canvas? = null
        try {
            // 本路实时降噪（多帧时域，按本画框分辨率/帧率自适应）；关闭时返回原图
            val src = if (denoiser.enabled) denoiser.process(bitmap) else bitmap
            // 优先硬件 Canvas（API23+），车机系统较老时回退软件 Canvas
            canvas = if (android.os.Build.VERSION.SDK_INT >= 23) {
                currentSurface.lockHardwareCanvas()
            } else {
                currentSurface.lockCanvas(null)
            }
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)

            matrix.reset()
            val vw = textureView.width.toFloat()
            val vh = textureView.height.toFloat()
            if (vw == 0f || vh == 0f) return

            // 等比缩放（centerCrop）
            val scale = maxOf(vw / src.width, vh / src.height)
            val dx = (vw - src.width * scale) / 2f
            val dy = (vh - src.height * scale) / 2f
            matrix.postScale(scale, scale)
            matrix.postTranslate(dx, dy)

            // 镜像 / 翻转 / 旋转（围绕视图中心）
            matrix.postRotate(
                rotationDegrees.toFloat(),
                vw / 2f, vh / 2f
            )
            if (mirrorH) matrix.postScale(-1f, 1f, vw / 2f, vh / 2f)
            if (flipV) matrix.postScale(1f, -1f, vw / 2f, vh / 2f)

            canvas.drawBitmap(src, matrix, paint)
        } catch (t: Throwable) {
            Logger.w(TAG, "draw frame failed: ${t.message}")
        } finally {
            if (canvas != null) {
                runCatching { currentSurface.unlockCanvasAndPost(canvas) }
            }
            painting.set(false)
        }
    }

    /** 清空画面（设备拔出 / 槽位置空 / 切换设备时调用，避免定格在最后一帧） */
    fun clear() {
        val currentSurface = surface ?: return
        if (textureView.surfaceTexture == null) return
        if (!painting.compareAndSet(false, true)) return
        var canvas: Canvas? = null
        try {
            canvas = if (android.os.Build.VERSION.SDK_INT >= 23) {
                currentSurface.lockHardwareCanvas()
            } else {
                currentSurface.lockCanvas(null)
            }
            canvas.drawColor(android.graphics.Color.BLACK)
        } catch (t: Throwable) {
            Logger.w(TAG, "clear failed: ${t.message}")
        } finally {
            if (canvas != null) runCatching { currentSurface.unlockCanvasAndPost(canvas) }
            painting.set(false)
        }
    }

    fun release() {
        surface = null
        denoiser.release()
    }
}
