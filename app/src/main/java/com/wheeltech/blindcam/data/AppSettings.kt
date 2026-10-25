package com.wheeltech.blindcam.data

/**
 * 应用级全局设置（DataStore 持久化）。
 * 单摄像头相关设置（名称/镜像/旋转/档位/顺序）见 [DeviceConfig]。
 */
data class AppSettings(
    /** 布局模式：AUTO=按摄像头数量自动 1/2/3/4 宫格；SINGLE=只看 [focusSlot] 这一路 */
    val layoutMode: LayoutMode = LayoutMode.AUTO,
    /** SINGLE 模式下要单看的槽位序号 */
    val focusSlot: Int = 0,
    /** 主题模式（默认跟随系统） */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 拍照保存目录（SAF 目录树 Uri 字符串；空表示用应用私有默认目录） */
    val photoDirUri: String = "",
    /** 录像保存目录（SAF 目录树 Uri 字符串；空表示用应用私有默认目录） */
    val videoDirUri: String = "",

    // ===== 总录像 / 单路录像画面叠加信息（OSD）=====
    /** 叠加左上角：自定义名称 */
    val osdName: Boolean = true,
    /** 叠加右上角：系统时间 yyyy-MM-dd HH:mm:ss */
    val osdClock: Boolean = true,
    /** 叠加左下角：车速（当前无车速数据源，默认关闭；预留） */
    val osdSpeed: Boolean = false,

    // ===== 预警 =====
    /** 预警提示音音量（0–100） */
    val warnVolume: Int = 70,

    // ===== 录像码率（0自动/1省流/2标准/3高画质/4原画，码率按分辨率帧率路数动态计算） =====
    /** 单路（分录像）码率档位 */
    val singleBitrateQuality: Int = 0,
    /** 总录像（多路合成）码率档位 */
    val compositeBitrateQuality: Int = 0,

    // ===== 循环录像 =====
    /** 总录像是否启用循环录像（分段切片 + 容量满覆盖最旧） */
    val loopEnabled: Boolean = false,
    /** 单段时长（分钟），到时自动切新段 */
    val loopSegmentMinutes: Int = 3,
    /** 保留剩余空间（MB），低于该值开始删最旧的总录像段 */
    val loopKeepFreeMb: Int = 1024,

    // ===== GPS 轨迹（总录像） =====
    /** 总录像时记录 GPS 轨迹（需定位权限，车机无 GPS 时自动跳过） */
    val gpsEnabled: Boolean = false
) {
    enum class LayoutMode { AUTO, SINGLE }

    /**
     * 主题：跟随系统 / 深色 / 浅色 / 深色+蓝色科技风。
     * ordinal 已持久化，新增项只能加在末尾，避免改动既有用户的选择。
     */
    enum class ThemeMode { SYSTEM, DARK, LIGHT, TECH_BLUE }
}
