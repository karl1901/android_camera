package com.wheeltech.blindcam.recorder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 把设备输出的 JPEG 帧按用户设置（水平镜像 / 旋转 / OSD 叠加）变换后输出，
 * 让「拍照、录像」保存的画面与屏幕预览所见完全一致（需求 66–68、43）。
 *
 * - 无镜像、旋转 0 且无 OSD 时：拍照原样返回原始 JPEG（最高保真、零重编码）；
 *   录像走 [transformToBitmap] 仅解码一次。
 * - 旋转 90/270 时输出宽高互换。
 * - 顺序与预览 [com.wheeltech.blindcam.render.PreviewRenderer] 一致：先旋转，再水平镜像，最后画 OSD。
 */
object JpegTransformer {
    private const val TAG = "JpegTransformer"

    /** 旋转 90/270 时宽高互换 */
    fun swapped(rotationDeg: Int): Boolean {
        val rot = ((rotationDeg % 360) + 360) % 360
        return rot == 90 || rot == 270
    }

    fun transform(jpeg: ByteArray, mirrorH: Boolean, rotationDeg: Int): ByteArray =
        transform(jpeg, mirrorH, rotationDeg, null)

    /** 拍照路径：输出 JPEG 字节。无变换时原样返回设备 JPEG（最高保真）。 */
    fun transform(
        jpeg: ByteArray,
        mirrorH: Boolean,
        rotationDeg: Int,
        overlay: OsdRenderer.Overlay?
    ): ByteArray {
        val rot = ((rotationDeg % 360) + 360) % 360
        val needGeo = mirrorH || rot != 0
        val needOsd = overlay?.any == true
        if (!needGeo && !needOsd) {
            logJpegSize(jpeg, "photo(raw)")
            return jpeg
        }
        val out = process(jpeg, mirrorH, rot, overlay) ?: return jpeg
        return try {
            val bos = ByteArrayOutputStream(jpeg.size.coerceAtLeast(1024))
            out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
            Log.i(TAG, "photo(re-encode) ${out.width}x${out.height} bytes=${bos.size()}")
            bos.toByteArray()
        } catch (t: Throwable) {
            Log.w(TAG, "re-encode failed: ${t.message}")
            jpeg
        } finally {
            runCatching { out.recycle() }
        }
    }

    /**
     * 录像路径：直接输出处理后的 Bitmap（供 MP4 硬件编码），
     * 避免 JPEG→Bitmap 再 JPEG 的二次编解码。调用方在用完后必须 recycle。
     */
    fun transformToBitmap(
        jpeg: ByteArray,
        mirrorH: Boolean,
        rotationDeg: Int,
        overlay: OsdRenderer.Overlay? = null
    ): Bitmap? = process(jpeg, mirrorH, ((rotationDeg % 360) + 360) % 360, overlay)

    /** 几何变换 + OSD 的共用实现，返回一张 ARGB Bitmap（调用方负责回收） */
    private fun process(
        jpeg: ByteArray,
        mirrorH: Boolean,
        rot: Int,
        overlay: OsdRenderer.Overlay?
    ): Bitmap? {
        val needGeo = mirrorH || rot != 0
        val needOsd = overlay?.any == true
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        var geo: Bitmap? = null
        try {
            // 1) 几何变换：先旋转，再水平镜像（与预览一致）
            var bmp: Bitmap = src
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
            // 2) OSD 需要在可变 ARGB bitmap 上绘制
            if (needOsd) {
                val m = bmp.copy(Bitmap.Config.ARGB_8888, true)
                if (m == null) return bmp
                if (bmp != src) runCatching { bmp.recycle() }
                OsdRenderer.draw(Canvas(m), m.width, m.height, overlay!!)
                geo = m
                return m
            }
            return bmp
        } catch (t: Throwable) {
            Log.w(TAG, "process failed: ${t.message}")
            return null
        } finally {
            // src 若已被几何替换则回收；最终返回的 bitmap 由调用方管理
            if (geo != null) runCatching { if (!src.isRecycled) src.recycle() }
        }
    }

    /** 打印原始 JPEG 实际分辨率（用于核对 4K 是否真的为 4K 档位） */
    private fun logJpegSize(jpeg: ByteArray, tag: String) {
        runCatching {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            if (o.outWidth > 0) Log.i(TAG, "$tag ${o.outWidth}x${o.outHeight} bytes=${jpeg.size}")
        }
    }
}
