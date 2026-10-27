package com.wheeltech.blindcam.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wheeltech.blindcam.util.Logger
import java.io.ByteArrayInputStream

/**
 * MJPEG 解码工具：把单帧 JPEG 字节解码为 Bitmap。
 *
 * 采用系统 BitmapFactory（硬解/软解由系统选择），不引入三方库。
 * 预览走全尺寸 RGB_565；预警等分析场景可传 [sampleSize] 按需降采样，
 * 避免在车机弱核上反复解码 4K 全尺寸帧。
 */
object MjpegDecoder {

    private const val TAG = "mjpeg"

    /**
     * @param sampleSize 采样率（1=原尺寸，2/4/8… 为 1/2、1/4、1/8），仅用于分析场景
     */
    fun decode(
        mjpeg: ByteArray,
        offset: Int = 0,
        length: Int = mjpeg.size,
        sampleSize: Int = 1
    ): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                // 车机预览用 RGB_565 降低内存占用（1080p 帧约 4MB → 2MB）
                inPreferredConfig = Bitmap.Config.RGB_565
                inPreferQualityOverSpeed = false
                if (sampleSize > 1) inSampleSize = sampleSize
            }
            BitmapFactory.decodeStream(
                ByteArrayInputStream(mjpeg, offset, length), null, options
            )
        } catch (t: Throwable) {
            // 单帧坏数据不允许导致预览崩溃
            Logger.w(TAG, "decode mjpeg frame failed: ${t.message}")
            null
        }
    }

    /** 根据原图短边估算“分析用”采样率，目标短边约 [targetShortSide] 像素 */
    fun sampleSizeFor(width: Int, height: Int, targetShortSide: Int = 120): Int {
        val shortSide = minOf(width, height)
        if (shortSide <= 0) return 1
        var s = 1
        while (shortSide / (s * 2) >= targetShortSide && s < 32) s *= 2
        return s
    }
}
