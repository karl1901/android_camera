package com.wheeltech.blindcam.vision

/**
 * 视觉识别相关类型。
 *
 * 说明：本版本只提供"分级推理工程框架"，未内置训练好的模型；
 * 因此真实的人/车/红绿灯分类结果暂不产生，预警回退到运动检测（WarningManager）。
 * 一旦补齐 INT8 模型（如红绿灯/人车检测），即可在 [SmartVisionEngine] 中直接产出结果。
 */

/** 设备能力分级（决定跑什么精度/频率的模型） */
enum class DeviceTier {
    /** 弱车机：仅运动检测，不跑模型 */
    LOW,
    /** 中低端：低分辨率 + 稀疏帧 + INT8 */
    MEDIUM,
    /** 中高端：标准分辨率 + INT8 */
    HIGH,
    /** 高端手机：高分辨率 + 较密帧 + INT8 */
    PREMIUM
}

/** 红绿灯状态（真分类需模型） */
enum class TrafficLightState { RED, YELLOW, GREEN, UNKNOWN }

/** 单个目标检测结果（归一化框 0..1；无模型时为空） */
data class Detection(
    val label: String,
    val confidence: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

/** 某一路的视觉结果 */
data class VisionResult(
    val slot: Int,
    /** 是否检测到运动（无模型时由运动检测提供，用于盲区红框） */
    val motion: Boolean,
    /** 目标检测（无模型时为空） */
    val detections: List<Detection> = emptyList(),
    /** 红绿灯（无模型时为 UNKNOWN） */
    val trafficLight: TrafficLightState = TrafficLightState.UNKNOWN
)
