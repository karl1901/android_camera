package com.wheeltech.blindcam

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.data.SettingsRepository
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.recorder.CaptureManager
import com.wheeltech.blindcam.recorder.MediaSaver
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Application：极简手工依赖容器（不引入 Hilt/Koin，控制车机包体与复杂度）。
 * 全局单例：
 * - cameraManager：UVC 设备/帧管理
 * - settingsRepository：DataStore 设置
 * - mediaSaver：拍照/录像落盘（SAF 自定义目录）
 * - captureManager：拍照 / 录像（注册为帧监听，后台前台都可用）
 */
class UvcApp : Application() {

    lateinit var cameraManager: UvcCameraManager
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var mediaSaver: MediaSaver
        private set
    lateinit var captureManager: CaptureManager
        private set

    override fun onCreate() {
        super.onCreate()
        Logger.i("app", "UvcApp onCreate")
        settingsRepository = SettingsRepository(this)
        // 启动即应用主题（必须在 Activity 创建前设置夜间模式）
        runCatching {
            val s = runBlocking { settingsRepository.settings.first() }
            applyTheme(s.themeMode)
        }
        mediaSaver = MediaSaver(this)
        cameraManager = UvcCameraManager.get(this).also { it.init() }
        captureManager = CaptureManager(this, mediaSaver)
        // 拍照/录像作为常驻帧监听，生命周期挂在 Application（含前台服务后台运行）
        cameraManager.addFrameListener(captureManager)
    }

    /** 应用主题模式：跟随系统 / 深色 / 浅色 */
    fun applyTheme(mode: AppSettings.ThemeMode) {
        val nightMode = when (mode) {
            AppSettings.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppSettings.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppSettings.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
