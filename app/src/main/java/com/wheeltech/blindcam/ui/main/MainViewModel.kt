package com.wheeltech.blindcam.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.domain.DisplayLayout
import com.wheeltech.blindcam.domain.StreamProfile
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.recorder.BitrateAdvisor
import com.wheeltech.blindcam.recorder.TotalRecorder
import com.wheeltech.blindcam.recorder.OsdRenderer
import com.wheeltech.blindcam.usb.UsbDeviceInfo
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
import kotlinx.coroutines.withContext

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

    /** DataStore 的初始值是默认 AppSettings，真实设置到达前不能拿它判断用户选择 */
    private var settingsReady = false
    private var pendingAutoLayoutCount: Int? = null

    /** 全局设置 */
    val settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings()).also { sf ->
        viewModelScope.launch {
            settingsRepository.settings.collect {
                sf.value = it
                settingsReady = true
                // 摄像头先到、设置在 DataStore 后到：等真实设置加载后补一次热插拔自动布局
                pendingAutoLayoutCount?.let { count ->
                    pendingAutoLayoutCount = null
                    lastAutoLayoutCount = -1
                    syncLayoutForConnected(count)
                }
            }
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
    fun grantAll() = supervisor.grantAll(manual = false)
    /** 用户主动点击钥匙图标：绕过自动去重，始终重新弹窗；全部已授权时提示 */
    fun grantAllManual() = supervisor.grantAll(manual = true)
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

    /** 设置页分段选择器直接指定角度，避免连续 rotate() 读旧值被合并 */
    fun setRotation(slot: Int, degrees: Int) = supervisor.setRotation(slot, degrees)

    fun rename(slot: Int, name: String) = supervisor.rename(slot, name)

    fun setWarn(slot: Int, warn: Boolean) = supervisor.setWarn(slot, warn)

    /** 某路实时降噪档位（0关/1弱/2中/3强） */
    fun setDenoise(slot: Int, level: Int) = supervisor.setDenoise(slot, level)

    // ------------------------------------------------------------------
    // 全局设置（OSD / 预警音量）
    // ------------------------------------------------------------------
    fun setOsdName(v: Boolean) = viewModelScope.launch { settingsRepository.setOsdName(v) }
    fun setOsdClock(v: Boolean) = viewModelScope.launch { settingsRepository.setOsdClock(v) }
    fun setWarnVolume(v: Int) = viewModelScope.launch { settingsRepository.setWarnVolume(v) }
    fun setBootAutoStart(v: Boolean) = viewModelScope.launch { settingsRepository.setBootAutoStart(v) }

    // 码率 / 循环录像 / GPS 开关
    fun setSingleBitrateQuality(v: Int) =
        viewModelScope.launch { settingsRepository.setSingleBitrateQuality(v) }
    fun setCompositeBitrateQuality(v: Int) =
        viewModelScope.launch { settingsRepository.setCompositeBitrateQuality(v) }
    fun setLoopEnabled(v: Boolean) = viewModelScope.launch { settingsRepository.setLoopEnabled(v) }
    fun setLoopSegmentMinutes(v: Int) =
        viewModelScope.launch { settingsRepository.setLoopSegmentMinutes(v) }
    fun setLoopKeepFreeMb(v: Int) =
        viewModelScope.launch { settingsRepository.setLoopKeepFreeMb(v) }
    fun setGpsEnabled(v: Boolean) = viewModelScope.launch { settingsRepository.setGpsEnabled(v) }

    /** 恢复照片 / 录像保存位置为应用默认目录 */
    fun resetPhotoDir() = viewModelScope.launch { settingsRepository.setPhotoDirUri("") }
    fun resetVideoDir() = viewModelScope.launch { settingsRepository.setVideoDirUri("") }

    // ------------------------------------------------------------------
    // 日志导出（SAF 选目录）
    // ------------------------------------------------------------------
    fun exportLogs(treeUri: String, onResult: (String) -> Unit) {
        viewModelScope.launch {
            // 日志可能接近 4MB，读文件/拼装/写 SAF 全部放 IO，避免导出时主线程 ANR
            val outcome = withContext(Dispatchers.IO) {
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
                    appContainer.mediaSaver.exportText(treeUri, name, header + Logger.snapshot())
                    name
                }
            }
            outcome.onSuccess { onResult("日志已导出：$it") }
                .onFailure { onResult("导出失败：${it.message}") }
        }
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    /**
     * 最近一次自动布局对应的摄像头路数。
     * 只在“热插拔导致路数变化”时自动切到 N 屏；用户手动选单路后，
     * 只要路数没变就不再覆盖（主题重建时 ViewModel 还在，也不会重置）。
     */
    private var lastAutoLayoutCount = -1

    /**
     * 摄像头热插拔智能布局：接入 1/2/3/4 路分别自动切单/双/三/四屏。
     * 路数没变时不动，避免覆盖用户手动选择。
     */
    fun syncLayoutForConnected(count: Int) {
        if (!settingsReady) {
            // 等设置真正加载后再应用，避免启动瞬间被默认 AUTO 误导
            pendingAutoLayoutCount = count
            return
        }
        if (count == lastAutoLayoutCount) return
        lastAutoLayoutCount = count
        val target = DisplayLayout.modeForConnected(count) ?: return
        if (settings.value.layoutMode != target) {
            viewModelScope.launch { settingsRepository.setLayoutMode(target) }
        }
    }

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

    /** 单路显示：同时持久化布局模式与要看的槽位 */
    fun setDisplayMode(mode: AppSettings.LayoutMode, focusSlot: Int? = null) {
        viewModelScope.launch {
            settingsRepository.setLayoutMode(mode)
            focusSlot?.let { settingsRepository.setFocusSlot(it) }
        }
    }

    fun setFocusSlot(slot: Int) {
        viewModelScope.launch { settingsRepository.setFocusSlot(slot.coerceAtLeast(0)) }
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
            // 按该画框当前镜像/旋转设置输出（与预览所见一致），文件名带设备名便于相册分类
            captureManager.takePhoto(
                slot, dir, displayNameOf(rt),
                rt.config?.mirror ?: false, rt.config?.rotation ?: 0,
                rt.config?.denoise ?: 0
            ) { result ->
                result.onSuccess { emit("照片已保存：$it") }
                    .onFailure { emit("拍照失败：${it.message}") }
            }
        }
    }

    /** 统一的设备显示名：自定义名优先，否则设备产品名，再否则 CAMx */
    private fun displayNameOf(rt: CameraSupervisor.SlotRuntime): String =
        rt.config?.name?.takeIf { it.isNotBlank() }
            ?: rt.device?.productName?.takeIf { it.isNotBlank() }
            ?: "CAM${rt.slot + 1}"

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
            val displayName = displayNameOf(rt)
            val osd = buildOverlay(s, displayName)
            // 按该画框当前镜像/旋转设置输出录像（旋转 90/270 时输出宽高互换），并叠加 OSD；
            // 文件名带设备名，输出通用 MP4；应用该路降噪档位与码率档位（按分辨率帧率动态计算）
            val bitrate = BitrateAdvisor.singleBitrate(
                profile.width, profile.height, profile.fps, s.singleBitrateQuality
            )
            val ok = captureManager.startRecording(
                slot, profile.width, profile.height, profile.fps, s.videoDirUri,
                displayName, cfg?.mirror ?: false, cfg?.rotation ?: 0, osd,
                denoiseLevel = cfg?.denoise ?: 0,
                bitrateOverride = bitrate
            )
            if (ok) _recordingSlots.update { it + slot } else emit("录像启动失败")
        }
    }

    /** 根据全局设置构造单路录像的 OSD 叠加（无任何叠加项时返回 null） */
    private fun buildOverlay(s: AppSettings, name: String): OsdRenderer.Overlay? =
        if (s.osdName || s.osdClock) {
            OsdRenderer.Overlay(
                showName = s.osdName,
                name = name,
                showClock = s.osdClock
            )
        } else {
            null
        }

    /** 总录像开关：录制所有画面（按当前 1/2/3/4 宫格合成） */
    fun toggleComposite() {
        if (captureManager.isCompositeStopping()) {
            // 正在保存上一段：忽略连点，避免误报“启动失败”
            return
        }
        if (captureManager.isCompositeRecording()) {
            appContainer.gpsTracker.stop()
            // 保持“录制中”视觉状态直到文件真正落盘完成，避免用户误以为已停止而重复点击
            captureManager.stopComposite { result ->
                result.onSuccess { emit("总录像已保存：$it") }
                    .onFailure { emit("总录像失败：${it.message}") }
                _compositeRecording.value = false
            }
            return
        }
        viewModelScope.launch {
            val s = settingsRepository.settings.first()
            // 总录像 = 当前界面实际显示的画框，而不是“所有已接入摄像头”。
            // 四屏/三屏/双屏/单路各自独立 MP4，回看切换直接播该文件。
            val occupied = slots.value
                .filter { it.state != CameraSupervisor.SlotState.EMPTY }
                .map { it.slot }
            val visible = com.wheeltech.blindcam.domain.DisplayLayout.visibleSlots(
                s.layoutMode, occupied, s.focusSlot
            )
            val streaming = visible.mapNotNull { slot ->
                slots.value.getOrNull(slot)?.takeIf {
                    it.state == CameraSupervisor.SlotState.STREAMING && it.key != null &&
                        it.activeProfile != null
                }
            }
            if (streaming.isEmpty()) {
                emit("暂无画面，无法总录像")
                return@launch
            }
            val cells = streaming.mapNotNull { rt ->
                val p = rt.activeProfile ?: return@mapNotNull null
                val name = rt.config?.name?.takeIf { it.isNotBlank() }
                    ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                    ?: "摄像头 ${rt.slot + 1}"
                TotalRecorder.Cell(
                    slot = rt.slot,
                    name = name,
                    mirror = rt.config?.mirror ?: false,
                    rotation = rt.config?.rotation ?: 0,
                    denoiseLevel = rt.config?.denoise ?: 0,
                    srcWidth = p.width,
                    srcHeight = p.height,
                    fps = p.fps
                )
            }
            val ok = captureManager.startComposite(
                cells, s.osdName, s.osdClock, s.videoDirUri,
                bitrate = 0,
                loopEnabled = s.loopEnabled,
                segmentMinutes = s.loopSegmentMinutes,
                keepFreeMb = s.loopKeepFreeMb
            )
            if (ok) {
                _compositeRecording.value = true
                emit("总录像开始：${cells.size} 路独立录制（${cells.joinToString { it.name }}）")
                // 总录像开始：按开关启动 GPS 轨迹记录（与总录像同名的 .csv 轨迹文件）
                if (s.gpsEnabled) appContainer.gpsTracker.start(s.videoDirUri)
            } else emit("总录像启动失败")
        }
    }

    private fun emit(msg: String) {
        _events.tryEmit(msg)
    }
}

