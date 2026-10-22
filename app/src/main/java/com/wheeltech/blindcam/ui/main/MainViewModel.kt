package com.wheeltech.blindcam.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.domain.StreamProfile
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.usb.UsbDeviceInfo
import com.wheeltech.blindcam.usb.UsbMonitor
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appContainer: UvcApp = app as UvcApp
    private val cameraManager: UvcCameraManager = appContainer.cameraManager
    private val captureManager = appContainer.captureManager
    private val settingsRepository = appContainer.settingsRepository

    data class SlotState(
        val slot: Int,
        val device: UsbDeviceInfo? = null,
        val capabilities: List<StreamProfile> = emptyList(),
        val activeProfile: StreamProfile? = null,
        val streaming: Boolean = false,
        val recording: Boolean = false
    )

    data class UiState(
        val slots: List<SlotState> = List(UvcCameraManager.SLOT_COUNT) { SlotState(it) },
        val unassignedDevices: List<UsbDeviceInfo> = emptyList(),
        val layoutMode: AppSettings.LayoutMode = AppSettings.LayoutMode.DUAL,
        val statusText: String = "初始化中…"
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 一次性提示（Toast / 结果） */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val deviceOpMutex = Mutex()
    private var startTriggered = false

    /** 开始预览（USB 设备打开/协商由 Domain 完成，重试由重试策略保障） */
    fun ensurePreviewing() {
        if (startTriggered) return
        startTriggered = true
        Logger.i(TAG, "ensurePreviewing: initial")
        viewModelScope.launch {
            deviceOpMutex.withLock {
                if (cameraManager.devices.value.none { it.hasPermission }) {
                    _uiState.update { it.copy(statusText = "未检测到授权设备") }
                }
                // 自动分配（含未授权提示）+ 开流，统一在 startStreamWithRetry
                startStreamWithRetry()
            }
        }
        observeUsbEvents()
    }

    /**
     * 从后台返回 / onResume 时调用：检查各槽位，缺流的自动恢复。
     * 设备在后台时 native 流可能被系统挂起，回到前台需重新协商。
     */
    fun resumePreviewing() {
        viewModelScope.launch {
            deviceOpMutex.withLock { startStreamWithRetry() }
        }
    }

    /** 主动刷新 USB 设备列表 */
    fun refreshDevices() {
        cameraManager.refreshDevices()
        viewModelScope.launch {
            deviceOpMutex.withLock { startStreamWithRetry() }
        }
    }

    /** 收集热插拔事件，重新同步 UI；新插入设备自动请求权限并分配 */
    private fun observeUsbEvents() {
        viewModelScope.launch {
            cameraManager.usbEvents.collect { event ->
                Logger.i(TAG, "usb event: $event")
                when (event) {
                    is UsbMonitor.UsbEvent.Attached -> {
                        val device = event.device
                        if (!device.hasPermission) {
                            _uiState.update { it.copy(statusText = "等待授权：${device.displayText}") }
                            val granted = withContext(Dispatchers.IO) {
                                cameraManager.requestPermission(device)
                            }
                            if (!granted) {
                                syncUiState()
                                emit("未授权：${device.displayText}")
                                return@collect
                            }
                        }
                        deviceOpMutex.withLock {
                            // 自动分配到空槽位并开流（重开失败由重试策略兜底）
                            if (cameraManager.findDeviceSlot(device.deviceId) < 0) {
                                val freeSlot = findFreeSlot()
                                if (freeSlot >= 0) {
                                    assignDeviceToSlot(freeSlot, device)
                                }
                            }
                            startStreamWithRetry()
                        }
                    }
                    is UsbMonitor.UsbEvent.Detached -> {
                        deviceOpMutex.withLock { syncUiState() }
                    }
                }
            }
        }
    }

    private fun findFreeSlot(): Int {
        for (slot in 0 until UvcCameraManager.SLOT_COUNT) {
            if (cameraManager.slotDevice(slot) == null) return slot
        }
        return -1
    }

    /**
     * 把设备分配到指定槽位（若该槽位已被占用则先停止释放）。
     * 若该设备已在其它槽位，会先从原槽位移除（实现左右自由调配）。
     */
    fun assignDevice(slot: Int, device: UsbDeviceInfo) {
        viewModelScope.launch {
            deviceOpMutex.withLock {
                var target = device
                if (!target.hasPermission) {
                    val granted = withContext(Dispatchers.IO) {
                        cameraManager.requestPermission(target)
                    }
                    if (!granted) {
                        emit("未授权：${target.displayText}")
                        syncUiState()
                        return@withLock
                    }
                    // 授权后刷新设备对象（hasPermission 状态更新）
                    target = cameraManager.devices.value
                        .firstOrNull { it.deviceId == device.deviceId } ?: device
                }
                assignDeviceToSlot(slot, target)
                startStreamWithRetry()
            }
        }
    }

    private fun assignDeviceToSlot(slot: Int, device: UsbDeviceInfo) {
        cameraManager.assignDeviceToSlot(slot, device)
        val camera = cameraManager.getCamera(slot)
        Logger.i(
            TAG,
            "assign device: slot=$slot device=${device.displayText} " +
                "caps=${camera?.capabilities?.size} default=${camera?.defaultProfile}"
        )
        syncUiState()
    }

    /**
     * 切换某槽位的分辨率 / 帧率（仅可选择该摄像头能力枚举范围内的档位）。
     * 会停止当前流、用新档位重新协商、重新开流。
     */
    fun switchProfile(slot: Int, profile: StreamProfile) {
        viewModelScope.launch {
            deviceOpMutex.withLock {
                cameraManager.switchProfile(slot, profile)
                startStreamWithRetry(slotOnly = slot)
            }
        }
    }

    /** 拍照：保存该槽位当前帧 JPEG */
    fun takePhoto(slot: Int) {
        val slotState = _uiState.value.slots[slot]
        if (slotState.device == null || !slotState.streaming) {
            emit("该通道暂无画面，无法拍照")
            return
        }
        viewModelScope.launch {
            val dir = settingsRepository.settings.first().photoDirUri
            captureManager.takePhoto(slot, dir) { result ->
                result.onSuccess { emit("照片已保存：$it") }
                    .onFailure { emit("拍照失败：${it.message}") }
            }
        }
    }

    /** 开始 / 停止录像（分辨率帧率取当前档位） */
    fun toggleRecording(slot: Int) {
        if (captureManager.isRecording(slot)) {
            captureManager.stopRecording(slot) { result ->
                result.onSuccess { emit("录像已保存：$it") }
                    .onFailure { emit("录像失败：${it.message}") }
                updateRecordingFlags()
            }
            updateRecordingFlags()
            return
        }
        val slotState = _uiState.value.slots[slot]
        val profile = slotState.activeProfile
        if (slotState.device == null || !slotState.streaming || profile == null) {
            emit("该通道暂无画面，无法录像")
            return
        }
        viewModelScope.launch {
            val dir = settingsRepository.settings.first().videoDirUri
            val ok = captureManager.startRecording(
                slot, profile.width, profile.height, profile.fps, dir
            )
            if (!ok) emit("录像启动失败")
            updateRecordingFlags()
        }
    }

    private fun updateRecordingFlags() {
        _uiState.update { st ->
            st.copy(
                slots = st.slots.map { s ->
                    s.copy(recording = captureManager.isRecording(s.slot))
                }
            )
        }
    }

    /** 循环布局模式：双屏 → 仅左 → 仅右 → 双屏（纯显示控制，不停取流） */
    fun cycleLayoutMode() {
        val next = when (_uiState.value.layoutMode) {
            AppSettings.LayoutMode.DUAL -> AppSettings.LayoutMode.LEFT_ONLY
            AppSettings.LayoutMode.LEFT_ONLY -> AppSettings.LayoutMode.RIGHT_ONLY
            AppSettings.LayoutMode.RIGHT_ONLY -> AppSettings.LayoutMode.DUAL
        }
        applyLayoutMode(next)
    }

    fun applyLayoutMode(mode: AppSettings.LayoutMode) {
        viewModelScope.launch {
            settingsRepository.setLayoutMode(mode)
            _uiState.update { it.copy(layoutMode = mode) }
        }
    }

    /**
     * 确保所有已分配槽位都在取流；缺流的按顺序补齐。
     * 设备打开/格式协商（失败可能因设备忙/时序）统一加重试，提升冷启动成功率。
     * @param slotOnly 仅处理指定槽位（切换分辨率时用）；null=全部
     */
    private suspend fun startStreamWithRetry(
        maxRetry: Int = 3,
        slotOnly: Int? = null
    ) {
        // 全量恢复时：已授权但未分配的设备自动补到空槽位（冷启动 / 刷新 / 热插拔共用）
        if (slotOnly == null) {
            cameraManager.devices.value
                .filter { it.hasPermission && cameraManager.findDeviceSlot(it.deviceId) < 0 }
                .sortedBy { it.deviceId }
                .forEach { device ->
                    val free = findFreeSlot()
                    if (free >= 0) assignDeviceToSlot(free, device)
                }
        }
        val slots = if (slotOnly != null) listOf(slotOnly)
        else (0 until UvcCameraManager.SLOT_COUNT).toList()
        for (slot in slots) {
            val device = cameraManager.slotDevice(slot) ?: continue
            var camera = cameraManager.getCamera(slot)
            if (camera == null) {
                val open = cameraManager.openCamera(slot, device)
                if (open is UvcCameraManager.OpenResult.Failure) {
                    Logger.w(TAG, "openCamera failed slot=$slot reason=${open.reason}")
                    _uiState.update { it.copy(statusText = "打开失败：${device.displayText}") }
                    continue
                }
                camera = cameraManager.getCamera(slot)
            }
            if (camera != null && camera.streaming) {
                Logger.i(TAG, "slot=$slot already streaming, skip")
                continue
            }
            val profile = camera?.currentProfile ?: camera?.defaultProfile
            if (profile == null) {
                Logger.w(TAG, "slot=$slot no profile available")
                continue
            }
            var ok = false
            var lastError = "协商失败"
            repeat(maxRetry) { attempt ->
                val started = cameraManager.startStream(slot, profile)
                if (started) {
                    ok = true
                    Logger.i(TAG, "slot=$slot stream started (attempt=${attempt + 1}) profile=$profile")
                    return@repeat
                }
                lastError = "取流协商失败"
                Logger.w(TAG, "slot=$slot start attempt=${attempt + 1} failed")
                cameraManager.stopStream(slot)
                cameraManager.closeCamera(slot)
                kotlinx.coroutines.delay(300L * (attempt + 1))
                cameraManager.openCamera(slot, device)
            }
            if (!ok) {
                _uiState.update {
                    it.copy(statusText = "取流失败：${device.displayText}（$lastError）")
                }
            }
        }
        syncUiState()
    }

    private fun emit(msg: String) {
        _events.tryEmit(msg)
        _uiState.update { it.copy(statusText = msg) }
    }

    /** 从 Domain 层同步最新状态到 UI（含活跃档位与取流状态） */
    private fun syncUiState() {
        val slotStates = (0 until UvcCameraManager.SLOT_COUNT).map { slot ->
            val camera = cameraManager.getCamera(slot)
            SlotState(
                slot = slot,
                device = cameraManager.slotDevice(slot),
                capabilities = camera?.capabilities ?: emptyList(),
                activeProfile = camera?.currentProfile,
                streaming = camera?.streaming ?: false,
                recording = captureManager.isRecording(slot)
            )
        }
        val assignedDeviceIds =
            (0 until UvcCameraManager.SLOT_COUNT)
                .mapNotNull { cameraManager.slotDevice(it)?.deviceId }.toSet()
        val unassigned = cameraManager.devices.value.filter { it.deviceId !in assignedDeviceIds }
        val streamingCount = slotStates.count { it.streaming }
        val status = when {
            cameraManager.devices.value.isEmpty() -> "未检测到 USB 设备"
            streamingCount == 0 -> "检测到 ${cameraManager.devices.value.size} 个设备"
            else -> "预览中 · $streamingCount/${UvcCameraManager.SLOT_COUNT} 路"
        }
        _uiState.update {
            it.copy(
                slots = slotStates,
                unassignedDevices = unassigned,
                statusText = status
            )
        }
    }

    /** 主动拉取一次状态（Activity 恢复时用） */
    fun refreshState() {
        syncUiState()
    }

    companion object {
        private const val TAG = "view-model"
    }
}
