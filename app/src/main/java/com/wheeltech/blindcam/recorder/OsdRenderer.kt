package com.wheeltech.blindcam.recorder

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录像 / 拍照画面叠加信息（OSD）：
 * - 左上角：用户自定义名称
 * - 右上角：系统时间 yyyy-MM-dd HH:mm:ss
 * - 左下角：车速（当前无车速数据源，预留）
 *
 * 白字黑描边，保证在任意画面背景上都清晰可读；字号按画面高度等比缩放。
 */
object OsdRenderer {

    /** 单帧叠加配置（[JpegTransformer.transform] 使用） */
    data class Overlay(
        val showName: Boolean,
        val name: String?,
        val showClock: Boolean,
        val showSpeed: Boolean,
        /** 车速文本（含单位，如 "42 km/h"）；无数据源时传 null */
        val speedText: String? = null
    ) {
        val any: Boolean get() = showName || showClock || showSpeed
    }

    private val timeFmt = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.STROKE
        typeface = Typeface.DEFAULT_BOLD
    }

    fun draw(canvas: Canvas, width: Int, height: Int, o: Overlay) {
        val textSize = (height / 24f).coerceAtLeast(14f)
        val pad = height / 40f
        fillPaint.textSize = textSize
        strokePaint.textSize = textSize
        strokePaint.strokeWidth = textSize / 7f

        // 垂直基线：顶部文字
        val topY = pad + textSize
        // 底部文字基线
        val bottomY = height - pad

        if (o.showName && !o.name.isNullOrBlank()) {
            drawText(canvas, o.name, pad, topY, Paint.Align.LEFT)
        }
        if (o.showClock) {
            val t = timeFmt.get()?.format(Date()) ?: ""
            drawText(canvas, t, width - pad, topY, Paint.Align.RIGHT)
        }
        if (o.showSpeed && !o.speedText.isNullOrBlank()) {
            drawText(canvas, o.speedText, pad, bottomY, Paint.Align.LEFT)
        }
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, align: Paint.Align) {
        fillPaint.textAlign = align
        strokePaint.textAlign = align
        canvas.drawText(text, x, y, strokePaint)
        canvas.drawText(text, x, y, fillPaint)
    }
}
