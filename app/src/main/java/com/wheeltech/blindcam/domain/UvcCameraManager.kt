package com.wheeltech.blindcam.domain

import android.content.Context
import com.wheeltech.blindcam.native.NativeUvc
import com.wheeltech.blindcam.usb.UsbDeviceInfo
import com.wheeltech.blindcam.usb.UsbMonitor
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Domain 层全局管理器：多路 UVC 设备生命周期、USB 插拔、帧分发的唯一入口。
 *
 * 分层约束：
 * - ViewModel 只调本类，不直接碰 JNI / USB 句柄
 * - 本类不持有任何 Android View / Context(Activity)，只持有 applicationContext
 */
class UvcCameraManager private constructor(context: Context) {

    companion object {
        private const val TAG = "manager"

        /** 最大同时显示路数（1/2/3/4 路自动宫格；架构上可继续扩展） */
        const val MAX_SLOTS = 4
        const val SLOT_LEFT = 0
        const val SLOT_RIGHT = 1

        /** 兼容旧引用 */
        const val SLOT_COUNT = MAX_SLOTS

        /** 默认取流参数（AHD 转 UVC 解码盒常见 1080p30 MJPEG；后续按能力协商） */
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 480
        const val DEFAULT_FPS = 30

        @Volatile
        private var instance: UvcCameraManager? = null

        fun get(context: Context): UvcCameraManager {
            return instance ?: synchronized(this) {
                instance ?: UvcCameraManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /** 帧接收方：渲染模块、录像模块各自注册；回调在 native streaming 线程，禁止耗时 */
    fun interface FrameListener {
        fun onFrame(frame: CameraFrame)
    }

    /** 打开设备结果 */
    sealed class OpenResult {
        data class Success(val slot: Int) : OpenResult()
        data class Failure(val slot: Int, val reason: String) : OpenResult()
    }

    private val appContext: Context = context.applicationContext
    val usbMonitor = UsbMonitor(appContext)

    private val cameras = arrayOfNulls<UvcCamera>(SLOT_COUNT)
    private val frameListeners = java.util.concurrent.CopyOnWriteArrayList<FrameListener>()

    /** 取流/停流串行化锁，防止多路并发协商互相干扰 */
    private val streamLock = Any()

    private var nativeCtx: Long = 0L

    // ---- USB 状态透传（ViewModel 观察） ----
    val devices: StateFlow<List<UsbDeviceInfo>> = usbMonitor.devices
    val usbEvents: SharedFlow<UsbMonitor.UsbEvent> = usbMonitor.events

    /** 初始化 native 上下文（Application onCreate 调一次即可） */
    fun init() {
        if (nativeCtx != 0L) return
        if (!NativeUvc.loaded) {
            Logger.w(TAG, "native 库未加载，设备打开功能不可用")
        }
        // getExternalFilesDir 会创建 app 可直接读写的外部私有目录，无需存储权限
        val diagDir = appContext.getExternalFilesDir(null)
        val diagPath = java.io.File(diagDir, "diag.txt").absolutePath
        nativeCtx = NativeUvc.nativeInit(diagPath)
        Logger.i(TAG, "native context = $nativeCtx")
    }

    fun registerUsbMonitor() = usbMonitor.register()
    fun unregisterUsbMonitor() = usbMonitor.unregister()
    fun refreshDevices() = usbMonitor.refresh()

    fun requestPermission(
        device: UsbDeviceInfo,
        callback: (UsbDeviceInfo, Boolean) -> Unit
    ) = usbMonitor.requestPermission(device, callback)

    /** 协程版：挂起等待授权结果（true=已授权） */
    suspend fun requestPermission(device: UsbDeviceInfo): Boolean =
        suspendCancellableCoroutine { cont ->
            usbMonitor.requestPermission(device) { _, granted ->
                if (cont.isActive) cont.resume(granted)
            }
        }

    /** 某槽位当前分配的设备（null=空槽） */
    fun slotDevice(slot: Int): UsbDeviceInfo? = cameras[slot]?.info

    /**
     * 切换某槽位档位：停流 → 用新档位重新协商（开流由调用方重试逻辑处理）。
     * 仅允许传入该设备能力枚举范围内的 [StreamProfile]。
     */
    fun switchProfile(slot: Int, profile: StreamProfile) {
        val camera = cameras[slot] ?: return
        synchronized(streamLock) {
            if (camera.streaming) {
                NativeUvc.nativeStopStream(camera.nativeHandle)
                camera.streaming = false
            }
            camera.currentProfile = profile
        }
    }

    /** 打开一路摄像头到指定槽位（必须已有 USB 权限）。
     *  全部 native 设备操作都在 [streamLock] 内串行，避免多路并发协商 /
     *  共享 libusb 事件线程时互相干扰。synchronized 可重入。 */
    fun openCamera(slot: Int, device: UsbDeviceInfo): OpenResult = synchronized(streamLock) {
        if (slot !in 0 until SLOT_COUNT) return OpenResult.Failure(slot, "槽位非法")
        if (nativeCtx == 0L) {
            return OpenResult.Failure(
                slot,
                "native 初始化失败（可能为 stub 桩库或库加载失败）"
            )
        }
        // 槽位已被占用先释放
        closeCamera(slot)

        val connection = usbMonitor.openConnection(device)
            ?: return OpenResult.Failure(
                slot,
                "打开 USB 连接失败：未授权 / 被占用 / SELinux 拦截（可尝试 adb shell setenforce 0）"
            )

        val handle = try {
            NativeUvc.nativeOpenFd(
                nativeCtx, connection.fileDescriptor,
                device.vendorId, device.productId, slot
            )
        } catch (t: Throwable) {
            Logger.e(TAG, "nativeOpenFd 异常", t)
            usbMonitor.closeConnection(connection)
            return OpenResult.Failure(slot, "native 打开异常: ${t.message}")
        }

        if (handle == 0L) {
            usbMonitor.closeConnection(connection)
            return OpenResult.Failure(
                slot,
                "UVC 打开失败：检查 SELinux 是否 enforcing、内核是否开启 UVC 驱动"
            )
        }

        val cam = UvcCamera(slot, device, connection, handle)
        cameras[slot] = cam
        runCatching {
            cam.usbSpeed = NativeUvc.nativeGetDeviceSpeed(handle)
            Logger.i(
                TAG,
                "usb speed slot=$slot speed=${cam.usbSpeed} " +
                    when (cam.usbSpeed) {
                        1 -> "(Full 12Mbps)"
                        2 -> "(High 480Mbps / USB2.0)"
                        3 -> "(Super 5Gbps / USB3.0)"
                        4 -> "(Super+ 10Gbps)"
                        else -> "(unknown)"
                    }
            )
        }
        // 查询摄像头支持的分辨率/帧率（失败不阻塞打开，后续可重试）
        runCatching {
            val raw = NativeUvc.nativeQueryCapabilities(handle)
            cam.capabilities = StreamProfile.parseCapabilities(raw)
            Logger.i(
                TAG,
                "capabilities slot=$slot count=${cam.capabilities.size} " +
                    cam.capabilities.take(8).joinToString { it.display }
            )
        }.onFailure { Logger.w(TAG, "queryCapabilities failed: ${it.message}") }
        Logger.i(TAG, "camera opened slot=$slot ${device.displayText}")
        return OpenResult.Success(slot)
    }

    /** 查找设备当前所在槽位（按稳定标识，不受系统 deviceId 复用影响），未打开返回 -1 */
    fun findDeviceSlot(device: UsbDeviceInfo): Int {
        for (slot in 0 until SLOT_COUNT) {
            if (cameras[slot]?.info?.stableKey == device.stableKey) return slot
        }
        return -1
    }

    /**
     * 把设备分配到指定槽位（用户自定义左右映射的核心）。
     * - 若设备已在其他槽位打开：连同 native 句柄/连接一起移动，更新 cameraId，
     *   取流不中断（帧回调自动命中新槽位）
     * - 若目标槽位已有其他设备：先关闭该设备
     * - 若设备尚未打开：直接 openCamera 到目标槽位
     */
    fun assignDeviceToSlot(targetSlot: Int, device: UsbDeviceInfo): OpenResult = synchronized(streamLock) {
        if (targetSlot !in 0 until SLOT_COUNT)
            return OpenResult.Failure(targetSlot, "槽位非法")
        val currentSlot = findDeviceSlot(device)
        if (currentSlot == targetSlot) return OpenResult.Success(targetSlot)

        // 目标槽位有其他设备，先关闭
        val occupant = cameras[targetSlot]
        if (occupant != null && occupant.info.stableKey != device.stableKey) {
            closeCamera(targetSlot)
        }

        if (currentSlot >= 0) {
            // 已打开：移动现有 UvcCamera（slot 为 val，重建等价对象）
            val cam = cameras[currentSlot]!!
            cameras[currentSlot] = null
            val moved = UvcCamera(targetSlot, cam.info, cam.connection, cam.nativeHandle).apply {
                capabilities = cam.capabilities
                currentProfile = cam.currentProfile
                streaming = cam.streaming
            }
            cameras[targetSlot] = moved
            NativeUvc.nativeSetCameraId(cam.nativeHandle, targetSlot)
            Logger.i(TAG, "moved device ${device.deviceId} slot $currentSlot->$targetSlot")
            return OpenResult.Success(targetSlot)
        }

        // 未打开：直接打开到目标槽位
        return openCamera(targetSlot, device)
    }

    /**
     * 启动某路取流并分发帧。
     * 帧通过 [FrameListener] 分发（渲染 + 录像），回调线程为 native 线程。
     * 必须显式传入要协商的 [profile]（来自该设备的能力列表）。
     */
    fun startStream(slot: Int, profile: StreamProfile): Boolean {
        // 槽位可能在等待锁期间被关闭/换设备，必须在锁内重新取
        synchronized(streamLock) {
            val camera = cameras[slot] ?: return false
            if (camera.streaming && camera.currentProfile == profile) return true
            if (camera.streaming) {
                NativeUvc.nativeStopStream(camera.nativeHandle)
                camera.streaming = false
            }
            val ret = NativeUvc.nativeStartStream(
                camera.nativeHandle, profile.width, profile.height, profile.fps
            ) { cameraId, data, w, h, format ->
                // data 由 native 每路复用，本回调返回后会被下一帧覆盖；
                // 各监听者须在 onFrame 内自行拷贝（渲染/录像均已拷贝）。
                val frame = CameraFrame(cameraId, data, w, h, format)
                frameListeners.forEach { listener ->
                    runCatching { listener.onFrame(frame) }
                        .onFailure { Logger.w(TAG, "frame listener error", it) }
                }
            }
            if (ret != 0) {
                Logger.e(TAG, "startStream slot=$slot profile=$profile failed code=$ret")
                return false
            }
            camera.currentProfile = profile
            camera.streaming = true
            return true
        }
    }

    /** 便捷：按 width/height/fps 启动（用于 ViewModel 已选定 profile 的场景） */
    fun startStream(slot: Int, width: Int, height: Int, fps: Int): Boolean =
        startStream(slot, StreamProfile(width, height, fps))

    /** 停止某路取流 */
    fun stopStream(slot: Int) {
        synchronized(streamLock) {
            val camera = cameras[slot] ?: return
            if (camera.streaming) {
                NativeUvc.nativeStopStream(camera.nativeHandle)
                camera.streaming = false
            }
        }
    }

    /** 关闭并释放一路摄像头（断开/切槽时调用） */
    fun closeCamera(slot: Int) = synchronized(streamLock) {
        val camera = cameras[slot] ?: return@synchronized
        runCatching {
            if (camera.streaming) NativeUvc.nativeStopStream(camera.nativeHandle)
            if (camera.nativeHandle != 0L) NativeUvc.nativeClose(camera.nativeHandle)
        }.onFailure { Logger.w(TAG, "closeCamera native error", it) }
        usbMonitor.closeConnection(camera.connection)
        camera.connection = null
        camera.nativeHandle = 0L
        camera.streaming = false
        cameras[slot] = null
        Logger.i(TAG, "camera closed slot=$slot")
    }

    fun closeAll() {
        repeat(SLOT_COUNT) { closeCamera(it) }
    }

    fun addFrameListener(listener: FrameListener) {
        frameListeners.addIfAbsent(listener)
    }

    fun removeFrameListener(listener: FrameListener) {
        frameListeners.remove(listener)
    }

    fun getCamera(slot: Int): UvcCamera? = cameras[slot]

    /** 应用退出时释放 */
    fun release() {
        closeAll()
        frameListeners.clear()
        usbMonitor.unregister()
        if (nativeCtx != 0L) {
            NativeUvc.nativeRelease(nativeCtx)
            nativeCtx = 0L
        }
    }
}
