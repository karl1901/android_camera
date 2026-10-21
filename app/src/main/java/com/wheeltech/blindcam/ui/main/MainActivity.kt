package com.wheeltech.blindcam.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.databinding.ActivityMainBinding
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.render.PreviewController
import com.wheeltech.blindcam.render.PreviewRenderer
import com.wheeltech.blindcam.usb.UsbDeviceInfo
import kotlinx.coroutines.launch

/**
 * 主界面：
 * - 阶段1：USB 设备枚举、权限申请、布局切换、TextureView 槽位占位
 * - 阶段2：接入左右路预览（PreviewRenderer 已就位）
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var app: UvcApp

    /** 左右槽位渲染器（TextureView 绑定） */
    private lateinit var leftRenderer: PreviewRenderer
    private lateinit var rightRenderer: PreviewRenderer

    /** 渲染总控：接收 native 帧并解码上屏 */
    private lateinit var previewController: PreviewController

    /** CAMERA 权限申请结果回调（UVC 设备必须先有 CAMERA 权限，系统才会弹 USB 授权框） */
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            binding.tvLog.text = "相机权限已授予"
            // CAMERA 就绪：统一自检，已授权设备开流，未授权设备弹 USB 授权框
            viewModel.ensurePreviewing()
        } else {
            binding.tvLog.text = "必须授予“相机”权限才能访问 USB 摄像头（UVC 设备的系统强制要求）"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as UvcApp

        binding.btnRefresh.setOnClickListener { viewModel.refreshDevices() }
        binding.btnPermission.setOnClickListener {
            // 先确保 CAMERA 权限，再请求 USB 权限
            ensureCameraPermissionThen { viewModel.requestFirstPermission() }
        }
        binding.btnToggleLayout.setOnClickListener { viewModel.cycleLayoutMode() }

        // 由 Activity 持有 USB 广播生命周期（前台实时枚举）
        app.cameraManager.registerUsbMonitor()

        // 渲染：绑定左右 TextureView，注册帧监听
        leftRenderer = PreviewRenderer(binding.textureLeft)
        rightRenderer = PreviewRenderer(binding.textureRight)
        previewController = PreviewController(
            mapOf(
                UvcCameraManager.SLOT_LEFT to leftRenderer,
                UvcCameraManager.SLOT_RIGHT to rightRenderer
            )
        )
        previewController.start(app.cameraManager)

        // 若由 USB 插入拉起，首次进入也刷新一次
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.refreshDevices()
        }

        observeUi()

        // 进入页面：CAMERA 已授予则统一自检并开预览；否则先申请 CAMERA
        if (hasCameraPermission()) {
            viewModel.ensurePreviewing()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 是否持有 CAMERA 权限 */
    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** 确保已授予 CAMERA 权限后再执行 [action]；未授予则先弹系统权限框 */
    private fun ensureCameraPermissionThen(action: () -> Unit) {
        if (hasCameraPermission()) {
            action()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun observeUi() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvStatus.text = state.statusText
                    binding.tvLog.text = state.logLine
                    renderDeviceList(state.devices)
                    applyLayout(state.layoutMode)
                }
            }
        }
    }

    private fun renderDeviceList(devices: List<UsbDeviceInfo>) {
        val container = binding.llDeviceList
        container.removeAllViews()
        if (devices.isEmpty()) {
            container.addView(buildChip("无设备", false, null))
            return
        }
        devices.forEach { info ->
            container.addView(buildChip(info.displayText, info.hasPermission, info))
        }
    }

    private fun buildChip(text: String, granted: Boolean, info: UsbDeviceInfo?): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(20, 10, 20, 10)
            gravity = Gravity.CENTER
            setBackgroundColor(if (granted) 0xFF2E7D32.toInt() else 0xFF37474F.toInt())
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
            layoutParams = lp
            if (info != null) {
                setOnClickListener {
                    // 点击设备条：未授权则先确保 CAMERA 权限再申请 USB 权限；
                    // 阶段2在此绑定左/右槽位并开流
                    if (!info.hasPermission) {
                        ensureCameraPermissionThen { viewModel.requestPermission(info) }
                    }
                }
            }
        }
    }

    private fun applyLayout(mode: AppSettings.LayoutMode) {
        binding.panelLeft.visibility =
            if (mode == AppSettings.LayoutMode.RIGHT_ONLY) View.GONE else View.VISIBLE
        binding.panelRight.visibility =
            if (mode == AppSettings.LayoutMode.LEFT_ONLY) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销帧监听、释放渲染器
        if (this::previewController.isInitialized) {
            previewController.stop(app.cameraManager)
        }
        if (this::leftRenderer.isInitialized) leftRenderer.release()
        if (this::rightRenderer.isInitialized) rightRenderer.release()

        // 当前阶段 Activity 退出即关闭全部设备并注销广播；
        // 阶段7前台服务接管时，设备/广播生命周期迁移到 Service
        app.cameraManager.closeAll()
        app.cameraManager.unregisterUsbMonitor()
    }
}
