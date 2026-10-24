package com.wheeltech.blindcam

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.data.SettingsRepository
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.domain.WarningManager
import com.wheeltech.blindcam.recorder.CaptureManager
import com.wheeltech.blindcam.recorder.MediaSaver
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    lateinit var supervisor: CameraSupervisor
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var mediaSaver: MediaSaver
        private set
    lateinit var captureManager: CaptureManager
        private set
    lateinit var warningManager: WarningManager
        private set

    /** 应用级协程范围（常驻，随进程存活） */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 全局设置热状态（供预警等 Application 级组件直接读取） */
    val settingsState by lazy {
        settingsRepository.settings.stateIn(
            appScope, SharingStarted.Eagerly, AppSettings()
        )
    }

    /** 当前主题模式（Activity setContentView 前据此 setTheme） */
    @Volatile
    var currentThemeMode: AppSettings.ThemeMode = AppSettings.ThemeMode.SYSTEM
        private set

    override fun onCreate() {
        super.onCreate()
        Logger.init(this)
        Logger.i("app", "UvcApp onCreate")
        settingsRepository = SettingsRepository(this)
        // 启动即应用主题（必须在 Activity 创建前设置夜间模式）
        runCatching {
            val s = runBlocking { settingsRepository.settings.first() }
            currentThemeMode = s.themeMode
            applyTheme(s.themeMode)
        }
        mediaSaver = MediaSaver(this)
        cameraManager = UvcCameraManager.get(this).also { it.init() }
        captureManager = CaptureManager(this, mediaSaver)
        // 拍照/录像作为常驻帧监听，生命周期挂在 Application（含前台服务后台运行）
        cameraManager.addFrameListener(captureManager)
        // 常驻摄像头状态机：自动授权/多路取流/热插拔/重启恢复，前台后台都运行
        supervisor = CameraSupervisor(this, cameraManager, settingsRepository)
        supervisor.start()
        // 盲区预警：运动检测 + 红框 + 提示音，常驻帧监听
        warningManager = WarningManager(this, supervisor.slots, settingsState)
        cameraManager.addFrameListener(warningManager)
    }

    /** 应用主题模式：跟随系统 / 深色 / 浅色 / 深色+蓝色科技风 */
    fun applyTheme(mode: AppSettings.ThemeMode) {
        currentThemeMode = mode
        val nightMode = when (mode) {
            AppSettings.ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppSettings.ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            AppSettings.ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            // 科技蓝是固定深色风格
            AppSettings.ThemeMode.TECH_BLUE -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    /** Activity 在 setContentView 前应使用的主题资源 */
    fun themeResId(mode: AppSettings.ThemeMode): Int = when (mode) {
        AppSettings.ThemeMode.SYSTEM -> R.style.Theme_UvcBlindCam
        AppSettings.ThemeMode.LIGHT -> R.style.Theme_UvcBlindCam_Light
        AppSettings.ThemeMode.DARK -> R.style.Theme_UvcBlindCam_Dark
        AppSettings.ThemeMode.TECH_BLUE -> R.style.Theme_UvcBlindCam_Tech
    }
}
