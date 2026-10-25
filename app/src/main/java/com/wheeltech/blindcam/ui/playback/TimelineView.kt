package com.wheeltech.blindcam.ui.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.wheeltech.blindcam.R

/**
 * 录像时间轴：一条横向轨道，有录像的时间段用主色填充、无录像段为灰色，
 * 顶部圆点/竖条标记每段起点，当前位置用高亮游标指示，可拖动/点击跳转。
 *
 * 时间范围由当天总录像文件计算（最早段开始 ~ 最晚段结束）。
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 一段录像（绝对时间毫秒） */
    data class SegmentRange(val startMs: Long, val endMs: Long)

    private var rangeMin = 0L
    private var rangeMax = 0L
    private var segments: List<SegmentRange> = emptyList()
    private var currentMs = 0L

    /** 用户拖动/点击改变时间回调 */
    var onScrub: ((Long) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val segRect = RectF()

    private val trackH = dp(10f)
    private val pad = dp(16f)

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.timeline_track)
        recPaint.color = ContextCompat.getColor(context, R.color.primary_fallback)
        recPaint.alpha = 235
        thumbPaint.color = ContextCompat.getColor(context, R.color.primary_fallback)
        dotPaint.color = ContextCompat.getColor(context, R.color.primary_fallback)
    }

    fun setData(rangeMin: Long, rangeMax: Long, segments: List<SegmentRange>, currentMs: Long) {
        this.rangeMin = rangeMin
        this.rangeMax = rangeMax
        this.segments = segments
        this.currentMs = currentMs
        invalidate()
    }

    fun setCurrent(ms: Long) {
        this.currentMs = ms
        invalidate()
    }

    private fun xOf(ms: Long): Float {
        val span = (rangeMax - rangeMin).coerceAtLeast(1L)
        val frac = (ms - rangeMin).toFloat() / span
        return pad + frac * (width - 2 * pad)
    }

    private fun msOf(x: Float): Long {
        val span = (rangeMax - rangeMin).coerceAtLeast(1L)
        val frac = ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        return rangeMin + (frac * span).toLong()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        trackRect.set(pad, cy - trackH / 2, width - pad, cy + trackH / 2)
        val radius = trackH / 2
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val span = (rangeMax - rangeMin).coerceAtLeast(1L)
        for (s in segments) {
            val x0 = xOf(s.startMs)
            val x1 = xOf(s.endMs.coerceAtLeast(s.startMs + 1))
            segRect.set(x0, cy - trackH / 2, x1.coerceAtLeast(x0 + 4f), cy + trackH / 2)
            canvas.drawRoundRect(segRect, radius, radius, recPaint)
            // 段起点圆点
            canvas.drawCircle(x0, cy, dp(5f), dotPaint)
        }

        if (rangeMax > rangeMin) {
            val cx = xOf(currentMs.coerceIn(rangeMin, rangeMax))
            canvas.drawCircle(cx, cy, dp(11f), thumbPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rangeMax <= rangeMin) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val ms = msOf(event.x)
                currentMs = ms
                invalidate()
                if (event.action == MotionEvent.ACTION_UP) onScrub?.invoke(ms)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(v: Float): Float =
        v * resources.displayMetrics.density
}
