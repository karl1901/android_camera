package com.wheeltech.blindcam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/** 应用级 DataStore（单例，懒加载） */
private val Context.uvcDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "uvc_settings"
)

/**
 * 设置仓库：DataStore 持久化。
 * - 全局设置：布局 / 主题 / 保存目录
 * - 设备设置：每路摄像头的顺序 / 名称 / 镜像 / 旋转 / 档位（JSON，按稳定标识绑定）
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val LAYOUT_MODE = intPreferencesKey("layout_mode")
        val FOCUS_SLOT = intPreferencesKey("focus_slot")
        val THEME_MODE = intPreferencesKey("theme_mode")
        val PHOTO_DIR_URI = stringPreferencesKey("photo_dir_uri")
        val VIDEO_DIR_URI = stringPreferencesKey("video_dir_uri")
        val DEVICE_CONFIGS = stringPreferencesKey("device_configs")
        val OSD_NAME = booleanPreferencesKey("osd_name")
        val OSD_CLOCK = booleanPreferencesKey("osd_clock")
        val OSD_SPEED = booleanPreferencesKey("osd_speed")
        val WARN_VOLUME = intPreferencesKey("warn_volume")
        val SINGLE_BITRATE_QUALITY = intPreferencesKey("single_bitrate_quality")
        val COMPOSITE_BITRATE_QUALITY = intPreferencesKey("composite_bitrate_quality")
        val LOOP_ENABLED = booleanPreferencesKey("loop_enabled")
        val LOOP_SEGMENT_MIN = intPreferencesKey("loop_segment_min")
        val LOOP_KEEP_FREE_MB = intPreferencesKey("loop_keep_free_mb")
        val GPS_ENABLED = booleanPreferencesKey("gps_enabled")
    }

    val settings: Flow<AppSettings> = context.uvcDataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            layoutMode = p[Keys.LAYOUT_MODE]?.let {
                runCatching { AppSettings.LayoutMode.entries[it] }.getOrNull()
            } ?: defaults.layoutMode,
            focusSlot = p[Keys.FOCUS_SLOT] ?: defaults.focusSlot,
            themeMode = p[Keys.THEME_MODE]?.let {
                runCatching { AppSettings.ThemeMode.entries[it] }.getOrNull()
            } ?: defaults.themeMode,
            photoDirUri = p[Keys.PHOTO_DIR_URI] ?: defaults.photoDirUri,
            videoDirUri = p[Keys.VIDEO_DIR_URI] ?: defaults.videoDirUri,
            osdName = p[Keys.OSD_NAME] ?: defaults.osdName,
            osdClock = p[Keys.OSD_CLOCK] ?: defaults.osdClock,
            osdSpeed = p[Keys.OSD_SPEED] ?: defaults.osdSpeed,
            warnVolume = (p[Keys.WARN_VOLUME] ?: defaults.warnVolume).coerceIn(0, 100),
            singleBitrateQuality = (p[Keys.SINGLE_BITRATE_QUALITY]
                ?: defaults.singleBitrateQuality).coerceIn(0, 4),
            compositeBitrateQuality = (p[Keys.COMPOSITE_BITRATE_QUALITY]
                ?: defaults.compositeBitrateQuality).coerceIn(0, 4),
            loopEnabled = p[Keys.LOOP_ENABLED] ?: defaults.loopEnabled,
            loopSegmentMinutes = (p[Keys.LOOP_SEGMENT_MIN]
                ?: defaults.loopSegmentMinutes).coerceIn(1, 60),
            loopKeepFreeMb = (p[Keys.LOOP_KEEP_FREE_MB]
                ?: defaults.loopKeepFreeMb).coerceIn(100, 50 * 1024),
            gpsEnabled = p[Keys.GPS_ENABLED] ?: defaults.gpsEnabled
        )
    }

    /** 全部摄像头配置（key=稳定标识） */
    val deviceConfigs: Flow<Map<String, DeviceConfig>> =
        context.uvcDataStore.data.map { p ->
            val raw = p[Keys.DEVICE_CONFIGS] ?: return@map emptyMap()
            runCatching {
                val root = JSONObject(raw)
                val out = LinkedHashMap<String, DeviceConfig>()
                root.keys().forEach { key ->
                    out[key] = DeviceConfig.fromJson(key, root.getJSONObject(key))
                }
                out
            }.getOrDefault(emptyMap())
        }

    suspend fun setLayoutMode(mode: AppSettings.LayoutMode) =
        edit { it[Keys.LAYOUT_MODE] = mode.ordinal }

    suspend fun setFocusSlot(slot: Int) =
        edit { it[Keys.FOCUS_SLOT] = slot.coerceAtLeast(0) }

    suspend fun setThemeMode(mode: AppSettings.ThemeMode) =
        edit { it[Keys.THEME_MODE] = mode.ordinal }

    suspend fun setPhotoDirUri(uri: String) =
        edit { it[Keys.PHOTO_DIR_URI] = uri }

    suspend fun setVideoDirUri(uri: String) =
        edit { it[Keys.VIDEO_DIR_URI] = uri }

    suspend fun setOsdName(v: Boolean) = edit { it[Keys.OSD_NAME] = v }
    suspend fun setOsdClock(v: Boolean) = edit { it[Keys.OSD_CLOCK] = v }
    suspend fun setOsdSpeed(v: Boolean) = edit { it[Keys.OSD_SPEED] = v }
    suspend fun setWarnVolume(v: Int) = edit { it[Keys.WARN_VOLUME] = v.coerceIn(0, 100) }
    suspend fun setSingleBitrateQuality(v: Int) =
        edit { it[Keys.SINGLE_BITRATE_QUALITY] = v.coerceIn(0, 4) }
    suspend fun setCompositeBitrateQuality(v: Int) =
        edit { it[Keys.COMPOSITE_BITRATE_QUALITY] = v.coerceIn(0, 4) }
    suspend fun setLoopEnabled(v: Boolean) = edit { it[Keys.LOOP_ENABLED] = v }
    suspend fun setLoopSegmentMinutes(v: Int) =
        edit { it[Keys.LOOP_SEGMENT_MIN] = v.coerceIn(1, 60) }
    suspend fun setLoopKeepFreeMb(v: Int) =
        edit { it[Keys.LOOP_KEEP_FREE_MB] = v.coerceIn(100, 50 * 1024) }
    suspend fun setGpsEnabled(v: Boolean) = edit { it[Keys.GPS_ENABLED] = v }

    /** 新增 / 更新单个摄像头配置 */
    suspend fun saveDeviceConfig(cfg: DeviceConfig) {
        edit { p ->
            val map = decodeConfigs(p[Keys.DEVICE_CONFIGS])
            map[cfg.key] = cfg
            p[Keys.DEVICE_CONFIGS] = encodeConfigs(map)
        }
    }

    /** 批量保存（用于拖动 / 重排顺序） */
    suspend fun saveAllDeviceConfigs(configs: Map<String, DeviceConfig>) {
        edit { p ->
            val map = LinkedHashMap(configs)
            p[Keys.DEVICE_CONFIGS] = encodeConfigs(map)
        }
    }

    /** 删除某摄像头配置（设备永久不再使用时） */
    suspend fun deleteDeviceConfig(key: String) {
        edit { p ->
            val map = decodeConfigs(p[Keys.DEVICE_CONFIGS])
            if (map.remove(key) != null) p[Keys.DEVICE_CONFIGS] = encodeConfigs(map)
        }
    }

    private fun decodeConfigs(raw: String?): LinkedHashMap<String, DeviceConfig> {
        val out = LinkedHashMap<String, DeviceConfig>()
        if (raw.isNullOrBlank()) return out
        runCatching {
            val root = JSONObject(raw)
            root.keys().forEach { key ->
                out[key] = DeviceConfig.fromJson(key, root.getJSONObject(key))
            }
        }
        return out
    }

    private fun encodeConfigs(map: Map<String, DeviceConfig>): String {
        val root = JSONObject()
        map.forEach { (k, v) -> root.put(k, v.toJson()) }
        return root.toString()
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.uvcDataStore.edit(block)
    }
}
