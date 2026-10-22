package com.wheeltech.blindcam.domain

/**
 * 一路可协商的视频规格：分辨率 + 帧率（MJPEG）。
 */
data class StreamProfile(
    val width: Int,
    val height: Int,
    val fps: Int
) {
    /** 像素总数，用于"最大分辨率"排序 */
    val pixels: Long get() = width.toLong() * height.toLong()

    /** 展示用，如 "1920x1080 · 30fps" */
    val display: String get() = "${width}x${height} · ${fps}fps"

    companion object {
        /**
         * 解析 native 能力字符串。
         * 格式："MJPEG;WxH:fps,fps;WxH:fps"，多个格式用 '|' 分隔。
         * 结果按分辨率面积降序、同分辨率帧率降序排列（最大规格在最前）。
         */
        fun parseCapabilities(raw: String): List<StreamProfile> {
            if (raw.isBlank()) return emptyList()
            val result = ArrayList<StreamProfile>()
            // 多个格式块（当前只取 MJPEG，块内首段即格式名）
            raw.split("|").forEach { block ->
                val parts = block.split(";")
                if (parts.isEmpty()) return@forEach
                val formatName = parts[0]
                if (!formatName.equals("MJPEG", ignoreCase = true)) return@forEach
                parts.drop(1).forEach { frameSpec ->
                    // frameSpec = "WxH:fps,fps"
                    val frameParts = frameSpec.split(":")
                    if (frameParts.size < 2) return@forEach
                    val dims = frameParts[0].split("x")
                    if (dims.size != 2) return@forEach
                    val w = dims[0].trim().toIntOrNull() ?: return@forEach
                    val h = dims[1].trim().toIntOrNull() ?: return@forEach
                    frameParts[1].split(",").forEach { fpsStr ->
                        val fps = fpsStr.trim().toIntOrNull() ?: return@forEach
                        if (fps > 0) result.add(StreamProfile(w, h, fps))
                    }
                }
            }
            return result.distinct()
                .sortedWith(compareByDescending<StreamProfile> { it.pixels }.thenByDescending { it.fps })
        }
    }
}
