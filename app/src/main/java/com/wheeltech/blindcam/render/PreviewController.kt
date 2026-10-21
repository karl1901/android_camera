package com.wheeltech.blindcam.render

import com.wheeltech.blindcam.domain.CameraFrame
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.util.Logger

/**
 * 渲染总控（render 模块）：
 * - 实现 [UvcCameraManager.FrameListener]，在 native 帧回调线程上被调用
 * - 按 cameraId(slot) 把帧路由到对应槽位的 [PreviewRenderer]
 * - 负责 MJPEG 解码、Bitmap 回收、帧数组归还
 *
 * 线程说明：
 * - onFrame 在 libuvc streaming 线程执行；MjpegDecoder.decode 是本链路唯一
 *   允许的耗时操作（单帧 JPEG 解码），渲染器内部自带丢帧保护
 * - 不在回调内做任何 View 层级操作 / 锁等待
 *
 * @param renderers 槽位(0=左 1=右)到渲染器的映射
 */
class PreviewController(
    private val renderers: Map<Int, PreviewRenderer>
) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "preview-ctrl"
    }

    /** 注册到管理器，开始接收帧 */
    fun start(manager: UvcCameraManager) = manager.addFrameListener(this)

    /** 从管理器注销，停止接收帧 */
    fun stop(manager: UvcCameraManager) = manager.removeFrameListener(this)

    private var rcvCount = 0
    private var decOk = 0
    private var decFail = 0

    private fun renderDiag(msg: String) {
        try {
            val p = java.io.File("/sdcard/Android/data/com.wheeltech.blindcam/files/render_diag.txt")
            p.appendText(msg + "\n")
        } catch (_: Throwable) {}
    }

    override fun onFrame(frame: CameraFrame) {
        // 最前面无条件心跳，确认是否被调用
        rcvCount++
        if (rcvCount == 1) renderDiag("onFrame FIRST cameraId=${frame.cameraId} dataLen=${frame.data.size}")
        // 帧必须最终归还数组（try/finally 保证，异常路径也回收）
        try {
            val renderer = renderers[frame.cameraId]
            if (renderer == null) {
                // 没有对应槽位（如只显示单路），直接丢弃
                if (rcvCount <= 3) renderDiag("no renderer for cameraId=${frame.cameraId}")
                return
            }
            // 仅处理 MJPEG 帧；后续扩展 H.264 时在此分发
            val bitmap = MjpegDecoder.decode(frame.data)
            if (bitmap != null) {
                decOk++
                // submitBitmap 内部 lockCanvas→draw→unlock 在当前线程同步完成，
                // 返回后 Bitmap 已不再被使用，立即回收，避免逐帧累积导致 OOM
                renderer.submitBitmap(bitmap)
                bitmap.recycle()
            } else {
                decFail++
                if (decFail <= 3)
                    renderDiag("decode FAIL rcv=$rcvCount dataLen=${frame.data.size} head=${frame.data.take(4).joinToString(" ") { "%02X".format(it) }}")
            }
            if (rcvCount % 30 == 0)
                renderDiag("stats rcv=$rcvCount decOk=$decOk decFail=$decFail")
        } catch (t: Throwable) {
            // 渲染/解码异常不允许打断 native 取流线程
            Logger.w(TAG, "onFrame render failed: ${t.message}")
            renderDiag("EXC: ${t.message}")
        } finally {
            frame.recycle()
        }
    }
}
