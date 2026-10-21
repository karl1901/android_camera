package com.wheeltech.blindcam.domain

import java.util.ArrayDeque

/**
 * byte[] 对象池：帧数据复用，防 OOM / 减少 GC 卡顿。
 *
 * 说明：JNI 层 NewByteArray 每帧产生一个新数组（native 内存无法直接池化），
 * 本池主要服务于渲染/录像二次处理（如解码中转、封装拷贝）的缓冲复用。
 * 只缓存与请求尺寸相同的数组，避免大数组长期占用小请求场景。
 */
class FramePool(
    private val maxPooledPerSize: Int = 6
) {
    private val pools = HashMap<Int, ArrayDeque<ByteArray>>()

    @Synchronized
    fun acquire(size: Int): ByteArray {
        val deque = pools[size]
        if (deque != null && deque.isNotEmpty()) {
            return deque.removeFirst()
        }
        return ByteArray(size)
    }

    @Synchronized
    fun recycle(buffer: ByteArray) {
        val deque = pools.getOrPut(buffer.size) { ArrayDeque() }
        if (deque.size < maxPooledPerSize) {
            deque.addLast(buffer)
        }
    }

    @Synchronized
    fun clear() {
        pools.clear()
    }
}
