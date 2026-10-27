package com.wheeltech.blindcam.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS-84 → GCJ-02（火星坐标）转换。
 *
 * Android LocationManager 返回的是 WGS-84；高德地图/国内地图 SDK 使用 GCJ-02。
 * 不转换会出现几十到几百米的系统性偏移，看起来就是“轨迹不准/不在路上”。
 * 这里采用公开的 GCJ-02 偏移算法，纯数学、无第三方依赖。
 */
object CoordinateConverter {

    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    data class LatLng(val lat: Double, val lon: Double)

    fun wgs84ToGcj02(lat: Double, lon: Double): LatLng {
        if (outOfChina(lat, lon)) return LatLng(lat, lon)
        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = dLat * 180.0 / (A * (1 - EE) / (magic * sqrtMagic) * Math.PI)
        dLon = dLon * 180.0 / (A / sqrtMagic * cos(radLat) * Math.PI)
        return LatLng(lat + dLat, lon + dLon)
    }

    /** 粗判断是否在中国境外；境外不做偏移 */
    private fun outOfChina(lat: Double, lon: Double): Boolean =
        lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320.0 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }

    /** 两点球面距离（米），用于 GPS 跳点过滤 */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * kotlin.math.atan2(sqrt(a), sqrt(1 - a))
    }
}
