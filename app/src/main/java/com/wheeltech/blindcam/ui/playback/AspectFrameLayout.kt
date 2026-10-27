package com.wheeltech.blindcam.ui.playback

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 固定宽高比的容器（默认 16:9，匹配总录像合成分辨率），
 * 保证回看预览区与视频比例一致，单路/全部切换裁剪不形变。
 */
class AspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    /** 宽:高 */
    private var ratio = 16f / 9f

    /**
     * 预览区最多占屏幕高度的比例。
     * 横屏手机上 16:9 全宽会超过屏幕高度，导致下方时间轴/播放控制被挤出屏幕；
     * 这里按比例封顶，内部用 fitCenter 矩阵保持画面不变形（上下/左右留黑）。
     */
    private var maxHeightFraction = 0.62f

    fun setRatio(w: Float, h: Float) {
        if (w > 0 && h > 0) {
            ratio = w / h
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0) {
            // 注意：横屏时 resources.displayMetrics.heightPixels 在部分 ROM 上仍是
            // 物理竖屏高度（2800），会把 16:9 预览算到 1575px 高，把播放控件挤出屏幕。
            // 用 configuration.screenHeightDp 才是当前方向的可用高度。
            val cfg = resources.configuration
            val screenH = (cfg.screenHeightDp * resources.displayMetrics.density).toInt()
            val maxH = (screenH * maxHeightFraction).toInt()
            val height = minOf((width / ratio).toInt(), maxH).coerceAtLeast(1)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
