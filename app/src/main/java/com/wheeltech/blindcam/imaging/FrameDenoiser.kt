package com.wheeltech.blindcam.imaging

import android.graphics.Bitmap
import com.wheeltech.blindcam.util.Logger

/**
 * 软件 ISP：实时降噪（CPU 版，纯整数 + 分块 + 运动自适应 + 时间累积）。
 *
 * 设计要点（性能优先，支持 4K、每路独立）：
 * - 不做任何逐像素 Float 乘除：所有运算为整数，避免老车机弱核上的软浮点开销。
 * - 以 k×k 块为单位：块级时间累积（上一帧干净块均值），块内逐像素只做一次
 *   整数缩放「向干净块均值拉多少」，把空间降噪与时间降噪合并成一次乘加。
 * - 运动自适应：块均值与历史干净值差异大（真实运动）→ 不拉齐（s=0，画面锐利、无拖影）；
 *   差异小（静态噪声）→ 强拉齐，噪声被时间累积吃掉。
 * - 自适应块大小（热降级）：按 [budgetMs] 预算测量处理耗时，太慢就加大块（2→3→4→6→8），
 *   仍超时则 [overBudget] 返回 true，由调用方决定是否临时回退原图（预览永不卡死）。
 *
 * 输出位图为内部复用（[process] 返回值），调用方不得回收；关闭时返回入参原图（零拷贝）。
 *
 * @param level 0关 / 1弱 / 2中 / 3强
 * @param budgetMs 单帧处理预算（毫秒）。预览约为帧间隔，录像约为帧间隔；超过则降级。
 */
class FrameDenoiser(
    private var level: Int = 0,
    private val budgetMs: Int = 30
) {
    private var w = 0
    private var h = 0

    /** 输出位图（内部复用） */
    private var out: Bitmap? = null

    /** 块网格的历史干净均值（按通道，长度 bw*bh） */
    private var cleanR: IntArray? = null
    private var cleanG: IntArray? = null
    private var cleanB: IntArray? = null
    private var haveHistory = false

    /** 当前块边长（自适应） */
    private var block = 2
    private var bw = 0
    private var bh = 0

    /** 复用的全分辨率像素数组（避免每帧分配） */
    private var curPx: IntArray? = null
    private var outPx: IntArray? = null

    /** 上一帧处理耗时（ms），供外部观测 */
    @Volatile var lastCostMs: Long = 0
        private set
    /** 上一帧是否在最粗块下仍超预算（外部据此回退原图） */
    @Volatile var overBudget: Boolean = false
        private set

    fun setLevel(l: Int) {
        level = l.coerceIn(0, 3)
    }

    fun level(): Int = level

    /**
     * 处理一帧。
     * @param src 当前帧（MJPEG 解码结果，ARGB）
     * @return 降噪后的内部复用位图；level=0 时直接返回 [src]
     */
    fun process(src: Bitmap): Bitmap {
        if (level == 0) return src
        val sw = src.width
        val sh = src.height
        ensure(sw, sh)

        val t0 = System.nanoTime()
        val px = curPx!!
        val opx = outPx!!
        src.getPixels(px, 0, sw, 0, 0, sw, sh)

        // 各档位参数
        // sMax：静态时逐像素向干净块均值拉齐的强度（0..256）
        // tLow/tHigh：块亮度差的运动门限（越小越保守）
        val sMax: Int
        val tLow: Int
        val tHigh: Int
        when (level) {
            1 -> { sMax = 110; tLow = 14; tHigh = 40 }
            2 -> { sMax = 170; tLow = 18; tHigh = 52 }
            else -> { sMax = 215; tLow = 22; tHigh = 64 }
        }
        val cR = cleanR!!
        val cG = cleanG!!
        val cB = cleanB!!
        val k = block
        val span = tHigh - tLow

        var bi = 0
        for (by in 0 until bh) {
            val y0 = by * k
            val y1 = minOf(sh, y0 + k)
            for (bx in 0 until bw) {
                val x0 = bx * k
                val x1 = minOf(sw, x0 + k)

                // 块均值（全块求和；已持有全像素数组，求和很便宜）
                var sr = 0; var sg = 0; var sb = 0; var cnt = 0
                var yy = y0
                while (yy < y1) {
                    var row = yy * sw + x0
                    val rowEnd = row + (x1 - x0)
                    while (row < rowEnd) {
                        val p = px[row]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                        cnt++
                        row++
                    }
                    yy++
                }
                val mr = sr / cnt
                val mg = sg / cnt
                val mb = sb / cnt

                var cr: Int; var cg: Int; var cb: Int
                if (!haveHistory) {
                    cr = mr; cg = mg; cb = mb
                } else {
                    cr = cR[bi]; cg = cG[bi]; cb = cB[bi]
                }

                // 亮度差（cheap luma：(r+2g+b)>>2）
                val lCur = (mr + 2 * mg + mb) shr 2
                val lClean = (cr + 2 * cg + cb) shr 2
                val d = kotlin.math.abs(lCur - lClean)

                // s：逐像素拉齐强度 0..sMax
                val s: Int = when {
                    d <= tLow -> sMax
                    d >= tHigh -> 0
                    else -> sMax * (tHigh - d) / span
                }
                // 历史干净块对当前帧的采纳率：静态时慢跟踪（去噪），运动时直接追上（无拖影）
                val adopt = (256 - s).coerceAtLeast(28)
                cR[bi] = (cr * (256 - adopt) + mr * adopt) shr 8
                cG[bi] = (cg * (256 - adopt) + mg * adopt) shr 8
                cB[bi] = (cb * (256 - adopt) + mb * adopt) shr 8

                // 块内逐像素：out = c + ((clean - c) * s >> 8)
                yy = y0
                while (yy < y1) {
                    var row = yy * sw + x0
                    val rowEnd = row + (x1 - x0)
                    while (row < rowEnd) {
                        val p = px[row]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        var or2 = r + (((cR[bi] - r) * s) shr 8)
                        var og2 = g + (((cG[bi] - g) * s) shr 8)
                        var ob2 = b + (((cB[bi] - b) * s) shr 8)
                        if (or2 < 0) or2 = 0 else if (or2 > 255) or2 = 255
                        if (og2 < 0) og2 = 0 else if (og2 > 255) og2 = 255
                        if (ob2 < 0) ob2 = 0 else if (ob2 > 255) ob2 = 255
                        opx[row] = (0xFF shl 24) or (or2 shl 16) or (og2 shl 8) or ob2
                        row++
                    }
                    yy++
                }
                bi++
            }
        }
        haveHistory = true

        val o = out!!
        o.setPixels(opx, 0, sw, 0, 0, sw, sh)
        val cost = (System.nanoTime() - t0) / 1_000_000
        lastCostMs = cost
        adaptBlock(cost)
        return o
    }

    /** 按预算调整块大小（热降级 / 质量回升） */
    private fun adaptBlock(costMs: Long) {
        overBudget = false
        if (costMs > budgetMs) {
            val next = when (block) {
                2 -> 3; 3 -> 4; 4 -> 6; 6 -> 8; else -> 8
            }
            if (next != block) {
                block = next
                rebuildGrid()
                Logger.i(TAG, "denoise 降级 block=$block cost=${costMs}ms")
            } else {
                // 已到最粗仍超时：通知外部回退原图
                overBudget = true
            }
        } else if (costMs < budgetMs * 0.55f) {
            // 余量充足：回升质量（块变小）
            val prev = when (block) {
                8 -> 6; 6 -> 4; 4 -> if (level <= 1) 2 else 3; 3 -> 2; else -> 2
            }
            if (prev != block) {
                block = prev
                rebuildGrid()
            }
        }
    }

    private fun ensure(sw: Int, sh: Int) {
        if (sw == w && sh == h && out != null) return
        out?.recycle()
        out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        curPx = IntArray(sw * sh)
        outPx = IntArray(sw * sh)
        w = sw; h = sh
        // 初始块大小按档位与分辨率粗估（4K 起步大一点，避免首帧就超时）
        block = when {
            level >= 3 && (sw >= 3200 || sh >= 3200) -> 4
            level >= 2 && (sw >= 3200 || sh >= 3200) -> 3
            level == 1 -> 2
            else -> 3
        }
        rebuildGrid()
    }

    private fun rebuildGrid() {
        bw = (w + block - 1) / block
        bh = (h + block - 1) / block
        val n = bw * bh
        cleanR = IntArray(n)
        cleanG = IntArray(n)
        cleanB = IntArray(n)
        haveHistory = false
    }

    fun release() {
        out?.recycle()
        out = null
        curPx = null
        outPx = null
        cleanR = null
        cleanG = null
        cleanB = null
        w = 0; h = 0
    }

    companion object {
        private const val TAG = "denoise"
    }
}
