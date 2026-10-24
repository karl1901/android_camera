package com.wheeltech.blindcam.usb

import android.hardware.usb.UsbDevice
import android.os.Build

/**
 * USB 设备展示模型（屏蔽系统 [UsbDevice]，方便 ViewModel/UI 使用）
 *
 * @param stableKey 跨插拔/重启的稳定标识，用于绑定用户自定义设置
 *   （名称、镜像、旋转、档位、顺序）。优先 vid:pid:序列号；
 *   序列号读不到（未授权或设备无序列号）时回退 vid:pid:总线节点。
 */
data class UsbDeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val productName: String?,
    val manufacturerName: String?,
    val hasPermission: Boolean,
    val stableKey: String,
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

    /** 设备默认名称（无自定义名时使用） */
    val defaultName: String get() = productName ?: "摄像头"

    companion object {
        /** 读取设备序列号（API29+ 公开 getSerialName，需已授权；读不到返回 null） */
        fun readSerial(device: UsbDevice): String? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val s: String? = device.serialNumber
                    s?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } catch (t: Throwable) {
                null
            }
        }

        /**
         * 计算稳定标识。
         * @param serial 已读取/缓存的序列号（授权后）；为空时回退总线节点
         * @param node 总线节点（如 /dev/bus/usb/001/003 的 003）
         */
        fun buildStableKey(vendorId: Int, productId: Int, serial: String?, node: String): String {
            return if (!serial.isNullOrBlank()) {
                "%04X:%04X:s:%s".format(vendorId, productId, serial)
            } else {
                "%04X:%04X:n:%s".format(vendorId, productId, node)
            }
        }
    }
}
