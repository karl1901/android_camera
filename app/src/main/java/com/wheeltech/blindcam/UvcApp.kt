package com.wheeltech.blindcam

import android.app.Application
import com.wheeltech.blindcam.data.SettingsRepository
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.recorder.CircularRecorder
import com.wheeltech.blindcam.util.Logger

/**
 * Application：极简手工依赖容器（不引入 Hilt/Koin，控制车机包体与复杂度）。
 * 全局单例：
 * - cameraManager：UVC 设备/帧管理
 * - settingsRepository：DataStore 设置
 * - recorder：循环录像
 */
class UvcApp : Application() {

    lateinit var cameraManager: UvcCameraManager
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var recorder: CircularRecorder
        private set

    override fun onCreate() {
        super.onCreate()
        Logger.i("app", "UvcApp onCreate")
        settingsRepository = SettingsRepository(this)
        cameraManager = UvcCameraManager.get(this).also { it.init() }
        recorder = CircularRecorder(this)
    }
}
