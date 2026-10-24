package com.wheeltech.blindcam.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.Looper
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

        /** 权限请求超时：部分魔改 ROM 会静默吞掉弹窗且不回调，超时后主动返回失败 */
        private const val PERMISSION_TIMEOUT_MS = 12_000L
    }

    /** 主线程 Handler，用于权限请求超时兜底 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 每个设备当前的超时任务（deviceId -> Runnable） */
    private val timeoutRunnables = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()

    /**
     * deviceId -> 已读到的序列号缓存。设备双路传输繁忙时 serialNumber 偶发返回 null，
     * 一旦读到过非空序列号就锁定，避免稳定标识在「序列号/总线节点」之间反复横跳，
     * 进而导致槽位被错误重排。设备拔出时清除。
     */
    private val serialCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

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

    /** 权限回调：按 deviceId 存储，支持多路设备同时请求（单变量会被并发覆盖） */
    private val permissionCallbacks =
        java.util.concurrent.ConcurrentHashMap<Int, (UsbDeviceInfo, Boolean) -> Unit>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableDeviceExtra()
                    Logger.i(TAG, "USB attached: ${device?.deviceName}")
                    refresh()
                    // 仅 UVC 摄像头才触发授权/取流；胎压监测接收器等非视频设备直接忽略
                    device?.let { d ->
                        if (isUvcCamera(d)) {
                            _events.tryEmit(UsbEvent.Attached(toInfo(d)))
                        } else {
                            Logger.i(TAG, "skip non-UVC device: ${d.deviceName} " +
                                "vid=${Integer.toHexString(d.vendorId)} pid=${Integer.toHexString(d.productId)}")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableDeviceExtra()
                    Logger.i(TAG, "USB detached: ${device?.deviceName}")
                    device?.let { serialCache.remove(it.deviceId) }
                    refresh()
                    device?.let { d ->
                        _events.tryEmit(UsbEvent.Detached(d.deviceId))
                    }
                }
                ACTION_USB_PERMISSION -> {
                    // 打印系统返回 intent 的全部 extras，定位无弹窗/device=null 原因
                    val extrasDesc = intent.extras?.let { b ->
                        b.keySet().joinToString { k -> "$k=${b.get(k)}" }
                    } ?: "null"
                    Logger.i(TAG, "permission intent: action=${intent.action} extras=[$extrasDesc]")

                    val device = intent.getParcelableDeviceExtra()
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    Logger.i(TAG, "permission result: granted=$granted device=${device?.deviceName}")
                    refresh()
                    if (device != null) {
                        dispatchPermissionResult(device.deviceId, toInfo(device), granted)
                    } else {
                        // 系统未回传设备对象（直接拒绝）：用当前列表里仍未授权的设备兜底
                        val fallback = _devices.value.firstOrNull { !it.hasPermission }
                        if (fallback != null) {
                            dispatchPermissionResult(fallback.deviceId, fallback, false)
                        }
                    }
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

    /** 注册状态守卫，保证 Activity / Service 多处调用 register 幂等 */
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 注册广播（onStart/onCreate 时调用，幂等） */
    fun register() {
        if (!registered.compareAndSet(false, true)) {
            refresh()
            return
        }
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

    /** 注销广播（幂等） */
    fun unregister() {
        if (!registered.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        // 清理所有等待中的回调与超时任务
        timeoutRunnables.forEach { (id, r) -> mainHandler.removeCallbacks(r) }
        timeoutRunnables.clear()
        permissionCallbacks.clear()
    }

    /**
     * 统一分发权限结果：移除超时任务并调用对应设备的回调。
     * 每个回调保证只被调用一次（广播结果或超时，先到者生效）。
     */
    private fun dispatchPermissionResult(
        deviceId: Int,
        info: UsbDeviceInfo,
        granted: Boolean
    ) {
        timeoutRunnables.remove(deviceId)?.let { mainHandler.removeCallbacks(it) }
        permissionCallbacks.remove(deviceId)?.invoke(info, granted)
    }

    /**
     * 判断设备是否为真正的 UVC 摄像头。
     * 标准 UVC 设备必然提供一个 Video class（USB_CLASS_VIDEO=14）的接口
     * （Video Control + Video Streaming，常伴随 Audio class=1 的麦克风接口）。
     * 胎压监测接收器等通常是 HID(3)/CDC(2,10) 等，没有 Video 接口，据此过滤。
     * 复合设备的 device 级 class 常为 0，因此必须检查「接口级」class 而非设备级。
     */
    private fun isUvcCamera(device: UsbDevice): Boolean {
        return runCatching {
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                    return@runCatching true
                }
            }
            false
        }.getOrDefault(false)
    }

    /** 重新枚举当前所有 USB 设备（仅保留 UVC 摄像头） */
    fun refresh() {
        val all = usbManager?.deviceList?.values ?: emptyList()
        val cameras = all.filter { isUvcCamera(it) }
        val skipped = all.size - cameras.size
        val list = cameras
            .sortedBy { it.deviceId }
            .map { toInfo(it) }
        _devices.update { list }
        Logger.i(TAG, "enumerate: ${list.size} UVC camera(s)" +
            (if (skipped > 0) ", skipped $skipped non-camera USB device(s)" else ""))
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

        // 注册回调（覆盖同设备旧的等待项，先清理旧的超时）
        timeoutRunnables.remove(device.deviceId)?.let { mainHandler.removeCallbacks(it) }
        permissionCallbacks[device.deviceId] = callback

        // 超时兜底：ROM 静默吞掉弹窗/不回调时，12 秒后主动返回失败
        val timeout = Runnable {
            Logger.w(TAG, "permission request timeout deviceId=${device.deviceId}")
            dispatchPermissionResult(device.deviceId, device, false)
        }
        timeoutRunnables[device.deviceId] = timeout
        mainHandler.postDelayed(timeout, PERMISSION_TIMEOUT_MS)

        val intent = Intent(ACTION_USB_PERMISSION).apply {
            // 显式限定本应用，配合 RECEIVER_NOT_EXPORTED 注册
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
            dispatchPermissionResult(device.deviceId, device, false)
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

    private fun toInfo(device: UsbDevice): UsbDeviceInfo {
        val hasPerm = usbManager?.hasPermission(device) == true
        // 读到序列号就锁定到缓存，避免后续偶发读取失败导致稳定标识回退
        UsbDeviceInfo.readSerial(device)?.let { serialCache[device.deviceId] = it }
        val serial = serialCache[device.deviceId]
        val node = device.deviceName?.substringAfterLast('/') ?: device.deviceId.toString()
        return UsbDeviceInfo(
            deviceId = device.deviceId,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceName = device.deviceName ?: "",
            productName = device.productName,
            manufacturerName = device.manufacturerName,
            hasPermission = hasPerm,
            // 授权后序列号可读，稳定标识从总线节点切换为序列号；之后锁定不再回退
            stableKey = UsbDeviceInfo.buildStableKey(
                device.vendorId, device.productId, serial, node
            ),
            rawDevice = device
        )
    }
}
