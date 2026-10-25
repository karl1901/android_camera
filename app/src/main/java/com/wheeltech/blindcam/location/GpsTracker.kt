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
import com.wheeltech.blindcam.util.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

            val providers = buildList {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
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
            // 立即尝试最后已知位置，缩短首点等待
            providers.firstNotNullOfOrNull { p ->
                runCatching { lm.getLastKnownLocation(p) }.getOrNull()
            }?.let { appendPoint(it) }

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
            val now = System.currentTimeMillis()
            val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
            val line = buildString {
                append(now).append(',')
                append(iso).append(',')
                append("%.7f".format(Locale.US, location.latitude)).append(',')
                append("%.7f".format(Locale.US, location.longitude)).append(',')
                append(if (location.hasAccuracy()) "%.1f".format(Locale.US, location.accuracy) else "")
                append(',')
                append(if (location.hasSpeed()) "%.2f".format(Locale.US, location.speed) else "")
                append(',')
                append(if (location.hasBearing()) "%.1f".format(Locale.US, location.bearing) else "")
                append(',')
                append(location.provider ?: "")
                append('\n')
            }
            f.appendText(line, Charsets.UTF_8)
            pointCount++
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
