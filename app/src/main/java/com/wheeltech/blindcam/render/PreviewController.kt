package com.wheeltech.blindcam.render

import android.os.Handler
import android.os.HandlerThread
import com.wheeltech.blindcam.domain.CameraFrame
import com.wheeltech.blindcam.domain.FramePool
import com.wheeltech.blindcam.domain.UvcCameraManager
import com.wheeltech.blindcam.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 渲染总控（render 模块）：
 * - 实现 [UvcCameraManager.FrameListener]，在 native 帧回调线程上被调用
 * - 按 cameraId(slot) 把帧路由到对应槽位的工作线程
 *
 * 关键：native 每路的 byte[] 会被下一帧复用/覆盖，所以在 onFrame（native 线程）
 * 内必须立即把数据拷贝到本类自有的缓冲（[FramePool]），再异步解码渲染。
 *
 * 线程模型：
 * - onFrame 运行在 libuvc 回调线程，只做一次 memcpy 投递，立即返回
 * - 每个槽位一个独立 [HandlerThread]，串行执行 MJPEG 解码 + Canvas 绘制
 * - 工作线程忙时新帧覆盖旧帧（旧缓冲归还对象池），天然丢帧，不堆积
 *
 * @param renderers 槽位(0=左 1=右)到渲染器的映射
 */
class PreviewController(
    private val renderers: Map<Int, PreviewRenderer>
) : UvcCameraManager.FrameListener {

    companion object {
        private const val TAG = "preview-ctrl"
    }

    /** 待处理的一帧：拷贝后的字节缓冲 + 有效长度 */
    private class Pending(val buffer: ByteArray, val length: Int)

    /** 单个槽位的异步渲染工作器 */
    private inner class SlotWorker(
        private val slot: Int,
        private val renderer: PreviewRenderer
    ) {
        private val thread = HandlerThread("render-$slot").apply { start() }
        private val handler = Handler(thread.looper)
        private val pending = AtomicReference<Pending?>(null)
        private val scheduled = AtomicBoolean(false)

        /** native 线程调用：立即拷贝帧数据并投递；旧缓冲归还池 */
        fun submit(frame: CameraFrame) {
            // 关键：这里同步拷贝 native 复用数组，回调返回后数据才安全
            val buffer = framePool.acquire(frame.data.size)
            System.arraycopy(frame.data, 0, buffer, 0, frame.data.size)
            val item = Pending(buffer, frame.data.size)
            val old = pending.getAndSet(item)
            old?.let { framePool.recycle(it.buffer) }
            if (scheduled.compareAndSet(false, true)) {
                handler.post { process() }
            }
        }

        private fun process() {
            val item = pending.getAndSet(null)
            try {
                if (item != null) {
                    val bitmap = MjpegDecoder.decode(item.buffer, 0, item.length)
                    if (bitmap != null) {
                        renderer.submitBitmap(bitmap)
                        bitmap.recycle()
                    }
                    framePool.recycle(item.buffer)
                }
            } catch (t: Throwable) {
                Logger.w(TAG, "render slot=$slot failed: ${t.message}")
                item?.let { runCatching { framePool.recycle(it.buffer) } }
            } finally {
                scheduled.set(false)
                // 处理期间又有新帧到达，立即再调度
                if (pending.get() != null && scheduled.compareAndSet(false, true)) {
                    handler.post { process() }
                }
            }
        }

        fun shutdown() {
            thread.quitSafely()
        }
    }

    /** 渲染拷贝缓冲复用池（所有槽位共用，严格上限防泄漏） */
    private val framePool = FramePool()

    private val workers: Map<Int, SlotWorker> = renderers.map { (slot, renderer) ->
        slot to SlotWorker(slot, renderer)
    }.toMap()

    /** 注册到管理器，开始接收帧 */
    fun start(manager: UvcCameraManager) = manager.addFrameListener(this)

    /** 从管理器注销，停止接收帧 */
    fun stop(manager: UvcCameraManager) = manager.removeFrameListener(this)

    override fun onFrame(frame: CameraFrame) {
        val worker = workers[frame.cameraId]
        if (worker == null) {
            // 没有对应槽位，直接丢弃
            return
        }
        // 隐藏画框（单路/少路布局）不再解码，降低 CPU/内存/发热
        if (renderers[frame.cameraId]?.active != true) return
        worker.submit(frame)
    }

    /** 释放工作线程（Activity 销毁时） */
    fun shutdown() {
        workers.values.forEach { it.shutdown() }
        framePool.clear()
    }
}
