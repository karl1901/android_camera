package com.wheeltech.blindcam.domain

/**
 * 一帧画面（MJPEG 原始字节）。
 *
 * 数据流规则：
 * JNI 回调 onFrame(byte[],w,h,format) → 包装成本对象 → 渲染模块 / 录像模块
 * 使用完毕必须调用 [recycle] 归还数组，避免频繁 GC 导致 OOM。
 */
class CameraFrame(
    val cameraId: Int,
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val frameFormat: Int,
    private val pool: FramePool?
) {
    @Volatile
    var recycled: Boolean = false
        private set

    fun recycle() {
        if (recycled) return
        recycled = true
        pool?.recycle(data)
    }
}
