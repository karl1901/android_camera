package com.wheeltech.blindcam.ui.main

import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.data.AppSettings
import com.wheeltech.blindcam.databinding.ActivityMainBinding
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as UvcApp

        binding.btnRefresh.setOnClickListener { viewModel.refreshDevices() }
        binding.btnPermission.setOnClickListener { viewModel.requestFirstPermission() }
        binding.btnToggleLayout.setOnClickListener { viewModel.cycleLayoutMode() }

        // 由 Activity 持有 USB 广播生命周期（前台实时枚举）
        app.cameraManager.registerUsbMonitor()

        // 若由 USB 插入拉起，首次进入也刷新一次
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            viewModel.refreshDevices()
        }

        observeUi()
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
                    // 点击设备条：未授权则申请权限；阶段2在此绑定左/右槽位并开流
                    if (!info.hasPermission) viewModel.requestPermission(info)
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
        // 注意：前台服务接管时广播注册会迁移到 Service（阶段7处理），
        // 当前阶段 Activity 退出即注销
        app.cameraManager.unregisterUsbMonitor()
    }
}
