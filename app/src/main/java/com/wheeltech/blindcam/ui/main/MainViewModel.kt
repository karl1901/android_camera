package com.wheeltech.blindcam.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.usb.UsbDeviceInfo
import com.wheeltech.blindcam.usb.UsbMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：只做 UI 状态聚合，不碰 JNI / USB 句柄。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val statusText: String = "USB 初始化中…",
        val devices: List<UsbDeviceInfo> = emptyList(),
        val layoutMode: AppSettings.LayoutMode = AppSettings.LayoutMode.DUAL,
        val logLine: String = ""
    )

    private val application = app as UvcApp
    private val manager = application.cameraManager

    private val logLine = MutableStateFlow("")
    private val layoutMode = MutableStateFlow(AppSettings.LayoutMode.DUAL)

    val uiState: StateFlow<UiState> = combine(
        manager.devices,
        layoutMode,
        logLine
    ) { devices, layout, log ->
        UiState(
            statusText = if (devices.isEmpty()) {
                "未检测到 USB 设备（共 ${devices.size} 路）"
            } else {
                "检测到 ${devices.size} 个 USB 设备"
            },
            devices = devices,
            layoutMode = layout,
            logLine = log
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2000), UiState())

    /** 设置流（设置页直接使用，主界面这里先取布局模式） */
    init {
        viewModelScope.launch {
            application.settingsRepository.settings.collect { s ->
                layoutMode.value = s.layoutMode
            }
        }
        // 透传热插拔事件到日志
        viewModelScope.launch {
            manager.usbEvents.collect { event ->
                when (event) {
                    is UsbMonitor.UsbEvent.Attached ->
                        log("设备插入：${event.device.displayText}")
                    is UsbMonitor.UsbEvent.Detached ->
                        log("设备拔出：deviceId=${event.deviceId}")
                }
            }
        }
    }

    fun refreshDevices() {
        manager.refreshDevices()
        log("已重新枚举 USB 设备")
    }

    /** 申请列表中第一个未授权设备的权限（阶段1简化入口；阶段2按槽位绑定） */
    fun requestFirstPermission() {
        val target = manager.devices.value.firstOrNull { !it.hasPermission }
        if (target == null) {
            log("没有需要授权的设备")
            return
        }
        manager.requestPermission(target) { _, granted ->
            log(if (granted) "USB 权限已授予：${target.displayText}" else "USB 权限被拒绝")
        }
    }

    fun requestPermission(device: UsbDeviceInfo) {
        manager.requestPermission(device) { _, granted ->
            log(if (granted) "USB 权限已授予：${device.displayText}" else "USB 权限被拒绝")
        }
    }

    /** 单/双屏循环切换：DUAL → LEFT_ONLY → RIGHT_ONLY → DUAL */
    fun cycleLayoutMode() {
        viewModelScope.launch {
            val next = when (layoutMode.value) {
                AppSettings.LayoutMode.DUAL -> AppSettings.LayoutMode.LEFT_ONLY
                AppSettings.LayoutMode.LEFT_ONLY -> AppSettings.LayoutMode.RIGHT_ONLY
                AppSettings.LayoutMode.RIGHT_ONLY -> AppSettings.LayoutMode.DUAL
            }
            application.settingsRepository.setLayoutMode(next)
            log("布局切换：$next")
        }
    }

    private fun log(text: String) {
        logLine.update { text }
    }
}
