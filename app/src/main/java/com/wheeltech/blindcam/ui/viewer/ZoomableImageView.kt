package com.wheeltech.blindcam.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 内置图片查看器用的可缩放 ImageView：
 * - 双指捏合缩放（1x–5x）
 * - 双击在 1x / 2.5x 之间切换（带缩放中心）
 * - 放大后单指拖动平移，并限制平移范围不出现黑边
 * - 单击回调（用于切换顶部栏显隐）
 *
 * 不依赖第三方库，纯原生实现，便于在车机上稳定运行。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val matrixTransform = Matrix()
    private var mode = NONE

    private var minScale = 1f
    private var maxScale = 5f
    private var saveScale = 1f

    private val last = PointF()
    private val start = PointF()
    private var origWidth = 0f
    private var origHeight = 0f
    private var origX = 0f
    private var origY = 0f
    private var oldMeasured = 0
    private var fitted = false

    var onSingleTap: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val scaleFactor = d.scaleFactor
            val newScale = saveScale * scaleFactor
            if (newScale in minScale..maxScale) {
                matrixTransform.postScale(scaleFactor, scaleFactor, d.focusX, d.focusY)
                saveScale = newScale
            }
            clamp()
            imageMatrix = matrixTransform
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!fitted) fitCenter()
            val target = if (saveScale > minScale * 1.05f) minScale else minScale * 2.5f
            val factor = target / saveScale.coerceAtLeast(0.0001f)
            matrixTransform.postScale(factor, factor, e.x, e.y)
            saveScale = target
            clamp()
            imageMatrix = matrixTransform
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val cur = PointF(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last.set(cur)
                start.set(last)
                mode = DRAG
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && saveScale > minScale * 1.01f) {
                    val dx = cur.x - last.x
                    val dy = cur.y - last.y
                    matrixTransform.postTranslate(dx, dy)
                    clamp()
                    imageMatrix = matrixTransform
                    last.set(cur)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = NONE
        }
        return true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        // 图片是在 onLayout 之后异步加载进来的：必须重置 fitted，否则 imageMatrix
        // 仍是初始单位矩阵，图片会按原始像素贴在左上角（用户反馈的“显示逻辑不对”）
        fitted = false
        requestLayout()
        post { fitCenter() }
    }

    /** 图片尺寸确定后，按 fitCenter 居中放置 */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!fitted || changed || oldMeasured != width) {
            fitCenter()
            oldMeasured = width
        }
    }

    private fun fitCenter() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0 || dh <= 0) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        val scale = min(vw / dw, vh / dh)
        matrixTransform.reset()
        matrixTransform.setScale(scale, scale)
        // 居中
        val scaledW = dw * scale
        val scaledH = dh * scale
        val tx = (vw - scaledW) / 2f
        val ty = (vh - scaledH) / 2f
        matrixTransform.postTranslate(tx, ty)
        imageMatrix = matrixTransform
        saveScale = scale
        minScale = scale
        maxScale = scale * 5f
        origWidth = scaledW
        origHeight = scaledH
        origX = tx
        origY = ty
        fitted = true
    }

    /** 限制平移/缩放，使图片始终铺满视图、不出现空白边 */
    private fun clamp() {
        val values = FloatArray(9)
        matrixTransform.getValues(values)
        var x = values[Matrix.MTRANS_X]
        var y = values[Matrix.MTRANS_Y]
        val sc = values[Matrix.MSCALE_X]
        val d = drawable ?: return
        val dw = d.intrinsicWidth * sc
        val dh = d.intrinsicHeight * sc
        val vw = width.toFloat()
        val vh = height.toFloat()

        // 宽度：比视图宽时左右夹住；比视图窄时居中
        x = if (dw <= vw) (vw - dw) / 2f else x.coerceIn(vw - dw, 0f)
        // 高度同理
        y = if (dh <= vh) (vh - dh) / 2f else y.coerceIn(vh - dh, 0f)
        matrixTransform.postTranslate(x - values[Matrix.MTRANS_X], y - values[Matrix.MTRANS_Y])
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
    }
}
