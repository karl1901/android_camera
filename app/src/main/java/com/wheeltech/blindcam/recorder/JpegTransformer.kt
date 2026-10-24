package com.wheeltech.blindcam.recorder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * 把设备输出的 JPEG 帧按用户设置（水平镜像 / 旋转 / OSD 叠加）变换后重新编码，
 * 让「拍照、录像」保存的画面与屏幕预览所见完全一致（需求 66–68、43）。
 *
 * - 无镜像、旋转 0 且无 OSD 时原样返回，零开销、零重编码。
 * - 旋转 90/270 时输出宽高互换。
 * - 顺序与预览 [com.wheeltech.blindcam.render.PreviewRenderer] 一致：先旋转，再水平镜像，最后画 OSD。
 */
object JpegTransformer {

    /** 旋转 90/270 时宽高互换 */
    fun swapped(rotationDeg: Int): Boolean {
        val rot = ((rotationDeg % 360) + 360) % 360
        return rot == 90 || rot == 270
    }

    fun transform(jpeg: ByteArray, mirrorH: Boolean, rotationDeg: Int): ByteArray =
        transform(jpeg, mirrorH, rotationDeg, null)

    fun transform(
        jpeg: ByteArray,
        mirrorH: Boolean,
        rotationDeg: Int,
        overlay: OsdRenderer.Overlay?
    ): ByteArray {
        val rot = ((rotationDeg % 360) + 360) % 360
        val needGeo = mirrorH || rot != 0
        val needOsd = overlay?.any == true
        if (!needGeo && !needOsd) return jpeg

        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        var geo: Bitmap? = null
        var canvasBmp: Bitmap? = null
        try {
            // 1) 几何变换（旋转 + 镜像）
            var bmp = src
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                val r = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
                if (r != src) {
                    bmp = r
                    geo = r
                }
            }
            if (mirrorH) {
                val m = Matrix().apply { preScale(-1f, 1f) }
                val f = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (f != bmp) {
                    if (bmp != src) runCatching { bmp.recycle() }
                    bmp = f
                    geo = f
                }
            }
            // 2) OSD 需要在可变 bitmap 上绘制
            val out = if (needOsd) {
                val m = bmp.copy(Bitmap.Config.ARGB_8888, true)
                canvasBmp = m
                OsdRenderer.draw(Canvas(m), m.width, m.height, overlay!!)
                m
            } else {
                bmp
            }
            val bos = ByteArrayOutputStream(jpeg.size.coerceAtLeast(1024))
            out.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            return bos.toByteArray()
        } catch (t: Throwable) {
            return jpeg
        } finally {
            if (canvasBmp != null && canvasBmp != geo) runCatching { canvasBmp?.recycle() }
            if (geo != null) runCatching { geo?.recycle() }
            runCatching { src.recycle() }
        }
    }
}
