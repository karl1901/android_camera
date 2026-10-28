package com.wheeltech.blindcam.ui.common

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings

/**
 * 所有页面统一主题入口：
 * - onCreate 前按 UvcApp.currentThemeMode 固定 style（浅色/深色/科技蓝不能只靠
 *   AppCompatDelegate，因为固定深色与科技蓝同为 night，不会触发系统重建）；
 * - onResume 时检测主题是否在设置页被改动，改动则重建当前页面。
 *
 * 这样“设置页切主题”会作用到 Main/Gallery/Playback/看图/播放器，避免返回后仍是旧主题。
 */
abstract class ThemedActivity : AppCompatActivity() {

    private var appliedTheme: AppSettings.ThemeMode? = null

    protected val uvcApp: UvcApp get() = application as UvcApp

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedTheme = uvcApp.currentThemeMode
        setTheme(uvcApp.themeResId(uvcApp.currentThemeMode))
        super.onCreate(savedInstanceState)
        // 进程可能先以竖屏/旧方向启动；所有页面统一刷新当前方向尺寸，
        // 保证 uiScale / 相册列数 / 设置最大宽度用对横屏尺寸。
        com.wheeltech.blindcam.domain.DevicePerformance.updateDisplay(this)
    }

    /**
     * 在子类 onResume 的最开始调用。
     * @return true 表示已触发 recreate，子类应立即 return，避免继续刷新旧视图。
     */
    protected fun recreateIfThemeChanged(): Boolean {
        val current = uvcApp.currentThemeMode
        if (appliedTheme == current) return false
        appliedTheme = current
        recreate()
        return true
    }
}
