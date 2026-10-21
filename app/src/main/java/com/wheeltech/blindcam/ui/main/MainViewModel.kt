package com.wheeltech.blindcam.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.domain.UvcCameraManager
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
        // 透传热插拔事件到日志；插入后自动发起权限弹窗
        viewModelScope.launch {
            manager.usbEvents.collect { event ->
                when (event) {
                    is UsbMonitor.UsbEvent.Attached -> {
                        log("设备插入：${event.device.displayText}")
                        // 自动申请：未授权则立即弹系统权限框（车机场景即插即用），
                        // 授权后由 onPermissionResult 自动开流
                        if (!event.device.hasPermission) {
                            log("正在请求 USB 权限：${event.device.displayText}")
                            manager.requestPermission(event.device, ::onPermissionResult)
                        }
                    }
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

    /**
     * 统一自检并确保预览（app 启动 / CAMERA 授予后调用）：
     * - 已授权且未打开的设备：自动打开并开流
     * - 已打开但未取流：启动取流
     * - 未授权设备：发起 USB 权限请求
     */
    fun ensurePreviewing() {
        val devices = manager.devices.value
        for (device in devices) {
            val occupied = findDeviceSlot(device.deviceId)
            if (occupied >= 0) {
                if (manager.getCamera(occupied)?.streaming != true) {
                    startStream(occupied)
                }
            } else if (device.hasPermission) {
                val freeSlot = firstFreeSlot()
                if (freeSlot >= 0) openAndStream(freeSlot, device)
            } else {
                log("正在请求 USB 权限：${device.displayText}")
                manager.requestPermission(device, ::onPermissionResult)
            }
        }
    }

    /** 申请列表中第一个未授权设备的权限（阶段1简化入口；阶段2按槽位绑定） */
    fun requestFirstPermission() {
        val target = manager.devices.value.firstOrNull { !it.hasPermission }
        if (target == null) {
            log("没有需要授权的设备")
            return
        }
        log("正在请求 USB 权限：${target.displayText}")
        manager.requestPermission(target, ::onPermissionResult)
    }

    fun requestPermission(device: UsbDeviceInfo) {
        log("正在请求 USB 权限：${device.displayText}")
        manager.requestPermission(device, ::onPermissionResult)
    }

    /**
     * USB 权限结果统一处理：
     * - 授予：自动分配到第一个空闲槽位，打开设备并启动取流
     * - 拒绝：仅记录日志
     */
    private fun onPermissionResult(device: UsbDeviceInfo, granted: Boolean) {
        if (!granted) {
            log("USB 权限被拒绝：${device.displayText}")
            return
        }
        log("USB 权限已授予：${device.displayText}")

        // 该设备已在某槽位打开则不重复打开
        val existingSlot = findDeviceSlot(device.deviceId)
        if (existingSlot >= 0) {
            // 确保该路在取流
            if (manager.getCamera(existingSlot)?.streaming != true) {
                startStream(existingSlot)
            }
            return
        }

        val freeSlot = firstFreeSlot()
        if (freeSlot < 0) {
            log("没有空闲槽位，无法打开：${device.displayText}")
            return
        }
        openAndStream(freeSlot, device)
    }

    /** 打开设备到指定槽位并启动取流 */
    private fun openAndStream(slot: Int, device: UsbDeviceInfo) {
        val result = manager.openCamera(slot, device)
        when (result) {
            is UvcCameraManager.OpenResult.Success -> startStream(slot)
            is UvcCameraManager.OpenResult.Failure ->
                log("打开失败(slot=$slot)：${result.reason}")
        }
    }

    private fun startStream(slot: Int) {
        val ok = manager.startStream(
            slot,
            UvcCameraManager.DEFAULT_WIDTH,
            UvcCameraManager.DEFAULT_HEIGHT,
            UvcCameraManager.DEFAULT_FPS
        )
        log(if (ok) "预览已开启：slot=$slot" else "取流失败：slot=$slot")
    }

    /** 第一个空闲槽位（0=左 1=右），无空闲返回 -1 */
    private fun firstFreeSlot(): Int {
        for (slot in 0 until UvcCameraManager.SLOT_COUNT) {
            if (manager.getCamera(slot) == null) return slot
        }
        return -1
    }

    /** 查找设备已占用的槽位，未找到返回 -1 */
    private fun findDeviceSlot(deviceId: Int): Int {
        for (slot in 0 until UvcCameraManager.SLOT_COUNT) {
            if (manager.getCamera(slot)?.info?.deviceId == deviceId) return slot
        }
        return -1
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
