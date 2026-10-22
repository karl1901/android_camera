package com.wheeltech.blindcam.domain

/**
 * 一帧画面（MJPEG 原始字节）。
 *
 * 数据流规则：
 * JNI 回调 onFrame(byte[],w,h,format) → 包装成本对象 → 渲染模块 / 录像模块
 *
 * 注意：[data] 由 native 层在每一路会话内部复用（下一帧会覆盖其内容），
 * 因此任何需要在回调返回后继续使用数据的模块，必须在 onFrame 中立即自行拷贝
 * （渲染、录像均已在接收线程内拷贝）。本对象不再持有/归还对象池。
 */
class CameraFrame(
    val cameraId: Int,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val frameFormat: Int
) {
    /** 回调内同步使用完即结束；保留空方法兼容调用点语义。 */
    fun recycle() = Unit
}
