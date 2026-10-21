package com.wheeltech.blindcam.usb

import android.hardware.usb.UsbDevice

/**
 * USB 设备展示模型（屏蔽系统 [UsbDevice]，方便 ViewModel/UI 使用）
 */
data class UsbDeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val productName: String?,
    val manufacturerName: String?,
    val hasPermission: Boolean,
    /** 原始系统对象，仅 Domain 层使用，UI 不直接持有 */
    val rawDevice: UsbDevice
) {
    /** 界面展示用摘要 */
    val displayText: String
        get() {
            val name = productName ?: "未知设备"
            return "%s [%04X:%04X]%s".format(
                name, vendorId, productId,
                if (hasPermission) "" else "（未授权）"
            )
        }
}
