package com.wheeltech.blindcam.recorder

/**
 * 录像码率顾问：按 USB 协议实际能力 + 当前分辨率/帧率/路数动态给出码率档位。
 *
 * 背景：MJPEG 采集的 USB 带宽与 H.264 录制输出码率是两段。
 * - USB2.0 等时带宽约 380–420Mbps，两路 1080p30 MJPEG 约 40Mbps，带宽压力很小；
 *   USB3.0 可承载 4K60。采集侧能否跑满由取流档位协商决定。
 * - 录制码率决定 H.264 成片的画质/体积，按「每像素比特数 bpp」随分辨率帧率线性给出，
 *   再 clamp 到合理区间，避免低分辨率码率虚高、4K 码率不足。
 *
 * 档位（用户可选，0=自动=高画质）：
 * 1 省流 / 2 标准 / 3 高画质(自动) / 4 原画。
 */
object BitrateAdvisor {

    const val QUALITY_AUTO = 0
    const val QUALITY_SAVE = 1
    const val QUALITY_STANDARD = 2
    const val QUALITY_HIGH = 3
    const val QUALITY_MAX = 4

    private val QUALITY_LABELS = listOf("自动", "省流", "标准", "高画质", "原画")

    fun labelOf(quality: Int): String =
        QUALITY_LABELS.getOrElse(quality.coerceIn(0, 4)) { QUALITY_LABELS[0] }

    /** 每像素每帧比特数（bpp）系数 */
    private fun bpp(quality: Int): Double = when (quality.coerceIn(0, 4)) {
        QUALITY_SAVE -> 0.06
        QUALITY_STANDARD -> 0.10
        QUALITY_MAX -> 0.25
        else -> 0.15 // 自动 / 高画质
    }

    /**
     * 单路录像码率（bps）。随分辨率/帧率动态计算。
     * 1080p30 自动 ≈ 9.3Mbps；4K30 自动 ≈ 37Mbps；4K60 自动 ≈ 74Mbps（clamp 80Mbps）。
     */
    fun singleBitrate(w: Int, h: Int, fps: Int, quality: Int): Int {
        val f = fps.coerceIn(1, 60)
        val v = (w.toLong() * h * f * bpp(quality)).toLong()
        return v.coerceIn(2_000_000L, 80_000_000L).toInt()
    }

    /**
     * 总录像（多路合成）码率（bps）。
     * 合成输出固定 720p、~15fps，但要在一屏内看清每一格，路数越多每格越小、
     * 纹理/文字越多，故在 720p 基准上按路数与档位上调。
     */
    fun compositeBitrate(cellCount: Int, quality: Int): Int {
        val cells = cellCount.coerceIn(1, 6)
        // 基准（720p15 单路）随档位
        val base = when (quality.coerceIn(0, 4)) {
            QUALITY_SAVE -> 3_000_000
            QUALITY_STANDARD -> 5_000_000
            QUALITY_MAX -> 14_000_000
            else -> 7_000_000 // 自动 / 高画质
        }
        // 多路网格：每多一路，整体需要更多码率承载分隔线/OSD/多格细节（非线性、封顶）
        val factor = when {
            cells <= 1 -> 1.0f
            cells == 2 -> 1.6f
            cells == 3 -> 2.0f
            cells == 4 -> 2.4f
            else -> 2.8f
        }
        return (base * factor).toInt().coerceIn(3_000_000, 30_000_000)
    }

    /** 码率友好展示，如 "9.3 Mbps" */
    fun mbps(bps: Int): String =
        String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
}
