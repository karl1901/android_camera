package com.wheeltech.blindcam.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.domain.StreamProfile
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.recorder.CompositeRecorder
import com.wheeltech.blindcam.recorder.OsdRenderer
import com.wheeltech.blindcam.usb.UsbDeviceInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel：薄门面。
 * 多路设备 / 授权 / 取流 / 热插拔的真实状态机在 [CameraSupervisor]（常驻、跨页面），
 * 这里只负责把状态透传给 UI、转发用户操作、拍照/录像与设置写入。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appContainer: UvcApp = app as UvcApp
    private val cameraManager: UvcCameraManager = appContainer.cameraManager
    private val supervisor: CameraSupervisor = appContainer.supervisor
    private val captureManager = appContainer.captureManager
    private val settingsRepository = appContainer.settingsRepository

    /** 各槽位运行状态（来自常驻状态机） */
    val slots: StateFlow<List<CameraSupervisor.SlotRuntime>> = supervisor.slots

    /** 全局设置 */
    val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings()).also { sf ->
        viewModelScope.launch {
            settingsRepository.settings.collect { sf.value = it }
        }
    }

    /** 设备列表（底部栏 / 选择对话框） */
    val devices: StateFlow<List<UsbDeviceInfo>> = cameraManager.devices

    /** 正在录像的槽位 */
    private val _recordingSlots = MutableStateFlow(emptySet<Int>())
    val recordingSlots: StateFlow<Set<Int>> = _recordingSlots.asStateFlow()

    /** 总录像（多路合成）是否进行中 */
    private val _compositeRecording = MutableStateFlow(false)
    val compositeRecording: StateFlow<Boolean> = _compositeRecording.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        // 状态机消息转 Toast
        viewModelScope.launch {
            supervisor.messages.collect { _events.tryEmit(it) }
        }
    }

    // ------------------------------------------------------------------
    // 授权
    // ------------------------------------------------------------------
    fun grantAll() = supervisor.grantAll()
    fun grant(deviceId: Int) = supervisor.grant(deviceId)
    fun refresh() = supervisor.refresh()
    fun retrySlot(slot: Int) = supervisor.retrySlot(slot)

    // ------------------------------------------------------------------
    // 设备 / 槽位操作
    // ------------------------------------------------------------------
    fun assignDevice(slot: Int, device: UsbDeviceInfo) =
        supervisor.assignDeviceToSlot(device, slot)

    fun setProfile(slot: Int, profile: StreamProfile) =
        supervisor.setProfile(slot, profile)

    fun setMirror(slot: Int, mirror: Boolean) {
        supervisor.setMirror(slot, mirror)
    }

    fun rotate(slot: Int) = supervisor.rotate(slot)

    fun rename(slot: Int, name: String) = supervisor.rename(slot, name)

    fun setWarn(slot: Int, warn: Boolean) = supervisor.setWarn(slot, warn)

    // ------------------------------------------------------------------
    // 全局设置（OSD / 预警音量）
    // ------------------------------------------------------------------
    fun setOsdName(v: Boolean) = viewModelScope.launch { settingsRepository.setOsdName(v) }
    fun setOsdClock(v: Boolean) = viewModelScope.launch { settingsRepository.setOsdClock(v) }
    fun setOsdSpeed(v: Boolean) = viewModelScope.launch { settingsRepository.setOsdSpeed(v) }
    fun setWarnVolume(v: Int) = viewModelScope.launch { settingsRepository.setWarnVolume(v) }

    // ------------------------------------------------------------------
    // 日志导出（SAF 选目录）
    // ------------------------------------------------------------------
    fun exportLogs(treeUri: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val name = "blind_log_" +
                    java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date()) + ".txt"
                val ctx = getApplication<Application>()
                val header = buildString {
                    appendLine("===== 盲区辅助 APP 运行日志 =====")
                    appendLine("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                    appendLine("品牌/型号: ${android.os.Build.BRAND} ${android.os.Build.MODEL}")
                    appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
                    runCatching {
                        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                        appendLine("应用版本: ${pi.versionName} (${pi.longVersionCode})")
                    }
                    appendLine("================================")
                }
                appContainer.mediaSaver.exportText(treeUri, name, header + com.wheeltech.blindcam.util.Logger.snapshot())
                name
            }.onSuccess { onResult("日志已导出：$it") }
                .onFailure { onResult("导出失败：${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------
    fun cycleLayout() {
        viewModelScope.launch {
            val cur = settings.value
            val visibleCount = slots.value.count { it.state != CameraSupervisor.SlotState.EMPTY }
            if (cur.layoutMode == AppSettings.LayoutMode.AUTO) {
                settingsRepository.setLayoutMode(AppSettings.LayoutMode.SINGLE)
                settingsRepository.setFocusSlot(0)
            } else {
                val focus = cur.focusSlot
                if (focus + 1 < visibleCount) {
                    settingsRepository.setFocusSlot(focus + 1)
                } else {
                    settingsRepository.setLayoutMode(AppSettings.LayoutMode.AUTO)
                }
            }
        }
    }

    fun setLayoutMode(mode: AppSettings.LayoutMode) {
        viewModelScope.launch { settingsRepository.setLayoutMode(mode) }
    }

    // ------------------------------------------------------------------
    // 拍照 / 录像
    // ------------------------------------------------------------------
    fun takePhoto(slot: Int) {
        val rt = slots.value.getOrNull(slot)
        if (rt?.state != CameraSupervisor.SlotState.STREAMING) {
            emit("该通道暂无画面，无法拍照")
            return
        }
        viewModelScope.launch {
            val dir = settingsRepository.settings.first().photoDirUri
            // 按该画框当前镜像/旋转设置输出（与预览所见一致）
            captureManager.takePhoto(
                slot, dir, rt.config?.mirror ?: false, rt.config?.rotation ?: 0
            ) { result ->
                result.onSuccess { emit("照片已保存：$it") }
                    .onFailure { emit("拍照失败：${it.message}") }
            }
        }
    }

    fun toggleRecording(slot: Int) {
        if (captureManager.isRecording(slot)) {
            captureManager.stopRecording(slot) { result ->
                result.onSuccess { emit("录像已保存：$it") }
                    .onFailure { emit("录像失败：${it.message}") }
                _recordingSlots.update { it - slot }
            }
            _recordingSlots.update { it - slot }
            return
        }
        val rt = slots.value.getOrNull(slot)
        val profile = rt?.activeProfile
        if (rt?.state != CameraSupervisor.SlotState.STREAMING || profile == null) {
            emit("该通道暂无画面，无法录像")
            return
        }
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            val cfg = rt.config
            val displayName = cfg?.name?.takeIf { it.isNotBlank() }
                ?: rt.device?.productName?.takeIf { it.isNotBlank() } ?: ""
            val osd = buildOverlay(s, displayName)
            // 按该画框当前镜像/旋转设置输出录像（旋转 90/270 时输出宽高互换），并叠加 OSD
            val ok = captureManager.startRecording(
                slot, profile.width, profile.height, profile.fps, s.videoDirUri,
                cfg?.mirror ?: false, cfg?.rotation ?: 0, osd
            )
            if (ok) _recordingSlots.update { it + slot } else emit("录像启动失败")
        }
    }

    /** 根据全局设置构造单路录像的 OSD 叠加（无任何叠加项时返回 null） */
    private fun buildOverlay(s: AppSettings, name: String): OsdRenderer.Overlay? =
        if (s.osdName || s.osdClock || s.osdSpeed) {
            OsdRenderer.Overlay(
                showName = s.osdName,
                name = name,
                showClock = s.osdClock,
                showSpeed = s.osdSpeed,
                speedText = null
            )
        } else {
            null
        }

    /** 总录像开关：录制所有画面（按当前 1/2/3/4 宫格合成） */
    fun toggleComposite() {
        if (captureManager.isCompositeRecording()) {
            captureManager.stopComposite { result ->
                result.onSuccess { emit("总录像已保存：$it") }
                    .onFailure { emit("总录像失败：${it.message}") }
                _compositeRecording.value = false
            }
            _compositeRecording.value = false
            return
        }
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            val streaming = slots.value.filter {
                it.state == CameraSupervisor.SlotState.STREAMING && it.key != null
            }
            if (streaming.isEmpty()) {
                emit("暂无画面，无法总录像")
                return@launch
            }
            val cells = streaming.map { rt ->
                val name = rt.config?.name?.takeIf { it.isNotBlank() }
                    ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                    ?: "摄像头 ${rt.slot + 1}"
                CompositeRecorder.Cell(
                    rt.slot, name, rt.config?.mirror ?: false, rt.config?.rotation ?: 0
                )
            }
            val ok = captureManager.startComposite(
                cells, s.osdName, s.osdClock, s.osdSpeed, s.videoDirUri
            )
            if (ok) _compositeRecording.value = true else emit("总录像启动失败")
        }
    }

    private fun emit(msg: String) {
        _events.tryEmit(msg)
    }
}
