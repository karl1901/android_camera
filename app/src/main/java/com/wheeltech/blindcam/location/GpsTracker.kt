package com.wheeltech.blindcam.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.wheeltech.blindcam.recorder.MediaSaver
import com.wheeltech.blindcam.util.CoordinateConverter
import com.wheeltech.blindcam.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 总录像 GPS 轨迹记录器：
 * - 总录像开始时 [start]，按 1 秒间隔从 GPS / 网络定位取点；
 * - 每个点一行写入 CSV（与总录像时间对齐，回看页按时间戳联动轨迹）；
 * - 总录像结束时 [stop]，把轨迹文件发布到录像保存目录（私有目录或 SAF），
 *   文件名 GPS_yyyyMMdd_HHmmss.csv。
 *
 * 车机无 GPS / 未授予定位权限时自动跳过（不影响录像）。
 * 单例（挂在 Application），只在总录像期间注册监听，停止即注销，不常驻耗电。
 */
class GpsTracker(
    private val appContext: Context,
    private val mediaSaver: MediaSaver
) {
    companion object {
        private const val TAG = "gps"
        private const val MIN_INTERVAL_MS = 1000L
        private const val MIN_DISTANCE_M = 0f
    }

    private var locationManager: LocationManager? = null
    private var cacheFile: File? = null
    private var videoDirUri: String = ""
    private var pointCount = 0

    /** 上一个被采纳的点：用于剔除网络定位/漂移造成的数公里跳点 */
    private var lastAccepted: Location? = null
    private var lastAcceptedElapsed = 0L
    private var lastGpsElapsed = 0L
    private var startWallMs = 0L

    @Volatile
    var isRecording = false
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            appendPoint(location)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 开始记录（无定位权限或无定位源时安全跳过，返回 false） */
    fun start(videoDirUri: String): Boolean {
        if (isRecording) return true
        if (!hasPermission()) {
            Logger.w(TAG, "未授予定位权限，跳过 GPS 轨迹记录")
            return false
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val cache = mediaSaver.newCacheFile("csv")
            // 写表头
            cache.writeText(
                "time_ms,iso_time,latitude,longitude,accuracy_m,speed_mps,bearing,provider\n",
                Charsets.UTF_8
            )
            cacheFile = cache
            this.videoDirUri = videoDirUri
            pointCount = 0
            lastAccepted = null
            lastAcceptedElapsed = 0L
            lastGpsElapsed = 0L
            startWallMs = System.currentTimeMillis()

            // GPS 优先；同时保留网络定位作为室内/地库无 GPS 时的兜底。
            // 网络点只在“最近 15 秒没有 GPS 点”时采纳，避免两种坐标源来回跳。
            val providers = buildList {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    add(LocationManager.GPS_PROVIDER)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    add(LocationManager.NETWORK_PROVIDER)
                }
            }
            if (providers.isEmpty()) {
                Logger.w(TAG, "没有可用定位源（GPS/网络均关闭），跳过")
                cache.delete()
                cacheFile = null
                return false
            }
            providers.forEach { p ->
                lm.requestLocationUpdates(p, MIN_INTERVAL_MS, MIN_DISTANCE_M, listener)
            }
            locationManager = lm
            isRecording = true
            Logger.i(TAG, "GPS 轨迹开始记录 -> ${cache.name} providers=$providers")
            true
        } catch (t: Throwable) {
            Logger.w(TAG, "GPS 启动失败: ${t.message}")
            false
        }
    }

    private fun appendPoint(location: Location) {
        val f = cacheFile ?: return
        try {
            val provider = location.provider.orEmpty()
            val isGps = provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true)
            val nowElapsed = SystemClock.elapsedRealtime()
            if (isGps) lastGpsElapsed = nowElapsed
            // 最近 15 秒有 GPS 时忽略网络点（网络定位在车上经常漂移几公里）
            if (!isGps && lastGpsElapsed > 0 && nowElapsed - lastGpsElapsed < 15_000) return

            // 精度过滤：GPS 正常 <20m；网络点放宽到 50m，>80m 的漂移直接丢弃
            val maxAcc = if (isGps) 80f else 50f
            if (!location.hasAccuracy() || location.accuracy > maxAcc) {
                Logger.i(TAG, "skip low accuracy=${location.accuracy} provider=$provider")
                return
            }
            if (location.latitude == 0.0 && location.longitude == 0.0) return

            val prev = lastAccepted
            if (prev != null && prev.provider == provider) {
                val dtSec = max(1L, (nowElapsed - lastAcceptedElapsed) / 1000L)
                val dist = CoordinateConverter.distanceMeters(
                    prev.latitude, prev.longitude,
                    location.latitude, location.longitude
                )
                // 允许的最大位移 = 速度推算 + 两次精度 + 固定余量。
                val speed = max(prev.speed, location.speed).coerceAtLeast(0f)
                val maxJump = max(120.0, speed * dtSec + prev.accuracy + location.accuracy + 80.0)
                if (dist > maxJump) {
                    Logger.i(
                        TAG,
                        "skip GPS jump dist=${dist.toInt()}m max=${maxJump.toInt()}m " +
                            "acc=${location.accuracy} provider=$provider"
                    )
                    return
                }
            }
            // 切换 provider（GPS↔网络）时以新 provider 的第一个点为基线，
            // 不做跨源跳点比较；回看时会优先只取 GPS 点。
            if (prev != null && prev.provider != provider) {
                Logger.i(TAG, "GPS provider switch ${prev.provider} -> $provider, reset baseline")
            }

            val now = System.currentTimeMillis()
            // 用回调时刻而不是 location.time：网络/缓存定位时刻可能早于录像起点，
            // 会让回看轨迹点落在时间轴范围外。回调时刻与录像时间轴严格对齐。
            val t = max(now, startWallMs)
            val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
            val line = buildString {
                append(t).append(',')
                append(iso).append(',')
                append("%.7f".format(Locale.US, location.latitude)).append(',')
                append("%.7f".format(Locale.US, location.longitude)).append(',')
                append("%.1f".format(Locale.US, location.accuracy)).append(',')
                append(if (location.hasSpeed()) "%.2f".format(Locale.US, location.speed) else "")
                append(',')
                append(if (location.hasBearing()) "%.1f".format(Locale.US, location.bearing) else "")
                append(',')
                append(provider)
                append('\n')
            }
            f.appendText(line, Charsets.UTF_8)
            pointCount++
            lastAccepted = location
            lastAcceptedElapsed = nowElapsed
            if (pointCount % 10 == 0) {
                Logger.i(TAG, "GPS 点数=$pointCount last=${location.latitude},${location.longitude}")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "写 GPS 点失败: ${t.message}")
        }
    }

    /** 结束并发布轨迹文件，返回最终文件名（无点则删除并返回 null） */
    fun stop(): String? {
        if (!isRecording) return null
        isRecording = false
        runCatching { locationManager?.removeUpdates(listener) }
        locationManager = null
        val cache = cacheFile
        cacheFile = null
        if (cache == null) return null
        if (pointCount <= 0) {
            runCatching { cache.delete() }
            Logger.i(TAG, "GPS 无有效定位点，不生成轨迹文件")
            return null
        }
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(cache.lastModified()))
            val name = "GPS_${ts}.csv"
            val result = mediaSaver.publishFile(cache, name, videoDirUri, "text/csv")
            Logger.i(TAG, "GPS 轨迹已保存 points=$pointCount -> $result")
            name
        } catch (t: Throwable) {
            runCatching { cache.delete() }
            Logger.w(TAG, "GPS 轨迹发布失败: ${t.message}")
            null
        }
    }
}

