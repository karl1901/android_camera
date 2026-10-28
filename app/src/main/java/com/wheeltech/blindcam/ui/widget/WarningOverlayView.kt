package com.wheeltech.blindcam.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * 盲区预警画面特效：
 * - 画框边缘多圈红色脉冲（模拟 box-shadow/霓虹光晕）；
 * - 四角红色 L 形角标，中心透明、四周红色渐隐（类似游戏中枪的红色边缘）；
 * - 激活瞬间有一次白色闪光，随后按 0.9s 周期呼吸；
 * - 只在预警激活时 invalidate，静止时零绘制开销。
 */
class WarningOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var active = false
    private var activatedAt = 0L

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val rect = RectF()
    private var vignette: RadialGradient? = null
    private var vignetteW = 0
    private var vignetteH = 0

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setWarning(on: Boolean) {
        if (active == on) return
        active = on
        if (on) {
            activatedAt = SystemClock.uptimeMillis()
            visibility = VISIBLE
            postInvalidateOnAnimation()
        } else {
            visibility = GONE
            invalidate()
        }
    }

    fun isWarning(): Boolean = active

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = SystemClock.uptimeMillis()
        val phase = ((now % 900L) / 900f) * (2f * Math.PI.toFloat())
        val pulse = 0.62f + 0.38f * (0.5f + 0.5f * sin(phase))
        val flash = (1f - ((now - activatedAt).coerceAtLeast(0L) / 150f)).coerceIn(0f, 1f)

        // 中心透明、四周红色渐隐：类似中枪后屏幕边缘泛红
        if (vignette == null || vignetteW != width || vignetteH != height) {
            vignette = RadialGradient(
                w / 2f, h / 2f,
                maxOf(w, h) * 0.72f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(38, 255, 20, 30),
                    Color.argb(110, 255, 10, 20)
                ),
                floatArrayOf(0f, 0.55f, 0.86f, 1f),
                Shader.TileMode.CLAMP
            )
            vignetteW = width
            vignetteH = height
        }
        vignettePaint.shader = vignette
        canvas.drawRect(0f, 0f, w, h, vignettePaint)

        // 多圈描边光晕（由外到内：宽而淡 → 细而亮）
        val inset = dp(2f)
        rect.set(inset, inset, w - inset, h - inset)
        val radius = min(w, h) * 0.020f
        val layers = arrayOf(
            14f to 0.10f,
            8f to 0.20f,
            4f to 0.55f,
            2f to 0.95f
        )
        for ((widthDp, alpha) in layers) {
            borderPaint.strokeWidth = dp(widthDp)
            borderPaint.color = Color.argb(
                (alpha * 255f * pulse).toInt().coerceIn(0, 255),
                255, 28, 42
            )
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
        }

        // 内部一圈几乎贴边的亮红细线，强化“画框边缘”
        borderPaint.strokeWidth = dp(1f)
        borderPaint.color = Color.argb((180 * pulse).toInt(), 255, 80, 90)
        canvas.drawRoundRect(
            RectF(inset + dp(3f), inset + dp(3f), w - inset - dp(3f), h - inset - dp(3f)),
            radius, radius, borderPaint
        )

        // 四角 L 形角标，长度随脉冲轻微伸缩
        val len = dp(18f) + dp(4f) * pulse
        val pad = dp(7f)
        cornerPaint.strokeWidth = dp(3f)
        cornerPaint.color = Color.argb((235 * pulse).toInt(), 255, 52, 64)
        drawCorners(canvas, w, h, pad, len)

        // 激活瞬间白色闪光
        if (flash > 0f) {
            flashPaint.color = Color.argb((flash * 70f).toInt(), 255, 255, 255)
            canvas.drawRect(0f, 0f, w, h, flashPaint)
        }

        postInvalidateOnAnimation()
    }

    private fun drawCorners(canvas: Canvas, w: Float, h: Float, pad: Float, len: Float) {
        // 左上
        canvas.drawLine(pad, pad, pad + len, pad, cornerPaint)
        canvas.drawLine(pad, pad, pad, pad + len, cornerPaint)
        // 右上
        canvas.drawLine(w - pad, pad, w - pad - len, pad, cornerPaint)
        canvas.drawLine(w - pad, pad, w - pad, pad + len, cornerPaint)
        // 左下
        canvas.drawLine(pad, h - pad, pad + len, h - pad, cornerPaint)
        canvas.drawLine(pad, h - pad, pad, h - pad - len, cornerPaint)
        // 右下
        canvas.drawLine(w - pad, h - pad, w - pad - len, h - pad, cornerPaint)
        canvas.drawLine(w - pad, h - pad, w - pad, h - pad - len, cornerPaint)
    }
}
