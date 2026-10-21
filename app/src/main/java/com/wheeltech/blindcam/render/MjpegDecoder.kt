package com.wheeltech.blindcam.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.wheeltech.blindcam.util.Logger
import java.io.ByteArrayInputStream

/**
 * MJPEG 解码工具：把单帧 JPEG 字节解码为 Bitmap。
 *
 * 采用系统 BitmapFactory（硬解/软解由系统选择），不引入三方库。
 * 后续如需更低延迟，可在 native 层对接 ImageDecoder / 硬解，接口保持不变。
 */
object MjpegDecoder {

    private const val TAG = "mjpeg"

    /**
     * 注意：调用线程是 native streaming 线程，本方法耗时较小（单帧 JPEG 解码），
     * 但在低端车机上仍可能达到数毫秒，渲染模块应控制丢帧。
     */
    fun decode(mjpeg: ByteArray, offset: Int = 0, length: Int = mjpeg.size): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                // 车机预览用 RGB_565 降低内存占用（1080p 帧约 4MB → 2MB）
                inPreferredConfig = Bitmap.Config.RGB_565
                inPreferQualityOverSpeed = false
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
}
