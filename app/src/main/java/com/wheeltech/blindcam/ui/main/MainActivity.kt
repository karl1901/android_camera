package com.wheeltech.blindcam.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.databinding.ActivityMainBinding
import com.wheeltech.blindcam.databinding.ItemCameraPanelBinding
import com.wheeltech.blindcam.domain.CameraSupervisor
import com.wheeltech.blindcam.domain.DevicePerformance
import com.wheeltech.blindcam.domain.StreamProfile
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.render.PreviewController
import com.wheeltech.blindcam.render.PreviewRenderer
import com.wheeltech.blindcam.service.UvcForegroundService
import com.wheeltech.blindcam.ui.common.GlassMenuPopup
import com.wheeltech.blindcam.ui.common.ThemedActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主界面：动态 1/2/3/4 宫格预览。
 * 多路取流/授权/热插拔由常驻 [CameraSupervisor] 负责，本类只负责渲染与交互。
 */
class MainActivity : ThemedActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    /** 每个槽位一个面板（视图 + 渲染器），按 slot 索引 0..MAX_SLOTS-1 */
    private class Panel(val slot: Int, val ui: ItemCameraPanelBinding, val renderer: PreviewRenderer) {
        var lastKey: String? = null
    }

    private val panels = ArrayList<Panel>()
    private lateinit var previewController: PreviewController
    private var isFullscreen = false
    private var gridKey: String? = null
    /** 当前设置对话框引用：保存目录变更后关闭并重新打开以刷新路径 */
    private var settingsDialog: androidx.appcompat.app.AlertDialog? = null

    /** CAMERA 授权通过后待执行的动作（用于手动点未授权时先补 CAMERA） */
    private var pendingAfterCamera: (() -> Unit)? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val action = pendingAfterCamera
                pendingAfterCamera = null
                if (action != null) {
                    UvcForegroundService.start(this)
                    action()
                } else {
                    onRuntimePermissionsReady()
                }
            } else {
                toast(getString(R.string.dialog_grant_camera))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论是否授予通知权限，都启动前台服务并继续 USB 授权
            afterNotificationPermission()
        }

    /** GPS 定位权限：授权后才开启总录像轨迹；拒绝则回退开关并刷新对话框 */
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                lifecycleScope.launch {
                    viewModel.setGpsEnabled(true)
                    toast("已开启 GPS 轨迹记录")
                    reopenSettings()
                }
            } else {
                lifecycleScope.launch {
                    viewModel.setGpsEnabled(false)
                    toast("未授予定位权限，无法记录 GPS 轨迹")
                    reopenSettings()
                }
            }
        }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 有 CAMERA 权限后，串行处理通知权限，再发起 USB 授权（避免多个系统弹窗互相吞掉） */
    private fun onRuntimePermissionsReady() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            afterNotificationPermission()
        }
    }

    private fun afterNotificationPermission() {
        UvcForegroundService.start(this)
        viewModel.grantAll()
    }

    /** CAMERA 是 USB 授权前置；未授予时先弹 CAMERA，授予后再执行动作 */
    private fun ensureCameraThen(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAfterCamera = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private val photoDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                lifecycleScope.launch {
                    app.settingsRepository.setPhotoDirUri(uri.toString())
                    toast("照片保存位置已更新")
                    reopenSettings()
                }
            }
        }

    private val videoDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                lifecycleScope.launch {
                    app.settingsRepository.setVideoDirUri(uri.toString())
                    toast("录像保存位置已更新")
                    reopenSettings()
                }
            }
        }

    /** 导出日志：选择目录后写入全部运行日志 */
    private val logDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                viewModel.exportLogs(uri.toString()) { msg -> toast(msg) }
            }
        }

    private val app: UvcApp get() = application as UvcApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = androidx.lifecycle.ViewModelProvider(
            this,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[MainViewModel::class.java]

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyDisplayCutout()
        applyWindowInsets()

        buildPanels()
        setupButtons()
        observeUi()

        // 权限严格串行：先 CAMERA（USB 授权前置），再通知，最后 USB 授权，
        // 杜绝多个系统权限弹窗同时请求被互相吞掉
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onRuntimePermissionsReady()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 动态创建 MAX_SLOTS 个面板（全部常驻 GridLayout，切换布局只改 span/可见性） */
    private fun buildPanels() {
        val renderers = LinkedHashMap<Int, PreviewRenderer>()
        for (slot in 0 until UvcCameraManager.MAX_SLOTS) {
            val ui = ItemCameraPanelBinding.inflate(layoutInflater, binding.gridPreview, false)
            val renderer = PreviewRenderer(ui.texturePanel)
            val panel = Panel(slot, ui, renderer)
            panels.add(panel)
            renderers[slot] = renderer
            wirePanel(panel)
            // 关键：所有 TextureView 一开始就挂在 GridLayout 上，切换 1/2/3/4 路时
            // 只修改 GridLayout.LayoutParams 与 visibility，绝不 removeAllViews/重新 addView。
            // 部分 vivo/Android 17 的 HWUI Vulkan 在 TextureView 被 detach 时仍由
            // RenderThread 持有 surface，会直接 SIGABRT：
            // "getFrame() called on a context with no surface!"（切布局闪退根因）
            ui.root.visibility = View.GONE
            binding.gridPreview.addView(
                ui.root,
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = 0
                    rowSpec = GridLayout.spec(0, 2, 1f)
                    columnSpec = GridLayout.spec(0, 2, 1f)
                }
            )
        }
        previewController = PreviewController(renderers.toMap())
        previewController.start(app.cameraManager)
    }

    private fun wirePanel(panel: Panel) {
        val slot = panel.slot
        panel.ui.btnCam.setOnClickListener { showCameraPicker(slot) }
        panel.ui.btnCam.setOnLongClickListener {
            showRenameDialog(slot)
            true
        }
        panel.ui.btnProfile.setOnClickListener { showProfilePicker(slot) }
        panel.ui.btnPhoto.setOnClickListener { viewModel.takePhoto(slot) }
        panel.ui.btnRecord.setOnClickListener { viewModel.toggleRecording(slot) }
        panel.ui.btnMirror.setOnClickListener {
            val cur = viewModel.slots.value.getOrNull(slot)?.config?.mirror ?: false
            viewModel.setMirror(slot, !cur)
        }
        panel.ui.btnRotate.setOnClickListener { viewModel.rotate(slot) }
        // 未授权 / 出错占位：点击授权或重试
        panel.ui.tvStatus.setOnClickListener {
            val rt = viewModel.slots.value.getOrNull(slot)
            when {
                // 未授权：点占位弹授权
                rt?.state == CameraSupervisor.SlotState.NO_PERMISSION && rt.device != null -> {
                    val devId = rt.device.deviceId
                    ensureCameraThen { viewModel.grant(devId) }
                }
                // 取流失败（含达上限放弃）：点占位立即手动重试该路
                rt?.state == CameraSupervisor.SlotState.ERROR -> viewModel.retrySlot(slot)
                else -> viewModel.refresh()
            }
        }
    }

    private fun setupButtons() {
        binding.btnGrant.setOnClickListener { ensureCameraThen { viewModel.grantAllManual() } }
        binding.btnRefresh.setOnClickListener { viewModel.refresh() }
        binding.btnLayout.setOnClickListener { showDisplayMenu() }
        binding.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, com.wheeltech.blindcam.ui.settings.SettingsActivity::class.java))
        }
        binding.btnGallery.setOnClickListener {
            startActivity(android.content.Intent(this, com.wheeltech.blindcam.ui.gallery.GalleryActivity::class.java))
        }
        binding.btnRecordAll.setOnClickListener { viewModel.toggleComposite() }
        binding.btnFullscreen.setOnClickListener { enterFullscreen() }
        binding.previewArea.setOnClickListener { if (isFullscreen) exitFullscreen() }
    }

    private fun observeUi() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.slots.collect { renderAll() } }
                launch { viewModel.settings.collect { renderAll() } }
                launch { viewModel.recordingSlots.collect { renderAll() } }
                launch { viewModel.compositeRecording.collect { rec -> renderRecordAllButton(rec) } }
                launch { app.warningManager.alerts.collect { renderAll() } }
                launch { viewModel.devices.collect { renderDeviceChips() } }
                launch { viewModel.events.collect { toast(it) } }
            }
        }
    }

    // ------------------------------------------------------------------
    // 动态宫格
    // ------------------------------------------------------------------

    /** 当前应当显示的槽位（按顺序） */
    private fun visibleSlots(): List<Int> {
        val s = viewModel.settings.value
        val occupied = viewModel.slots.value
            .filter { it.state != CameraSupervisor.SlotState.EMPTY }
            .map { it.slot }
        return com.wheeltech.blindcam.domain.DisplayLayout.visibleSlots(
            s.layoutMode, occupied, s.focusSlot
        )
    }

    private fun renderAll() {
        // 热插拔：插入几路自动几屏，拔掉后自动降到剩余路数对应的宫格
        val connectedCount = viewModel.slots.value.count {
            it.state != CameraSupervisor.SlotState.EMPTY
        }
        viewModel.syncLayoutForConnected(connectedCount)
        val visible = visibleSlots()
        applyGrid(visible)
        for (panel in panels) {
            if (panel.slot in visible) bindPanel(panel)
        }
        binding.tvEmpty.visibility =
            if (visible.isEmpty()) View.VISIBLE else View.GONE
        updateLayoutButton()
        updateTopBarVisibility()
        renderDeviceChips()
    }

    private fun layoutLabel(mode: AppSettings.LayoutMode): String = when (mode) {
        AppSettings.LayoutMode.AUTO -> getString(R.string.layout_quad)
        AppSettings.LayoutMode.TRIPLE -> getString(R.string.layout_triple)
        AppSettings.LayoutMode.DUAL -> getString(R.string.layout_dual)
        AppSettings.LayoutMode.SINGLE -> getString(R.string.layout_single)
    }

    private fun updateLayoutButton() {
        val occupied = viewModel.slots.value.count {
            it.state != CameraSupervisor.SlotState.EMPTY
        }
        val mode = com.wheeltech.blindcam.domain.DisplayLayout.effectiveMode(
            viewModel.settings.value.layoutMode, occupied
        )
        binding.btnLayout.text = "${layoutLabel(mode)}  ▾"
    }

    /** 无摄像头时隐藏显示模式/总录像/全屏，避免点了没有对象 */
    private fun updateTopBarVisibility() {
        val occupied = viewModel.slots.value.count {
            it.state != CameraSupervisor.SlotState.EMPTY
        }
        val hasCamera = occupied > 0
        binding.btnLayout.visibility = if (hasCamera) View.VISIBLE else View.GONE
        binding.btnRecordAll.visibility = if (hasCamera) View.VISIBLE else View.GONE
        binding.btnFullscreen.visibility = if (hasCamera) View.VISIBLE else View.GONE
        binding.barBottom.visibility =
            if (viewModel.devices.value.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * 显示模式下拉：只列出当前接入路数支持的选项。
     * 1 路：单路；2 路：双屏/单路；3 路：三屏/双屏/单路；4 路：四屏/三屏/双屏/单路。
     */
    private fun showDisplayMenu() {
        val s = viewModel.settings.value
        val occupied = viewModel.slots.value.filter {
            it.state != CameraSupervisor.SlotState.EMPTY
        }
        if (occupied.isEmpty()) {
            toast("未接入摄像头")
            return
        }
        val modes = com.wheeltech.blindcam.domain.DisplayLayout.availableModes(occupied.size)
        val effective = com.wheeltech.blindcam.domain.DisplayLayout.effectiveMode(
            s.layoutMode, occupied.size
        )
        val entries = ArrayList<GlassMenuPopup.Entry>()
        val actions = ArrayList<() -> Unit>()
        entries.add(GlassMenuPopup.Entry.Header(getString(R.string.layout_menu_title)))
        actions.add {}
        val focus = if (s.focusSlot in occupied.map { it.slot }) {
            s.focusSlot
        } else {
            occupied.firstOrNull()?.slot ?: 0
        }
        modes.forEach { mode ->
            entries.add(
                GlassMenuPopup.Entry.Item(layoutLabel(mode), selected = mode == effective)
            )
            actions.add {
                if (mode == AppSettings.LayoutMode.SINGLE && occupied.size > 1) {
                    showSingleCameraMenu(occupied, focus)
                } else {
                    viewModel.setDisplayMode(
                        mode,
                        if (mode == AppSettings.LayoutMode.SINGLE) focus else null
                    )
                }
            }
        }
        GlassMenuPopup(this, entries) { idx ->
            actions.getOrNull(idx)?.invoke()
        }.show(binding.btnLayout)
    }

    /** 单路显示：再弹一层“选择哪一路”，四路摄像头都能直接切换 */
    private fun showSingleCameraMenu(occupied: List<CameraSupervisor.SlotRuntime>, focus: Int) {
        if (occupied.isEmpty()) return
        val entries = occupied.map { rt ->
            val name = rt.config?.name?.takeIf { it.isNotBlank() }
                ?: rt.device?.defaultName?.takeIf { it.isNotBlank() }
                ?: "画面 ${rt.slot + 1}"
            GlassMenuPopup.Entry.Item(name, selected = rt.slot == focus)
        }
        GlassMenuPopup(this, entries) { idx ->
            val slot = occupied.getOrNull(idx)?.slot ?: return@GlassMenuPopup
            viewModel.setDisplayMode(AppSettings.LayoutMode.SINGLE, slot)
        }.show(binding.btnLayout)
    }

    /** 总录像按钮默认外观（录制前缓存，停止时恢复） */
    private var recordAllDefaultTint: ColorStateList? = null
    private var recordAllDefaultBg: android.graphics.drawable.Drawable? = null

    /**
     * 总录像按钮：录制中切换为与单路录像按钮一致的"红底 + 白色停止方块"，
     * 停止后恢复顶部工具按钮的无边框外观，给用户明确的录制正反馈。
     */
    private fun renderRecordAllButton(recording: Boolean) {
        binding.btnRecordAll.apply {
            // 首次进入时缓存默认背景（无边框 ripple）与图标着色
            if (recordAllDefaultBg == null) {
                recordAllDefaultBg = background?.constantState?.newDrawable()
                recordAllDefaultTint = imageTintList
            }
            if (recording) {
                setImageResource(R.drawable.ic_stop)
                setBackgroundResource(R.drawable.bg_capture_button)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E53935"))
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            } else {
                setImageResource(R.drawable.ic_videocam)
                recordAllDefaultBg?.let { background = it.constantState?.newDrawable() }
                backgroundTintList = null
                imageTintList = recordAllDefaultTint
            }
        }
    }

    /**
     * 网格布局规则由 [com.wheeltech.blindcam.domain.GridLayoutSpec] 统一提供。
     * 仅修改常驻面板的 span/可见性，绝不 detach TextureView。
     */
    private fun applyGrid(visible: List<Int>) {
        val key = visible.joinToString(",")
        if (key == gridKey) return
        gridKey = key
        for (panel in panels) {
            val index = visible.indexOf(panel.slot)
            if (index < 0) {
                // 隐藏而不是移除：TextureView 始终留在视图树里，避免 HWUI 仍持有
                // 已 detach 的 Surface 导致原生崩溃。重排布局只改 visible。
                panel.ui.root.visibility = View.GONE
                panel.ui.viewWarn.setWarning(false)
                panel.lastKey = null
                panel.renderer.active = false
                continue
            }
            panel.ui.root.visibility = View.VISIBLE
            panel.renderer.active = true
            val cell = com.wheeltech.blindcam.domain.GridLayoutSpec.cell(visible.size, index)
            val lp = (panel.ui.root.layoutParams as? GridLayout.LayoutParams)
                ?: GridLayout.LayoutParams()
            lp.width = 0
            lp.height = 0
            lp.rowSpec = GridLayout.spec(cell.row, cell.rowSpan, 1f)
            lp.columnSpec = GridLayout.spec(cell.col, cell.colSpan, 1f)
            panel.ui.root.layoutParams = lp
        }
    }

    private fun bindPanel(panel: Panel) {
        val slot = panel.slot
        val rt = viewModel.slots.value.getOrNull(slot) ?: return
        val cfg = rt.config
        val device = rt.device
        val recording = viewModel.recordingSlots.value.contains(slot)

        // 设备切换时清黑，避免上一路定格；未取流/重连/失败时也清空，
        // 防止“画面卡在断开前一帧”被误认为仍在实时预览。
        val blank = rt.state != CameraSupervisor.SlotState.STREAMING
        if (panel.lastKey != rt.key || blank) {
            panel.renderer.clear()
            panel.lastKey = rt.key
        }

        // 镜像 / 旋转 / 每路降噪（直接应用到渲染器，状态持久化）
        panel.renderer.mirrorH = cfg?.mirror ?: false
        panel.renderer.rotationDegrees = cfg?.rotation ?: 0
        panel.renderer.setDenoiseLevel(
            cfg?.denoise ?: 0,
            rt.activeProfile?.fps ?: 30
        )
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
        panel.ui.btnMirror.backgroundTintList =
            if (cfg?.mirror == true) ColorStateList.valueOf(primary)
            else null
        panel.ui.btnRotate.backgroundTintList =
            if ((cfg?.rotation ?: 0) != 0) ColorStateList.valueOf(primary)
            else null

        // 名称（优先自定义名）/ 档位
        panel.ui.btnCam.text = when {
            !cfg?.name.isNullOrBlank() -> cfg!!.name
            device != null -> device.defaultName
            else -> getString(R.string.camera_unnamed)
        }
        panel.ui.btnProfile.text = rt.activeProfile?.let {
            "${it.width}x${it.height}·${it.fps}fps"
        } ?: "—"

        // 状态占位
        val overlayVisible = !isFullscreen
        // 名称 + 分辨率 + 四个操作按钮的可见性统一由这里裁决：
        // 非全屏显示、全屏隐藏。退出全屏后 renderAll() 会走到这里恢复，
        // 不再出现「退出全屏后按钮/名称/分辨率消失」。
        val controlsVis = if (overlayVisible) View.VISIBLE else View.GONE
        panel.ui.btnCam.visibility = controlsVis
        panel.ui.btnProfile.visibility = controlsVis
        panel.ui.btnPhoto.visibility = controlsVis
        panel.ui.btnRecord.visibility = controlsVis
        panel.ui.btnMirror.visibility = controlsVis
        panel.ui.btnRotate.visibility = controlsVis
        when (rt.state) {
            CameraSupervisor.SlotState.STREAMING,
            CameraSupervisor.SlotState.OPENING -> {
                panel.ui.tvStatus.visibility =
                    if (rt.state == CameraSupervisor.SlotState.OPENING && overlayVisible) {
                        panel.ui.tvStatus.text = "打开中…"
                        View.VISIBLE
                    } else View.GONE
            }
            CameraSupervisor.SlotState.NO_PERMISSION -> {
                panel.ui.tvStatus.text = getString(R.string.camera_no_permission)
                panel.ui.tvStatus.visibility = if (overlayVisible) View.VISIBLE else View.GONE
            }
            CameraSupervisor.SlotState.ERROR -> {
                panel.ui.tvStatus.text = getString(R.string.camera_retrying)
                panel.ui.tvStatus.visibility = if (overlayVisible) View.VISIBLE else View.GONE
            }
            CameraSupervisor.SlotState.EMPTY -> {}
        }

        // 录像按钮 / 指示
        panel.ui.btnRecord.setImageResource(
            if (recording) R.drawable.ic_stop else R.drawable.ic_videocam
        )
        panel.ui.btnRecord.backgroundTintList = when {
            recording -> ColorStateList.valueOf(Color.parseColor("#E53935"))
            else -> null
        }
        // 镜像/旋转高亮上面单独设置，录像按钮红会覆盖，这里不冲突（不同按钮）
        panel.ui.tvRec.visibility = if (recording && overlayVisible) View.VISIBLE else View.GONE

        // 盲区预警红框（全屏时也保留）：多圈脉冲光晕 + 角标 + 边缘红晕
        val warning = app.warningManager.alerts.value[slot] == true
        panel.ui.viewWarn.setWarning(warning)
    }

    // ------------------------------------------------------------------
    // 底部设备栏
    // ------------------------------------------------------------------
    private fun renderDeviceChips() {
        updateTopBarVisibility()
        val container = binding.llDeviceList
        container.removeAllViews()
        val devices = viewModel.devices.value
        for (device in devices) {
            val chip = TextView(this).apply {
                val dot = if (device.hasPermission) "● " else "○ "
                text = dot + device.displayText
                setTextColor(
                    if (device.hasPermission)
                        ContextCompat.getColor(this@MainActivity, R.color.status_ok)
                    else ContextCompat.getColor(this@MainActivity, R.color.status_err)
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * DevicePerformance.uiScale())
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                if (!device.hasPermission) {
                    val devId = device.deviceId
                    setOnClickListener { ensureCameraThen { viewModel.grant(devId) } }
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            container.addView(chip, lp)
        }
    }

    // ------------------------------------------------------------------
    // 对话框
    // ------------------------------------------------------------------
    private fun showCameraPicker(slot: Int) {
        val devices = viewModel.devices.value
        if (devices.isEmpty()) {
            toast(getString(R.string.no_device)); return
        }
        val names = devices.map { it.displayText }.toTypedArray()
        val currentKey = viewModel.slots.value.getOrNull(slot)?.key
        val checked = devices.indexOfFirst { it.stableKey == currentKey }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_camera_title)
            .setSingleChoiceItems(names, checked) { d, which ->
                viewModel.assignDevice(slot, devices[which])
                d.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .setNeutralButton(R.string.action_rename) { _, _ -> showRenameDialog(slot) }
            .show()
    }

    private fun showProfilePicker(slot: Int) {
        val rt = viewModel.slots.value.getOrNull(slot)
        val caps = rt?.capabilities
        if (caps.isNullOrEmpty()) {
            toast("该摄像头暂无可选档位"); return
        }
        val panel = panels.firstOrNull { it.slot == slot }
        val entries = caps.map { p ->
            GlassMenuPopup.Entry.Item(
                p.display,
                selected = p == rt.activeProfile
            )
        }
        val anchor = panel?.ui?.btnProfile ?: binding.btnLayout
        GlassMenuPopup(this, entries) { which ->
            caps.getOrNull(which)?.let { viewModel.setProfile(slot, it) }
        }.show(anchor)
    }

    private fun showRenameDialog(slot: Int) {
        val rt = viewModel.slots.value.getOrNull(slot)
        if (rt?.device == null) {
            toast("该通道暂未接入摄像头"); return
        }
        val input = EditText(this).apply {
            hint = getString(R.string.rename_hint)
            setText(rt.config?.name ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(R.string.common_confirm) { _, _ ->
                viewModel.rename(slot, input.text.toString())
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showSettingsDialog() {
        lifecycleScope.launch {
            val s = app.settingsRepository.settings.first()
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
            }
            val scroll = ScrollView(this@MainActivity).apply { addView(container) }

            // ================= 分类 1：显示 =================
            container.addView(sectionLabel("显示"))
            container.addView(itemLabel("主题"))
            val themeGroup = RadioGroup(this@MainActivity).apply { orientation = RadioGroup.HORIZONTAL }
            AppSettings.ThemeMode.entries.forEachIndexed { i, m ->
                themeGroup.addView(RadioButton(this@MainActivity).apply {
                    text = when (m) {
                        AppSettings.ThemeMode.SYSTEM -> getString(R.string.theme_system)
                        AppSettings.ThemeMode.DARK -> getString(R.string.theme_dark)
                        AppSettings.ThemeMode.LIGHT -> getString(R.string.theme_light)
                        AppSettings.ThemeMode.TECH_BLUE -> getString(R.string.theme_tech_blue)
                    }; id = i
                })
            }
            themeGroup.check(s.themeMode.ordinal)
            container.addView(themeGroup)

            container.addView(itemLabel("显示布局"))
            val layoutGroup = RadioGroup(this@MainActivity).apply { orientation = RadioGroup.HORIZONTAL }
            AppSettings.LayoutMode.entries.forEachIndexed { i, m ->
                layoutGroup.addView(RadioButton(this@MainActivity).apply {
                    text = when (m) {
                        AppSettings.LayoutMode.AUTO -> getString(R.string.layout_quad)
                        AppSettings.LayoutMode.TRIPLE -> getString(R.string.layout_triple)
                        AppSettings.LayoutMode.DUAL -> getString(R.string.layout_dual)
                        AppSettings.LayoutMode.SINGLE -> getString(R.string.layout_single)
                    }; id = i
                })
            }
            layoutGroup.check(s.layoutMode.ordinal)
            container.addView(layoutGroup)

            // ================= 分类 2：录像叠加（OSD） =================
            container.addView(sectionLabel("录像叠加"))
            container.addView(itemLabel("在录像画面上显示的信息（单路与总录像均生效）"))
            container.addView(checkBox("左上角显示自定义名称", s.osdName) { viewModel.setOsdName(it) })
            container.addView(checkBox("右上角显示系统时间", s.osdClock) { viewModel.setOsdClock(it) })

            // ================= 分类 3：盲区预警 =================
            container.addView(sectionLabel("盲区预警"))
            val warnSlots = viewModel.slots.value.filter {
                it.key != null && it.state != CameraSupervisor.SlotState.EMPTY
            }
            container.addView(itemLabel("为需要预警的画面开启（有物体进入盲区时红框提示并响铃）"))
            if (warnSlots.isEmpty()) {
                container.addView(itemLabel("暂无已接入摄像头，接入后可在此开启对应画面预警"))
            } else {
                warnSlots.forEach { rt ->
                    val label = rt.config?.name?.takeIf { it.isNotBlank() }
                        ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                        ?: "摄像头 ${rt.slot + 1}"
                    container.addView(
                        checkBox("画面 ${rt.slot + 1}：$label", rt.config?.warn == true) {
                            viewModel.setWarn(rt.slot, it)
                        }
                    )
                }
            }
            container.addView(itemLabel("提示音音量：${s.warnVolume}"))
            val volumeRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val seek = SeekBar(this@MainActivity).apply {
                max = 100
                progress = s.warnVolume
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val volValue = TextView(this@MainActivity).apply {
                text = "${s.warnVolume}"
                setPadding(dp(12), 0, 0, 0)
                setTextColor(themeColor(R.attr.onSurfaceDim))
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    volValue.text = progress.toString()
                    if (fromUser) viewModel.setWarnVolume(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            volumeRow.addView(seek)
            volumeRow.addView(volValue)
            container.addView(volumeRow)

            // ================= 分类：实时降噪（每路独立） =================
            container.addView(sectionLabel("实时降噪（每路独立）"))
            container.addView(itemLabel("为每个画框单独设置，按该画框分辨率/帧率实时处理，4K 自动降负载，弱档即可明显压噪"))
            if (warnSlots.isEmpty()) {
                container.addView(itemLabel("暂无已接入摄像头，接入后可在此设置对应画面降噪"))
            } else {
                warnSlots.forEach { rt ->
                    val dLabel = rt.config?.name?.takeIf { it.isNotBlank() }
                        ?: rt.device?.productName?.takeIf { it.isNotBlank() }
                        ?: "摄像头 ${rt.slot + 1}"
                    container.addView(itemLabel("画面 ${rt.slot + 1}：$dLabel"))
                    container.addView(
                        denoiseLevelRow(rt.config?.denoise ?: 0) { lvl ->
                            viewModel.setDenoise(rt.slot, lvl)
                        }
                    )
                }
            }

            // ================= 分类：录像码率（按分辨率/帧率/路数动态） =================
            container.addView(sectionLabel("录像码率与画质"))
            container.addView(itemLabel("单路分录像：码率随该画框分辨率/帧率自动计算（自动≈高画质，1080p30 约 9Mbps、4K30 约 37Mbps）"))
            container.addView(
                segmentRow(
                    listOf("自动", "省流", "标准", "高画质", "原画"),
                    s.singleBitrateQuality
                ) { viewModel.setSingleBitrateQuality(it) }
            )
            container.addView(itemLabel("总录像：按合成画面路数自动计算（多路网格适当提高码率保证每格清晰）"))
            container.addView(
                segmentRow(
                    listOf("自动", "省流", "标准", "高画质", "原画"),
                    s.compositeBitrateQuality
                ) { viewModel.setCompositeBitrateQuality(it) }
            )

            // ================= 分类：循环录像（总录像，外置存储满覆盖） =================
            container.addView(sectionLabel("循环录像（总录像）"))
            container.addView(
                checkBox("开启循环录像（分段存储，空间不足时自动覆盖最旧总录像）", s.loopEnabled) {
                    viewModel.setLoopEnabled(it)
                    reopenSettings()
                }
            )
            if (s.loopEnabled) {
                container.addView(itemLabel("单段时长（到点自动切下一段，便于覆盖与回看）"))
                val segLabels = listOf("1分钟", "3分钟", "5分钟", "10分钟")
                val segValues = listOf(1, 3, 5, 10)
                val segIdx = segValues.indexOf(s.loopSegmentMinutes).let { if (it < 0) 1 else it }
                container.addView(segmentRow(segLabels, segIdx) {
                    viewModel.setLoopSegmentMinutes(segValues[it])
                })
                container.addView(itemLabel("保留剩余空间（低于该值开始删除最旧的总录像段）"))
                val freeLabels = listOf("512MB", "1GB", "2GB", "5GB")
                val freeValues = listOf(512, 1024, 2048, 5120)
                val freeIdx = freeValues.indexOf(s.loopKeepFreeMb).let { if (it < 0) 1 else it }
                container.addView(segmentRow(freeLabels, freeIdx) {
                    viewModel.setLoopKeepFreeMb(freeValues[it])
                })
            }

            // ================= 分类：GPS 轨迹（总录像） =================
            container.addView(sectionLabel("GPS 轨迹（总录像）"))
            container.addView(itemLabel("开启后总录像时记录行车轨迹，回看页可联动地图；车机无 GPS 时自动跳过"))
            container.addView(
                checkBox("总录像记录 GPS 轨迹", s.gpsEnabled) { checked ->
                    if (checked) {
                        if (hasLocationPermission()) {
                            viewModel.setGpsEnabled(true)
                        } else {
                            // 先申请定位权限，授权后才真正开启
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                    } else {
                        viewModel.setGpsEnabled(false)
                    }
                }
            )

            // ================= 分类 4：保存位置 =================
            container.addView(sectionLabel("保存位置"))
            // 照片：显示完整路径，点击重选；自定义时提供恢复默认
            container.addView(itemLabel(getString(R.string.settings_photo_dir)))
            container.addView(dirPathRow(app.mediaSaver.photoDisplayPath(s.photoDirUri)) {
                photoDirLauncher.launch(null)
            })
            if (s.photoDirUri.isNotBlank()) {
                container.addView(resetRow("恢复为应用默认目录") {
                    lifecycleScope.launch {
                        viewModel.resetPhotoDir()
                        toast("已恢复默认目录")
                        reopenSettings()
                    }
                })
            }
            // 录像：显示完整路径，点击重选；自定义时提供恢复默认
            container.addView(itemLabel(getString(R.string.settings_video_dir)))
            container.addView(dirPathRow(app.mediaSaver.videoDisplayPath(s.videoDirUri)) {
                videoDirLauncher.launch(null)
            })
            if (s.videoDirUri.isNotBlank()) {
                container.addView(resetRow("恢复为应用默认目录") {
                    lifecycleScope.launch {
                        viewModel.resetVideoDir()
                        toast("已恢复默认目录")
                        reopenSettings()
                    }
                })
            }

            // ================= 分类 5：日志与排查 =================
            container.addView(sectionLabel("日志与排查"))
            container.addView(itemLabel("出现问题时导出全部运行日志，可发送给开发者排查"))
            container.addView(actionRow("导出运行日志（手动选择文件夹）") { logDirLauncher.launch(null) })

            settingsDialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.settings_title)
                .setView(scroll)
                .setPositiveButton(R.string.common_confirm) { _, _ ->
                    val theme = AppSettings.ThemeMode.entries[themeGroup.checkedRadioButtonId.coerceAtLeast(0)]
                    val layout = AppSettings.LayoutMode.entries[layoutGroup.checkedRadioButtonId.coerceAtLeast(0)]
                    val oldTheme = s.themeMode
                    lifecycleScope.launch {
                        app.settingsRepository.setThemeMode(theme)
                        app.settingsRepository.setLayoutMode(layout)
                        app.applyTheme(theme)
                        // 固定浅色/深色/科技蓝是不同 setTheme，统一重建以可靠生效；
                        // 跟随系统深浅切换也一并重建，避免部分控件残留旧色
                        if (theme != oldTheme) recreate()
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .setOnDismissListener { settingsDialog = null }
                .show()
        }
    }

    /** 保存目录变更后：关闭并重新打开设置对话框，刷新显示的完整路径 */
    private fun reopenSettings() {
        settingsDialog?.dismiss()
        showSettingsDialog()
    }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        }

    private fun itemLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(themeColor(R.attr.onSurfaceDim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(12), 0, dp(2))
        }

    private fun actionRow(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(themeColor(com.google.android.material.R.attr.colorPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 保存目录路径行：完整显示绝对路径，可换行，点击重选 */
    private fun dirPathRow(path: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = "📁  $path\n点击可更改"
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setLineSpacing(dp(2).toFloat(), 1f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 恢复默认目录的小按钮 */
    private fun resetRow(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = android.view.Gravity.END
            setTextColor(themeColor(R.attr.onSurfaceDim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), dp(4), dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    /** 设置页复选框：先设初始值再绑监听，避免初始化触发写入 */
    private fun checkBox(text: String, checked: Boolean, onChange: (Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            this.text = text
            isChecked = checked
            setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }

    /**
     * 分段选择行：等分的若干标签按钮，选中项高亮（主色），点击立即写入并就地刷新，
     * 不重建对话框、不滚回顶部。用于降噪档位、码率档位、循环时长等离散选择。
     */
    private fun segmentRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }
        val chips = ArrayList<TextView>(labels.size)
        val primary = themeColor(com.google.android.material.R.attr.colorPrimary)
        val onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface)
        fun refresh(sel: Int) {
            chips.forEachIndexed { i, tv ->
                tv.setTextColor(if (i == sel) Color.WHITE else onSurface)
                tv.backgroundTintList = if (i == sel)
                    ColorStateList.valueOf(primary) else null
            }
        }
        labels.forEachIndexed { i, lab ->
            val tv = TextView(this).apply {
                text = lab
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                isClickable = true
                setOnClickListener {
                    onSelect(i)
                    refresh(i)
                }
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            row.addView(tv, lp)
            chips.add(tv)
        }
        refresh(selected)
        return row
    }

    /** 降噪档位选择行：关闭 / 弱 / 中 / 强 */
    private fun denoiseLevelRow(current: Int, onSelect: (Int) -> Unit): LinearLayout =
        segmentRow(listOf("关闭", "弱", "中", "强"), current.coerceIn(0, 3), onSelect)

    // ------------------------------------------------------------------
    // 异形屏 / 刘海 / 圆角 / 圆形屏适配
    // ------------------------------------------------------------------
    /** 允许内容延伸到刘海 / 挖孔区域（横屏短边），预览铺满全屏 */
    private fun applyDisplayCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * edge-to-edge 布局，把状态栏 / 导航栏 / 挖孔 / 圆角的安全区作为内边距
     * 施加到上下栏，保证按钮不被遮挡；预览区铺满；圆形屏按圆形裁剪预览。
     */
    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val top = binding.barTop
        val baseL = top.paddingLeft
        val baseT = top.paddingTop
        val baseR = top.paddingRight
        val baseB = top.paddingBottom
        val bottom = binding.barBottom
        val bL = bottom.paddingLeft
        val bR = bottom.paddingRight
        val bB = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            top.updatePadding(
                left = baseL + safe.left,
                top = baseT + safe.top,
                right = baseR + safe.right,
                bottom = baseB
            )
            bottom.updatePadding(
                left = bL + safe.left,
                right = bR + safe.right,
                bottom = bB + safe.bottom
            )
            insets
        }
        // 圆形屏：预览区按圆形裁剪，避免四角画面落在可见圆外
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
            resources.configuration.isScreenRound
        ) {
            binding.gridPreview.clipToOutline = true
            binding.gridPreview.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 全屏
    // ------------------------------------------------------------------
    private fun panelOverlays(panel: Panel): List<View> = listOf(
        panel.ui.btnCam, panel.ui.btnProfile, panel.ui.btnPhoto,
        panel.ui.btnRecord, panel.ui.btnMirror, panel.ui.btnRotate,
        panel.ui.tvRec, panel.ui.tvStatus
    )

    private fun enterFullscreen() {
        isFullscreen = true
        binding.barTop.visibility = View.GONE
        binding.barBottom.visibility = View.GONE
        panels.forEach { p -> panelOverlays(p).forEach { it.visibility = View.GONE } }
        WindowInsetsControllerCompat(window, binding.root).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        isFullscreen = false
        binding.barTop.visibility = View.VISIBLE
        binding.barBottom.visibility = View.VISIBLE
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        renderAll()
    }

    override fun onStart() {
        super.onStart()
        // 页面重新可见才恢复预览渲染；后台/Gallery/Playback 覆盖时停掉，
        // 避免 Main 的 TextureView 在不可见状态下继续 lockHardwareCanvas，
        // 与系统 HWUI/GLThread 的 Surface 销毁竞态（真机上出现过 GLThread SIGABRT）。
        previewController.start(app.cameraManager)
        gridKey = null // 强制按当前可见槽位恢复 active/布局
        renderAll()
    }

    override fun onStop() {
        // 先让渲染器丢掉新帧，并等待正在绘制的帧结束，再注销监听，
        // 最后才允许系统销毁 TextureView 的 Surface（避免 HWUI/GLThread 竞态崩溃）
        panels.forEach {
            it.ui.viewWarn.setWarning(false)
            it.renderer.pause()
        }
        previewController.stop(app.cameraManager)
        super.onStop()
    }

    override fun onResume() {
        if (recreateIfThemeChanged()) return
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroy() {
        // 常驻状态机/取流不关闭；只释放本页面的渲染资源
        previewController.stop(app.cameraManager)
        previewController.shutdown()
        panels.forEach { it.renderer.release() }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** 解析当前主题的颜色属性（多主题：浅色/深色/科技蓝） */
    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
