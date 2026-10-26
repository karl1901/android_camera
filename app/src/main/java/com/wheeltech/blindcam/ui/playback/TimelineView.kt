package com.wheeltech.blindcam.ui.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 时间轴控件：
 * - 有录像段用亮色（霓虹）填充，无录像段用暗色，反映当天可拖动范围
 * - 拇指位置（当前播放点）
 *
 * 拖动交互（修复"不响应/不丝滑"）：
 * - DOWN：立即开始拖动并定位（onStart）
 * - MOVE：连续回调（onMove），由 Activity 节流 seek
 * - UP：结束（onEnd）
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 拖动阶段 */
    enum class Phase { START, MOVE, END }

    /** 有录像的时间段（全局毫秒，相对当天 0 点） */
    data class Segment(val startMs: Long, val endMs: Long)

    private var segments: List<Segment> = emptyList()
    private var rangeStartMs = 0L
    private var rangeEndMs = 86_400_000L
    private var currentMs = 0L

    /** 拖动回调：fraction 已换算为全局 ms；phase 表示阶段 */
    var onScrub: ((globalMs: Long, phase: Phase) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2E38.toInt() }
    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF6FB5.toInt()
        setShadowLayer(14f, 0f, 0f, 0xCCFF5FA8.toInt())
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(12f, 0f, 0f, 0xCC8B5CF6.toInt())
    }

    fun setSegments(segs: List<Segment>, startMs: Long, endMs: Long) {
        segments = segs.sortedBy { it.startMs }
        rangeStartMs = startMs
        rangeEndMs = if (endMs <= startMs) startMs + 1 else endMs
        invalidate()
    }

    fun setCurrent(ms: Long) {
        currentMs = ms
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 8f
        val cy = height / 2f
        val trackH = 8f
        val track = RectF(pad, cy - trackH / 2, width - pad, cy + trackH / 2)
        // 底轨
        canvas.drawRoundRect(track, trackH, trackH, trackPaint)
        // 有录像段
        val span = rangeEndMs - rangeStartMs
        for (s in segments) {
            val l = pad + (s.startMs - rangeStartMs).toFloat() / span * (width - 2 * pad)
            val r = pad + (s.endMs - rangeStartMs).toFloat() / span * (width - 2 * pad)
            if (r > pad && l < width - pad) {
                canvas.drawRoundRect(
                    RectF(l.coerceAtLeast(pad), cy - trackH / 2,
                        r.coerceAtMost(width - pad), cy + trackH / 2),
                    trackH, trackH, recPaint
                )
            }
        }
        // 拇指
        val tx = pad + (currentMs - rangeStartMs).toFloat() / span * (width - 2 * pad)
        canvas.drawCircle(tx.coerceIn(pad, width - pad), cy, 11f, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pad = 8f
        val usable = (width - 2 * pad).coerceAtLeast(1f)
        val frac = ((event.x - pad) / usable).coerceIn(0f, 1f)
        val global = rangeStartMs + (frac * (rangeEndMs - rangeStartMs)).toLong()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                currentMs = global
                invalidate()
                onScrub?.invoke(global, Phase.START)
            }
            MotionEvent.ACTION_MOVE -> {
                currentMs = global
                invalidate()
                onScrub?.invoke(global, Phase.MOVE)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentMs = global
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(false)
                onScrub?.invoke(global, Phase.END)
            }
        }
        return true
    }
}
