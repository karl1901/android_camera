package com.wheeltech.blindcam.native

import com.wheeltech.blindcam.util.Logger

/**
 * JNI 桥（与 app/src/main/cpp/uvc_native.cpp 一一对应）。
 *
 * 约定：
 * - 0L 句柄 = 无效；错误码 0 成功，负数失败（-100 = stub 桩库未接入 libuvc）
 * - 所有 [FrameCallback.onFrame] 运行在 libuvc 内部线程，回调内禁止做耗时操作
 *
 * 分层约束：本对象只允许 Domain 层 UvcCameraManager 调用，UI / ViewModel 禁止直接碰。
 */
object NativeUvc {

    private const val TAG = "native"
    private const val LIB_NAME = "uvc_native"

    /** native 库是否成功加载（true=真实 libuvc 后端；false=stub 桩库/加载失败） */
    @Volatile
    var loaded: Boolean = false
        private set

    /** libuvc 后端是否编译进来（stub 构建时为 false） */
    @JvmStatic
    private external fun nativeIsBackendAvailable(): Boolean

    init {
        runCatching {
            System.loadLibrary(LIB_NAME)
            loaded = true
            Logger.i(TAG, "libuvc backend available = ${nativeIsBackendAvailable()}")
        }.onFailure {
            loaded = false
            Logger.e(TAG, "loadLibrary($LIB_NAME) failed", it)
        }
    }

    /** 帧回调：cameraId 为左右路槽位编号（0=左 1=右） */
    fun interface FrameCallback {
        fun onFrame(cameraId: Int, data: ByteArray, width: Int, height: Int, frameFormat: Int)
    }

    /** 创建 uvc_context，返回上下文句柄；0 表示失败。diagPath 为诊断文件完整路径 */
    external fun nativeInit(diagPath: String): Long

    /** 释放 uvc_context */
    external fun nativeRelease(ctxHandle: Long)

    /**
     * 用 UsbDeviceConnection 的 fd 打开一路 UVC 设备
     * @param fd       [android.hardware.usb.UsbDeviceConnection.getFileDescriptor]
     * @param cameraId 左右路槽位编号
     * @return 会话句柄，0 表示失败
     */
    external fun nativeOpenFd(
        ctxHandle: Long, fd: Int, vid: Int, pid: Int, cameraId: Int
    ): Long

    /** 关闭一路会话 */
    external fun nativeClose(sessionHandle: Long)

    /**
     * 启动 MJPEG 流
     * @return 0 成功；-1/-2 参数错；-3 协商格式失败；-4 启动失败；-100 stub 桩库
     */
    external fun nativeStartStream(
        sessionHandle: Long,
        width: Int, height: Int, fps: Int,
        callback: FrameCallback
    ): Int

    /** 停止取流 */
    external fun nativeStopStream(sessionHandle: Long)

    /** libuvc 中 UVC_FRAME_FORMAT_MJPEG 的枚举值，供上层判断帧格式 */
    @JvmStatic
    external fun nativeGetMjpegFormat(): Int

    /**
     * 查询摄像头支持的 MJPEG 分辨率与帧率。
     * 返回编码串 "MJPEG;WxH:fps,fps;WxH:fps"，多个格式用 '|' 分隔。
     */
    external fun nativeQueryCapabilities(sessionHandle: Long): String

    /** 更新会话 cameraId（设备在槽位间重新映射时调用） */
    external fun nativeSetCameraId(sessionHandle: Long, cameraId: Int)

    /** USB 链路速度：1=Full 12Mbps, 2=High 480Mbps, 3=Super 5Gbps, 4=Super+ 10Gbps */
    external fun nativeGetDeviceSpeed(sessionHandle: Long): Int
}
