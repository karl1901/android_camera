package com.wheeltech.blindcam.imaging

import android.graphics.Bitmap
import com.wheeltech.blindcam.util.Logger
import kotlin.math.abs
import kotlin.math.pow

/**
 * 实时降噪 / 夜视增强（纯 CPU 整数运算 + 预计算 LUT）。
 *
 * 针对实测反馈重写：
 * - 旧版“强档”是块均值把整块拉平，纹理变糊、运动物体拖影；新版在每个块内
 *   先判断边缘强度与运动量，只在平坦/静止块做时域累积，边缘和运动块保持当前帧。
 * - 增加夜视 Gamma/阴影提升 LUT：暗部被抬亮，观感接近“夜视/HDR”，
 *   而不是单纯抹平噪声。
 * - 性能：以 2×2（4K 用 4×4）块为单位计算均值，避免逐像素分支；
 *   1080p 的目标是几十毫秒内，超过预算由 PreviewRenderer 原图回退，
 *   绝不阻塞 USB 取流线程。
 * - 每路独立实例、latest-only，运动物体不拖影、强档不冻结。
 *
 * 输出位图为内部复用，调用方不得回收；level=0 返回入参原图。
 */
class FrameDenoiser(
    private var level: Int = 0,
    budgetMs: Int = 30
) {
    @Volatile
    private var budgetMs: Int = budgetMs.coerceIn(8, 220)

    private var w = 0
    private var h = 0
    private var out: Bitmap? = null
    private var curPx: IntArray? = null
    private var outPx: IntArray? = null

    // 块级历史干净均值（按通道），避免整帧历史位图的内存/带宽
    private var cleanR: IntArray? = null
    private var cleanG: IntArray? = null
    private var cleanB: IntArray? = null
    private var bw = 0
    private var bh = 0
    private var block = 2
    private var haveHistory = false

    private var toneLut: IntArray = IDENTITY_LUT
    private var toneLevel = -1
    private var frames = 0

    @Volatile var lastCostMs: Long = 0
        private set
    @Volatile var overBudget: Boolean = false
        private set

    fun setLevel(l: Int) {
        level = l.coerceIn(0, 3)
        toneLevel = -1
    }

    fun setBudget(ms: Int) {
        budgetMs = ms.coerceIn(8, 220)
    }

    fun level(): Int = level

    fun process(src: Bitmap): Bitmap {
        if (level == 0) return src
        val sw = src.width
        val sh = src.height
        try {
            ensure(sw, sh)
        } catch (oom: OutOfMemoryError) {
            Logger.e(TAG, "denoise alloc failed ${sw}x$sh, fallback raw", oom)
            releaseBuffers()
            return src
        } catch (t: Throwable) {
            Logger.w(TAG, "denoise alloc failed ${sw}x$sh, fallback raw: ${t.message}")
            releaseBuffers()
            return src
        }

        val t0 = System.nanoTime()
        val cur = curPx!!
        val opx = outPx!!
        val cR = cleanR!!
        val cG = cleanG!!
        val cB = cleanB!!
        src.getPixels(cur, 0, sw, 0, 0, sw, sh)
        val lut = toneLutFor(level)

        // 档位参数：sMax=静态块拉齐强度；d 为块亮度差
        val sMax: Int
        val tLow: Int
        val tHigh: Int
        when (level) {
            1 -> { sMax = 120; tLow = 10; tHigh = 38 }
            2 -> { sMax = 180; tLow = 8; tHigh = 32 }
            else -> { sMax = 225; tLow = 7; tHigh = 28 }
        }
        val span = (tHigh - tLow).coerceAtLeast(1)
        val k = block

        var bi = 0
        for (by in 0 until bh) {
            val y0 = by * k
            val y1 = minOf(sh, y0 + k)
            for (bx in 0 until bw) {
                val x0 = bx * k
                val x1 = minOf(sw, x0 + k)
                var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                var minY = 255; var maxY = 0
                var yy = y0
                while (yy < y1) {
                    var row = yy * sw + x0
                    val end = row + (x1 - x0)
                    while (row < end) {
                        val p = cur[row]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        sr += r; sg += g; sb += b
                        val l = (r * 77 + g * 150 + b * 29) shr 8
                        if (l < minY) minY = l
                        if (l > maxY) maxY = l
                        cnt++
                        row++
                    }
                    yy++
                }
                val mr = sr / cnt; val mg = sg / cnt; val mb = sb / cnt
                val lCur = (mr * 77 + mg * 150 + mb * 29) shr 8
                var cr: Int; var cg: Int; var cb: Int
                if (!haveHistory) {
                    cr = mr; cg = mg; cb = mb
                } else {
                    cr = cR[bi]; cg = cG[bi]; cb = cB[bi]
                }
                val lClean = (cr * 77 + cg * 150 + cb * 29) shr 8
                val d = abs(lCur - lClean)
                var s = when {
                    d <= tLow -> sMax
                    d >= tHigh -> 0
                    else -> sMax * (tHigh - d) / span
                }
                // 边缘保护：块内亮度跨度大说明有细节/物体边缘，不做强均值拉平，
                // 避免“强降噪整块糊掉”和大面积残影。
                if (maxY - minY > 28) s = minOf(s, 60)
                // 历史块追踪：静止缓慢跟随，运动立即跟随当前块（无拖影）
                val adopt = (256 - s).coerceAtLeast(40)
                cR[bi] = (cr * (256 - adopt) + mr * adopt) shr 8
                cG[bi] = (cg * (256 - adopt) + mg * adopt) shr 8
                cB[bi] = (cb * (256 - adopt) + mb * adopt) shr 8

                // 块内逐像素：只向干净块均值做一次整数拉齐，再套夜视 LUT
                yy = y0
                while (yy < y1) {
                    var row = yy * sw + x0
                    val end = row + (x1 - x0)
                    while (row < end) {
                        val p = cur[row]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        var nr = r + (((cR[bi] - r) * s) shr 8)
                        var ng = g + (((cG[bi] - g) * s) shr 8)
                        var nb = b + (((cB[bi] - b) * s) shr 8)
                        if (nr < 0) nr = 0 else if (nr > 255) nr = 255
                        if (ng < 0) ng = 0 else if (ng > 255) ng = 255
                        if (nb < 0) nb = 0 else if (nb > 255) nb = 255
                        val tr = lut[nr]; val tg = lut[ng]; val tb = lut[nb]
                        opx[row] = (0xFF shl 24) or (tr shl 16) or (tg shl 8) or tb
                        row++
                    }
                    yy++
                }
                bi++
            }
        }
        haveHistory = true
        out!!.setPixels(opx, 0, sw, 0, 0, sw, sh)

        val cost = (System.nanoTime() - t0) / 1_000_000
        lastCostMs = cost
        frames++
        if (frames <= 3 || frames % 60 == 0) {
            Logger.i(TAG, "denoise ${w}x$h level=$level block=$k cost=${cost}ms")
        }
        overBudget = cost > budgetMs
        if (overBudget) {
            Logger.w(TAG, "denoise over budget ${w}x$h cost=${cost}ms budget=${budgetMs}ms")
        }
        return out!!
    }

    /** 夜视 LUT：Gamma 抬暗部 + 轻微中间调对比，模拟 HDR/夜视观感 */
    private fun toneLutFor(l: Int): IntArray {
        if (toneLevel == l) return toneLut
        val gamma = when (l) { 1 -> 1.10; 2 -> 1.22; else -> 1.38 }
        val lift = when (l) { 1 -> 1; 2 -> 3; else -> 5 }
        val lut = IntArray(256)
        for (i in 0 until 256) {
            var v = (255.0 * (i / 255.0).pow(1.0 / gamma)).toInt() + lift
            v = (v + ((v - 128) * 0.05f).toInt()).coerceIn(0, 255)
            lut[i] = v
        }
        toneLut = lut
        toneLevel = l
        return lut
    }

    private fun ensure(sw: Int, sh: Int) {
        // 1080p 用 2x2；4K 用 4x4，避免 4K 强档把 CPU 打满
        val targetBlock = if (sw.toLong() * sh > 3_000_000L) 4 else 2
        if (sw == w && sh == h && out != null && targetBlock == block) return
        releaseBuffers()
        out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        curPx = IntArray(sw * sh)
        outPx = IntArray(sw * sh)
        w = sw; h = sh
        block = targetBlock
        bw = (sw + block - 1) / block
        bh = (sh + block - 1) / block
        val n = bw * bh
        cleanR = IntArray(n)
        cleanG = IntArray(n)
        cleanB = IntArray(n)
        haveHistory = false
        toneLevel = -1
    }

    private fun releaseBuffers() {
        out?.recycle()
        out = null
        curPx = null
        outPx = null
        cleanR = null; cleanG = null; cleanB = null
        bw = 0; bh = 0; w = 0; h = 0
        haveHistory = false
    }

    fun release() {
        releaseBuffers()
    }

    companion object {
        private const val TAG = "denoise"
        private val IDENTITY_LUT = IntArray(256) { it }
    }
}
