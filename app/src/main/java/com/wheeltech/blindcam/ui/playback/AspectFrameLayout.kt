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

    fun setRatio(w: Float, h: Float) {
        if (w > 0 && h > 0) {
            ratio = w / h
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0) {
            val height = (width / ratio).toInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
