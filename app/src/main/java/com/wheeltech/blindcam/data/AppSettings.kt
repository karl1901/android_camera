package com.wheeltech.blindcam.data

/**
 * 设置项数据模型（DataStore 持久化）
 */
data class AppSettings(
    /** APP 启动后默认开启摄像头（自动打开已授权设备） */
    val enableCameraOnStart: Boolean = true,
    /** 循环录像分片时长（秒） */
    val clipDurationSec: Int = 60,
    /** 最大存储占用（MB） */
    val maxStorageMb: Int = 2048,
    /** 左路画面水平镜像（默认关闭，显示正常正面画面） */
    val mirrorLeft: Boolean = false,
    /** 右路画面水平镜像（默认关闭，显示正常正面画面） */
    val mirrorRight: Boolean = false,
    /** 左路画面垂直翻转 */
    val flipLeft: Boolean = false,
    /** 右路画面垂直翻转 */
    val flipRight: Boolean = false,
    /** 布局模式 */
    val layoutMode: LayoutMode = LayoutMode.DUAL,
    /** 主题模式（默认跟随系统） */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 拍照保存目录（SAF 目录树 Uri 字符串；空表示用应用私有默认目录） */
    val photoDirUri: String = "",
    /** 录像保存目录（SAF 目录树 Uri 字符串；空表示用应用私有默认目录） */
    val videoDirUri: String = ""
) {
    enum class LayoutMode { DUAL, LEFT_ONLY, RIGHT_ONLY }

    enum class ThemeMode { SYSTEM, DARK, LIGHT }
}
