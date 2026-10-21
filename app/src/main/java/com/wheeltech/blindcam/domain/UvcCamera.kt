package com.wheeltech.blindcam.domain

import android.hardware.usb.UsbDeviceConnection
import com.wheeltech.blindcam.usb.UsbDeviceInfo

/**
 * 一路 UVC 摄像头会话（Domain 层内部模型，UI 不可见）。
 *
 * @param slot          槽位编号：0=左盲区 1=右盲区
 * @param info          枚举信息
 * @param connection    USB 连接，必须在会话存活期间持有，关闭时置 null
 * @param nativeHandle  native 会话句柄（0=未打开）
 */
class UvcCamera(
    val slot: Int,
    val info: UsbDeviceInfo,
    var connection: UsbDeviceConnection?,
    @Volatile var nativeHandle: Long
) {
    @Volatile
    var streaming: Boolean = false

    val fd: Int
        get() = connection?.fileDescriptor ?: -1

    val isOpen: Boolean
        get() = nativeHandle != 0L
}
