package com.wheeltech.blindcam.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.core.content.ContextCompat
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * usb-monitor 子模块：
 * - USB 热插拔广播（ATTACHED / DETACHED）
 * - 设备枚举
 * - USB Host 运行时权限申请（PendingIntent 弹窗，无需清单权限）
 * - 打开 [UsbDeviceConnection]（fd 交给 native 层）
 */
class UsbMonitor(private val context: Context) {

    companion object {
        private const val TAG = "usb"
        private const val ACTION_USB_PERMISSION =
            "com.wheeltech.blindcam.USB_PERMISSION"
    }

    /** 热插拔事件 */
    sealed interface UsbEvent {
        data class Attached(val device: UsbDeviceInfo) : UsbEvent
        data class Detached(val deviceId: Int) : UsbEvent
    }

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _devices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val devices: StateFlow<List<UsbDeviceInfo>> = _devices.asStateFlow()

    private val _events = MutableSharedFlow<UsbEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UsbEvent> = _events.asSharedFlow()

    /** 权限回调：deviceId 到 是否授权 */
    private var permissionCallback: ((UsbDeviceInfo, Boolean) -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableDeviceExtra()
                    Logger.i(TAG, "USB attached: ${device?.deviceName}")
                    refresh()
                    device?.let { d ->
                        _events.tryEmit(UsbEvent.Attached(toInfo(d)))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableDeviceExtra()
                    Logger.i(TAG, "USB detached: ${device?.deviceName}")
                    refresh()
                    device?.let { d ->
                        _events.tryEmit(UsbEvent.Detached(d.deviceId))
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableDeviceExtra()
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    Logger.i(TAG, "permission result: granted=$granted device=${device?.deviceName}")
                    refresh()
                    if (device != null) {
                        permissionCallback?.invoke(toInfo(device), granted)
                    }
                    permissionCallback = null
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.getParcelableDeviceExtra(): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    /** 注册广播（onStart/onCreate 时调用） */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        // targetSdk=33：自定义权限广播用 NOT_EXPORTED；系统插拔广播受保护，同样可收到
        if (Build.VERSION.SDK_INT >= VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        refresh()
    }

    /** 注销广播（onStop/onDestroy 时调用） */
    fun unregister() {
        runCatching { context.unregisterReceiver(usbReceiver) }
        permissionCallback = null
    }

    /** 重新枚举当前所有 USB 设备 */
    fun refresh() {
        val list = usbManager?.deviceList?.values
            ?.sortedBy { it.deviceId }
            ?.map { toInfo(it) }
            ?: emptyList()
        _devices.update { list }
        Logger.i(TAG, "enumerate: ${list.size} device(s)")
    }

    /**
     * 申请 USB Host 权限（系统弹窗）
     * @return true 表示已有权限（无需弹窗）；false 表示已发起弹窗，结果走回调
     */
    fun requestPermission(
        device: UsbDeviceInfo,
        callback: (UsbDeviceInfo, Boolean) -> Unit
    ): Boolean {
        val manager = usbManager ?: run {
            callback(device, false)
            return false
        }
        if (manager.hasPermission(device.rawDevice)) {
            callback(device, true)
            return true
        }
        permissionCallback = callback
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, device.deviceId, intent, flags
        )
        runCatching {
            manager.requestPermission(device.rawDevice, pendingIntent)
        }.onFailure {
            Logger.e(TAG, "requestPermission failed", it)
            permissionCallback = null
            callback(device, false)
        }
        return false
    }

    /**
     * 打开 USB 连接。调用方必须持有权限。
     * 返回的连接必须在使用期间保持引用，关闭时调用 [closeConnection]，
     * 一旦连接被回收，native 持有的 fd 会失效。
     */
    fun openConnection(device: UsbDeviceInfo): UsbDeviceConnection? {
        val manager = usbManager ?: return null
        if (!manager.hasPermission(device.rawDevice)) {
            Logger.w(TAG, "openConnection: no permission")
            return null
        }
        return runCatching { manager.openDevice(device.rawDevice) }
            .onFailure { Logger.e(TAG, "openDevice failed", it) }
            .getOrNull()
    }

    fun closeConnection(connection: UsbDeviceConnection?) {
        runCatching { connection?.close() }
    }

    private fun toInfo(device: UsbDevice): UsbDeviceInfo = UsbDeviceInfo(
        deviceId = device.deviceId,
        vendorId = device.vendorId,
        productId = device.productId,
        deviceName = device.deviceName ?: "",
        productName = device.productName,
        manufacturerName = device.manufacturerName,
        hasPermission = usbManager?.hasPermission(device) == true,
        rawDevice = device
    )
}
