package com.wheeltech.blindcam.imaging

import android.graphics.Bitmap
import com.wheeltech.blindcam.util.Logger
import kotlin.math.abs

/**
 * 每路独立的「多帧时域降噪器」（软件 ISP 的一部分）。
 *
 * 思路对标手机夜景的多帧时域降噪：
 * - 维护上一帧的降噪结果（历史帧），与当前帧做加权混合；
 * - 静态区域历史权重大（多帧累积，噪声被平均掉，等效多帧长曝光）；
 * - 运动区域按帧间亮度差自适应降低历史权重，避免拖影/鬼影；
 * - 暗光区域噪声更大，自动提高历史权重（暗光自动压噪点）。
 * - 历史帧取「上一帧的输出」，形成指数累积，单帧只存一张历史即可等效多帧，开销极低。
 *
 * 性能：每帧只做一次像素遍历（O(像素数)），无逐帧内存分配（数组/输出位图按尺寸复用）。
 * 每个 [PreviewRenderer] / 每路录像各持一个实例，在各自线程调用，因此天然「每路独立、
 * 按该画框分辨率/帧率处理」，多路之间互不影响；4K 也只是线性增加像素数，
 * 并在 [process] 内对超大分辨率自动隔帧处理以保证实时。
 *
 * @param level 降噪档位：0=关闭，1=弱，2=中，3=强
 */
class FrameDenoiser(private var level: Int = 0) {

    /** 历史帧像素（上一帧输出），尺寸变化时重建 */
    private var hist: IntArray? = null
    /** 当前帧像素复用缓冲 */
    private var cur: IntArray? = null
    /** 与输入同尺寸的输出位图（复用），ARGB_8888 */
    private var out: Bitmap? = null
    private var w = 0
    private var h = 0

    private var frameNo = 0
    /** 超大分辨率隔帧处理的步长（1=每帧） */
    private var stride = 1

    fun setLevel(l: Int) {
        val nl = l.coerceIn(0, MAX_LEVEL)
        if (nl != level) {
            level = nl
            // 档位变化清空历史，避免档位切换瞬间的拖影
            hist = null
            frameNo = 0
            Logger.i(TAG, "denoise level=$level")
        }
    }

    val enabled: Boolean get() = level > 0

    /**
     * 处理一帧，返回降噪后的位图。
     * - 关闭或尺寸异常时原样返回输入位图（零拷贝）。
     * - 输出位图为内部复用缓冲，调用方在本次使用完前不要回收、也不要长期持有。
     * 必须在固定的单一线程内调用（历史帧不做跨线程同步）。
     */
    fun process(input: Bitmap): Bitmap {
        if (level <= 0 || input.isRecycled) return input
        val iw = input.width
        val ih = input.height
        if (iw <= 0 || ih <= 0) return input

        // 4K 等超大分辨率：为保实时，强档也只做隔帧时域（其余帧原样输出），
        // 实测 1080p 走每帧；具体阈值按像素总数自适应。
        stride = when {
            iw.toLong() * ih >= 3_500_000L -> 2 // ~4K：隔帧，仍有效且更省电
            else -> 1
        }
        frameNo++
        if (stride > 1 && frameNo % stride != 0) {
            // 隔帧：该帧不更新历史，但仍可输出上一帧会导致延迟，故直接输出当前帧
            return input
        }

        ensureBuffers(iw, ih)
        val histArr = hist!!
        val curArr = cur!!
        val outBmp = out!!

        input.getPixels(curArr, 0, iw, 0, 0, iw, ih)
        // 首帧：历史=当前，输出原图
        val hasHistory = histArr.size == curArr.size && frameNo > stride
        if (!hasHistory) {
            System.arraycopy(curArr, 0, histArr, 0, curArr.size)
            outBmp.setPixels(curArr, 0, iw, 0, 0, iw, ih)
            return outBmp
        }

        // 档位参数：历史基础权重 + 运动判定阈值（亮度 0..255）
        // 历史权重越大降噪越强但越容易拖影，阈值越大越不易把噪声判成运动
        val baseBlend = when (level) {
            1 -> 0.30f
            2 -> 0.50f
            else -> 0.70f
        }
        val motionThr = when (level) {
            1 -> 10f
            2 -> 14f
            else -> 18f
        }
        val motionRange = 28f

        val n = curArr.size
        for (i in 0 until n) {
            val c = curArr[i]
            val p = histArr[i]
            val cr = c and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = (c shr 16) and 0xFF
            val pr = p and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = (p shr 16) and 0xFF

            // 亮度（BT.601 整数）
            val lumC = (cr * 77 + cg * 150 + cb * 29) shr 8
            val lumP = (pr * 77 + pg * 150 + pb * 29) shr 8
            val diff = abs(lumC - lumP).toFloat()

            // 运动门控：差值越小越静态；超过阈值+范围则不混合
            var gate = 1f - ((diff - motionThr) / motionRange).coerceIn(0f, 1f)
            // 暗光（低亮度）噪声更大，适度增强历史权重（最高 +0.2）
            val darkBoost = (1f - (lumC / 90f).coerceIn(0f, 1f)) * 0.20f
            val w = (baseBlend + darkBoost) * gate
            if (w <= 0.01f) {
                histArr[i] = c
                continue
            }
            val iw2 = 1f - w
            val r = (cr * iw2 + pr * w).toInt().coerceIn(0, 255)
            val g = (cg * iw2 + pg * w).toInt().coerceIn(0, 255)
            val b = (cb * iw2 + pb * w).toInt().coerceIn(0, 255)
            val merged = (0xFF shl 24) or (b shl 16) or (g shl 8) or r
            curArr[i] = merged
            histArr[i] = merged // 历史=输出，指数累积多帧
        }
        outBmp.setPixels(curArr, 0, iw, 0, 0, iw, ih)
        return outBmp
    }

    private fun ensureBuffers(iw: Int, ih: Int) {
        if (iw == w && ih == h && out != null) return
        val count = iw * ih
        out?.recycle()
        out = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        hist = IntArray(count)
        cur = IntArray(count)
        w = iw; h = ih
        frameNo = 0
    }

    /** 释放（路移除/页面销毁时调用） */
    fun release() {
        out?.recycle()
        out = null
        hist = null
        cur = null
        w = 0; h = 0
    }

    companion object {
        private const val TAG = "denoise"
        const val MAX_LEVEL = 3

        /** 档位中文名（设置 UI 用） */
        fun levelLabel(l: Int): String = when (l.coerceIn(0, MAX_LEVEL)) {
            0 -> "关闭"
            1 -> "弱"
            2 -> "中"
            else -> "强"
        }
    }
}
