package com.wheeltech.blindcam.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.databinding.ActivityMainBinding
import com.wheeltech.blindcam.domain.StreamProfile
import com.wheeltech.blindcam.render.PreviewController
import com.wheeltech.blindcam.render.PreviewRenderer
import com.wheeltech.blindcam.service.UvcForegroundService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主界面（横屏车机）：
 * - 顶部常驻工具栏：刷新 / 单双屏 / 设置 / 全屏
 * - 左右双路预览，悬浮「选择摄像头 / 档位 / 拍照 / 录像」
 * - 底部常驻设备栏
 * - 前台不熄屏；前台服务保活，切后台不中断取流
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private lateinit var leftRenderer: PreviewRenderer
    private lateinit var rightRenderer: PreviewRenderer
    private lateinit var previewController: PreviewController

    private var isFullscreen = false

    // ---- 运行时权限：相机（USB 权限前提） ----
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.ensurePreviewing()
            } else {
                toast(getString(R.string.dialog_grant_camera))
            }
        }

    // ---- 通知权限（Android 13+ 前台服务通知） ----
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论结果都启动保活服务（targetSdk33 下通知权限不影响 FGS connectedDevice）
            UvcForegroundService.start(this)
        }

    // ---- SAF 选择照片 / 录像保存目录 ----
    private val photoDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                lifecycleScope.launch { app.settingsRepository.setPhotoDirUri(uri.toString()) }
                toast("照片保存位置已更新")
            }
        }

    private val videoDirLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                app.mediaSaver.persistTreeUri(uri)
                lifecycleScope.launch { app.settingsRepository.setVideoDirUri(uri.toString()) }
                toast("录像保存位置已更新")
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

        // 前台不熄屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupRenderers()
        setupButtons()
        observeUi()

        // 注册 USB 热插拔并立即枚举（幂等，与前台服务双保险）
        app.cameraManager.registerUsbMonitor()

        // 启动保活服务（含通知权限）
        ensureNotificationPermissionThenStartService()

        // 相机权限：USB 权限申请的前提
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.ensurePreviewing()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupRenderers() {
        leftRenderer = PreviewRenderer(binding.textureLeft)
        rightRenderer = PreviewRenderer(binding.textureRight)
        previewController = PreviewController(mapOf(0 to leftRenderer, 1 to rightRenderer))
        previewController.start(app.cameraManager)

        // 应用镜像设置
        lifecycleScope.launch {
            val s = app.settingsRepository.settings.first()
            leftRenderer.mirrorH = s.mirrorLeft
            rightRenderer.mirrorH = s.mirrorRight
            leftRenderer.flipV = s.flipLeft
            rightRenderer.flipV = s.flipRight
        }
    }

    private fun setupButtons() {
        binding.btnRefresh.setOnClickListener { viewModel.refreshDevices() }
        binding.btnLayout.setOnClickListener { viewModel.cycleLayoutMode() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
        binding.btnFullscreen.setOnClickListener { enterFullscreen() }

        // 点击画面（非控件区域）：全屏时退出全屏
        binding.previewArea.setOnClickListener {
            if (isFullscreen) exitFullscreen()
        }

        // 左路
        binding.btnCamLeft.setOnClickListener { showCameraPicker(0) }
        binding.btnProfileLeft.setOnClickListener { showProfilePicker(0) }
        binding.btnPhotoLeft.setOnClickListener { viewModel.takePhoto(0) }
        binding.btnRecordLeft.setOnClickListener { viewModel.toggleRecording(0) }

        // 右路
        binding.btnCamRight.setOnClickListener { showCameraPicker(1) }
        binding.btnProfileRight.setOnClickListener { showProfilePicker(1) }
        binding.btnPhotoRight.setOnClickListener { viewModel.takePhoto(1) }
        binding.btnRecordRight.setOnClickListener { viewModel.toggleRecording(1) }
    }

    private fun observeUi() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        // 顶部状态（精简，仅用于无设备时）
                        binding.tvEmpty.visibility =
                            if (state.slots.none { it.streaming } &&
                                viewModel.uiState.value.unassignedDevices.isEmpty()
                            ) View.VISIBLE else View.GONE

                        // 每路标签 / 档位
                        bindSlot(0)
                        bindSlot(1)

                        // 布局模式控制面板
                        val leftVisible = state.layoutMode != AppSettings.LayoutMode.RIGHT_ONLY
                        val rightVisible = state.layoutMode != AppSettings.LayoutMode.LEFT_ONLY
                        binding.panelLeft.visibility = if (leftVisible) View.VISIBLE else View.GONE
                        binding.panelRight.visibility = if (rightVisible) View.VISIBLE else View.GONE

                        renderDeviceChips()
                    }
                }
                launch {
                    viewModel.events.collect { msg -> toast(msg) }
                }
            }
        }
    }

    private fun bindSlot(slot: Int) {
        val state = viewModel.uiState.value.slots[slot]
        val camBtn = if (slot == 0) binding.btnCamLeft else binding.btnCamRight
        val profileBtn = if (slot == 0) binding.btnProfileLeft else binding.btnProfileRight
        val recBtn = if (slot == 0) binding.btnRecordLeft else binding.btnRecordRight
        val recLabel = if (slot == 0) binding.tvRecLeft else binding.tvRecRight

        val name = state.device?.displayText
            ?: if (slot == 0) getString(R.string.camera_left) else getString(R.string.camera_right)
        camBtn.text = name
        profileBtn.text = state.activeProfile?.let {
            "${it.width}x${it.height}·${it.fps}fps"
        } ?: "—"

        // 录像按钮 / 指示
        recBtn.setImageResource(
            if (state.recording) R.drawable.ic_stop else R.drawable.ic_videocam
        )
        recBtn.backgroundTintList =
            if (state.recording) ColorStateList.valueOf(Color.parseColor("#E53935"))
            else null
        recLabel.visibility = if (state.recording) View.VISIBLE else View.GONE
    }

    /** 底部设备 chips：摄像头名 + 授权状态点 */
    private fun renderDeviceChips() {
        val container = binding.llDeviceList
        container.removeAllViews()
        val devices = app.cameraManager.devices.value
        for (device in devices) {
            val chip = TextView(this).apply {
                text = "  ${device.displayText}  "
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.app_on_surface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
                val dot = if (device.hasPermission) "● " else "○ "
                text = dot + device.displayText
                setTextColor(
                    if (device.hasPermission)
                        ContextCompat.getColor(this@MainActivity, R.color.status_ok)
                    else ContextCompat.getColor(this@MainActivity, R.color.status_err)
                )
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            container.addView(chip, lp)
        }
    }

    // ---- 选择摄像头对话框 ----
    private fun showCameraPicker(slot: Int) {
        val devices = app.cameraManager.devices.value
        if (devices.isEmpty()) {
            toast(getString(R.string.no_device))
            return
        }
        val names = devices.map { it.displayText }.toTypedArray()
        val current = app.cameraManager.slotDevice(slot)?.deviceId
        val checked = devices.indexOfFirst { it.deviceId == current }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_choose_camera_title)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                viewModel.assignDevice(slot, devices[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ---- 分辨率 / 帧率对话框（仅列该摄像头支持档位） ----
    private fun showProfilePicker(slot: Int) {
        val camera = app.cameraManager.getCamera(slot)
        val caps = camera?.capabilities
        if (caps.isNullOrEmpty()) {
            toast("该摄像头暂无可选档位")
            return
        }
        val labels = caps.map { "${it.width}×${it.height} · ${it.fps}fps" }.toTypedArray()
        val checked = caps.indexOfFirst { it == camera.currentProfile }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_profile_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.switchProfile(slot, caps[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ---- 设置对话框 ----
    private fun showSettingsDialog() {
        lifecycleScope.launch {
            val s = app.settingsRepository.settings.first()
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
            }
            // 内容超出屏幕时可上下滑动
            val scrollView = ScrollView(this@MainActivity).apply {
                addView(container)
                isVerticalScrollBarEnabled = true
            }

            // 主题
            container.addView(sectionLabel("主题"))
            val themeGroup = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.HORIZONTAL
            }
            AppSettings.ThemeMode.entries.forEachIndexed { i, m ->
                val label = when (m) {
                    AppSettings.ThemeMode.SYSTEM -> getString(R.string.theme_system)
                    AppSettings.ThemeMode.DARK -> getString(R.string.theme_dark)
                    AppSettings.ThemeMode.LIGHT -> getString(R.string.theme_light)
                }
                val rb = RadioButton(this@MainActivity).apply {
                    text = label
                    id = i
                }
                themeGroup.addView(rb)
            }
            themeGroup.check(s.themeMode.ordinal)
            container.addView(themeGroup)

            // 布局
            container.addView(sectionLabel("显示布局"))
            val layoutGroup = RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.HORIZONTAL
            }
            AppSettings.LayoutMode.entries.forEachIndexed { i, m ->
                val label = when (m) {
                    AppSettings.LayoutMode.DUAL -> getString(R.string.layout_dual)
                    AppSettings.LayoutMode.LEFT_ONLY -> getString(R.string.layout_left)
                    AppSettings.LayoutMode.RIGHT_ONLY -> getString(R.string.layout_right)
                }
                layoutGroup.addView(RadioButton(this@MainActivity).apply {
                    text = label
                    id = i
                })
            }
            layoutGroup.check(s.layoutMode.ordinal)
            container.addView(layoutGroup)

            // 保存位置
            container.addView(sectionLabel("照片保存位置"))
            val photoRow = dirRow(s.photoDirUri) { photoDirLauncher.launch(null) }
            container.addView(photoRow)

            container.addView(sectionLabel("录像保存位置"))
            val videoRow = dirRow(s.videoDirUri) { videoDirLauncher.launch(null) }
            container.addView(videoRow)

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.settings_title)
                .setView(scrollView)
                .setPositiveButton(R.string.common_confirm) { _, _ ->
                    val theme = AppSettings.ThemeMode.entries[themeGroup.checkedRadioButtonId.coerceAtLeast(0)]
                    val layout = AppSettings.LayoutMode.entries[layoutGroup.checkedRadioButtonId.coerceAtLeast(0)]
                    lifecycleScope.launch {
                        app.settingsRepository.setThemeMode(theme)
                        app.settingsRepository.setLayoutMode(layout)
                        app.applyTheme(theme)
                        // 显式让当前 Activity 应用夜间模式；若未触发 recreate 则手动重建
                        val applied = delegate.applyDayNight()
                        if (!applied) recreate()
                        viewModel.applyLayoutMode(layout)
                    }
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        }
    }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.app_on_surface_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(14), 0, dp(4))
        }

    private fun dirRow(uri: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = if (uri.isBlank()) getString(R.string.dir_default) else Uri.parse(uri).lastPathSegment ?: uri
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_blue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_chip)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }

    /** 全屏时需隐藏的覆盖控件（摄像头名/档位/拍照录像/录制指示） */
    private fun overlayControls(): List<View> = listOf(
        binding.btnCamLeft, binding.btnProfileLeft,
        binding.btnPhotoLeft, binding.btnRecordLeft, binding.tvRecLeft,
        binding.btnCamRight, binding.btnProfileRight,
        binding.btnPhotoRight, binding.btnRecordRight, binding.tvRecRight
    )

    // ---- 全屏 ----
    private fun enterFullscreen() {
        isFullscreen = true
        binding.barTop.visibility = View.GONE
        binding.barBottom.visibility = View.GONE
        overlayControls().forEach { it.visibility = View.GONE }
        WindowInsetsControllerCompat(window, binding.root).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullscreen() {
        isFullscreen = false
        binding.barTop.visibility = View.VISIBLE
        binding.barBottom.visibility = View.VISIBLE
        overlayControls().forEach { it.visibility = View.VISIBLE }
        // 恢复后重新绑定（录像指示等状态）
        bindSlot(0); bindSlot(1)
        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    // ---- 通知权限 / 服务 ----
    private fun ensureNotificationPermissionThenStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        UvcForegroundService.start(this)
    }

    override fun onResume() {
        super.onResume()
        app.cameraManager.refreshDevices()
        viewModel.refreshState()
        viewModel.resumePreviewing()
    }

    override fun onDestroy() {
        // 后台保活：不关闭摄像头、不注销 USB 监听（由前台服务持有）
        previewController.stop(app.cameraManager)
        previewController.shutdown()
        leftRenderer.release()
        rightRenderer.release()
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
