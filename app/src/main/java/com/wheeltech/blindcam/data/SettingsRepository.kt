package com.wheeltech.blindcam.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 应用级 DataStore（单例，懒加载） */
private val Context.uvcDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "uvc_settings"
)

/**
 * 设置仓库：DataStore 偏好设置持久化
 * 所有读取走 [settings] Flow，写入为 suspend 函数，默认值集中在 [AppSettings]
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val ENABLE_CAMERA_ON_START = booleanPreferencesKey("enable_camera_on_start")
        val CLIP_DURATION_SEC = intPreferencesKey("clip_duration_sec")
        val MAX_STORAGE_MB = intPreferencesKey("max_storage_mb")
        val MIRROR_LEFT = booleanPreferencesKey("mirror_left")
        val MIRROR_RIGHT = booleanPreferencesKey("mirror_right")
        val FLIP_LEFT = booleanPreferencesKey("flip_left")
        val FLIP_RIGHT = booleanPreferencesKey("flip_right")
        val LAYOUT_MODE = intPreferencesKey("layout_mode")
    }

    val settings: Flow<AppSettings> = context.uvcDataStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            enableCameraOnStart = p[Keys.ENABLE_CAMERA_ON_START] ?: defaults.enableCameraOnStart,
            clipDurationSec = p[Keys.CLIP_DURATION_SEC] ?: defaults.clipDurationSec,
            maxStorageMb = p[Keys.MAX_STORAGE_MB] ?: defaults.maxStorageMb,
            mirrorLeft = p[Keys.MIRROR_LEFT] ?: defaults.mirrorLeft,
            mirrorRight = p[Keys.MIRROR_RIGHT] ?: defaults.mirrorRight,
            flipLeft = p[Keys.FLIP_LEFT] ?: defaults.flipLeft,
            flipRight = p[Keys.FLIP_RIGHT] ?: defaults.flipRight,
            layoutMode = p[Keys.LAYOUT_MODE]?.let {
                runCatching { AppSettings.LayoutMode.entries[it] }.getOrNull()
            } ?: defaults.layoutMode
        )
    }

    suspend fun setEnableCameraOnStart(value: Boolean) =
        edit { it[Keys.ENABLE_CAMERA_ON_START] = value }

    suspend fun setClipDurationSec(seconds: Int) =
        edit { it[Keys.CLIP_DURATION_SEC] = seconds.coerceIn(10, 600) }

    suspend fun setMaxStorageMb(mb: Int) =
        edit { it[Keys.MAX_STORAGE_MB] = mb.coerceIn(256, 32 * 1024) }

    suspend fun setMirror(slot: Int, value: Boolean) = edit {
        when (slot) {
            0 -> it[Keys.MIRROR_LEFT] = value
            1 -> it[Keys.MIRROR_RIGHT] = value
        }
    }

    suspend fun setFlip(slot: Int, value: Boolean) = edit {
        when (slot) {
            0 -> it[Keys.FLIP_LEFT] = value
            1 -> it[Keys.FLIP_RIGHT] = value
        }
    }

    suspend fun setLayoutMode(mode: AppSettings.LayoutMode) =
        edit { it[Keys.LAYOUT_MODE] = mode.ordinal }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.uvcDataStore.edit(block)
    }
}
