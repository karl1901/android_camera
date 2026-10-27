package com.wheeltech.blindcam.location

/**
 * 一个 GPS 轨迹点（与录像时间轴对齐）。
 * @param timeMs 定位时刻（Unix 毫秒），用于和总录像时间轴联动
 */
data class TrackPoint(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    /** gps / network / passive；空=旧文件 */
    val provider: String = ""
)
