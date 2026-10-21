package com.wheeltech.blindcam.domain

import android.content.Context
import com.wheeltech.blindcam.native.NativeUvc
import com.wheeltech.blindcam.usb.UsbDeviceInfo
import com.wheeltech.blindcam.usb.UsbMonitor
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

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

        /** 槽位：0=左盲区 1=右盲区（2=预留第三路 3=预留第四路） */
        const val SLOT_LEFT = 0
        const val SLOT_RIGHT = 1
        const val SLOT_COUNT = 2

        /** 默认取流参数（AHD 转 UVC 解码盒常见 1080p30 MJPEG；后续按能力协商） */
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
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

    val framePool = FramePool()

    private val cameras = arrayOfNulls<UvcCamera>(SLOT_COUNT)
    private val frameListeners = java.util.concurrent.CopyOnWriteArrayList<FrameListener>()

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
        nativeCtx = NativeUvc.nativeInit()
        Logger.i(TAG, "native context = $nativeCtx")
    }

    fun registerUsbMonitor() = usbMonitor.register()
    fun unregisterUsbMonitor() = usbMonitor.unregister()
    fun refreshDevices() = usbMonitor.refresh()

    fun requestPermission(
        device: UsbDeviceInfo,
        callback: (UsbDeviceInfo, Boolean) -> Unit
    ) = usbMonitor.requestPermission(device, callback)

    /** 打开一路摄像头到指定槽位（必须已有 USB 权限） */
    fun openCamera(slot: Int, device: UsbDeviceInfo): OpenResult {
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

        cameras[slot] = UvcCamera(slot, device, connection, handle)
        Logger.i(TAG, "camera opened slot=$slot ${device.displayText}")
        return OpenResult.Success(slot)
    }

    /**
     * 启动某路取流并分发帧。
     * 帧通过 [FrameListener] 分发（渲染 + 录像），回调线程为 native 线程。
     */
    fun startStream(
        slot: Int,
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        fps: Int = DEFAULT_FPS
    ): Boolean {
        val camera = cameras[slot] ?: return false
        if (camera.streaming) return true
        val ret = NativeUvc.nativeStartStream(
            camera.nativeHandle, width, height, fps
        ) { cameraId, data, w, h, format ->
            // 回调内禁止耗时：只做包装 + 分发给监听者
            val frame = CameraFrame(cameraId, data, w, h, format, framePool)
            frameListeners.forEach { listener ->
                runCatching { listener.onFrame(frame) }
                    .onFailure { Logger.w(TAG, "frame listener error", it) }
            }
        }
        if (ret != 0) {
            Logger.e(TAG, "startStream slot=$slot failed code=$ret")
            return false
        }
        camera.streaming = true
        return true
    }

    fun stopStream(slot: Int) {
        val camera = cameras[slot] ?: return
        if (camera.streaming) {
            NativeUvc.nativeStopStream(camera.nativeHandle)
            camera.streaming = false
        }
    }

    /** 关闭并释放一路摄像头（断开/切槽时调用） */
    fun closeCamera(slot: Int) {
        val camera = cameras[slot] ?: return
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
