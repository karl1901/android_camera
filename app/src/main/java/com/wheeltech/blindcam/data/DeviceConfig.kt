package com.wheeltech.blindcam.data

import org.json.JSONObject

/**
 * 单个摄像头的持久化配置，按设备稳定标识（vid:pid:序列号）绑定，
 * 跨插拔 / 重启 / 换槽位后仍然生效。
 *
 * @param key      设备稳定标识（[com.wheeltech.blindcam.usb.UsbDeviceInfo.stableKey]）
 * @param order    排序权重（决定从左到右 / 从上到下的显示顺序，小在前）
 * @param name     用户自定义名称（空串=显示设备默认名称）
 * @param mirror   水平镜像（镜像摄像头开启）
 * @param rotation 旋转角度（0/90/180/270）
 * @param width    选定分辨率宽（0=用设备支持的最大档）
 * @param height   选定分辨率高
 * @param fps      选定帧率（0=设备默认）
 * @param warn     该路是否开启盲区预警（移动目标红框 + 提示音）
 * @param denoise  该路实时降噪档位（0关/1弱/2中/3强），按本画框分辨率/帧率独立处理
 */
data class DeviceConfig(
    val key: String,
    val order: Int = 0,
    val name: String = "",
    val mirror: Boolean = false,
    val rotation: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val warn: Boolean = false,
    val denoise: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("order", order)
        put("name", name)
        put("mirror", mirror)
        put("rotation", rotation)
        put("width", width)
        put("height", height)
        put("fps", fps)
        put("warn", warn)
        put("denoise", denoise)
    }

    companion object {
        fun fromJson(key: String, o: JSONObject): DeviceConfig = DeviceConfig(
            key = key,
            order = o.optInt("order", 0),
            name = o.optString("name", ""),
            mirror = o.optBoolean("mirror", false),
            rotation = o.optInt("rotation", 0),
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
            fps = o.optInt("fps", 0),
            warn = o.optBoolean("warn", false),
            denoise = o.optInt("denoise", 0)
        )
    }
}
