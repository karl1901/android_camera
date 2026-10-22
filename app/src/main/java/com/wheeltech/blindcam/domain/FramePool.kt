package com.wheeltech.blindcam.domain

/**
 * byte[] 复用池：供渲染线程在接收帧时拷贝数据，避免每帧 new 大数组造成
 * 频繁 GC 卡顿。
 *
 * 采用 best-fit：acquire 时返回池中容量 ≥ 请求大小的最小数组，
 * 归还后按容量复用；池内数组总数受 [maxTotal] 严格限制，超出即丢弃，
 * 不会无限增长（防止不同帧大小导致的内存泄漏）。
 *
 * 注意：调用方必须保证归还的数组不再被使用。
 */
class FramePool(
    private val maxTotal: Int = 12
) {
    private val pool = java.util.ArrayDeque<ByteArray>()

    @Synchronized
    fun acquire(size: Int): ByteArray {
        // best-fit：池中 >= size 的最小数组
        val it = pool.iterator()
        var best: ByteArray? = null
        while (it.hasNext()) {
            val b = it.next()
            if (b.size >= size && (best == null || b.size < best.size)) {
                best = b
            }
        }
        if (best != null) {
            pool.remove(best)
            return best
        }
        return ByteArray(size)
    }

    @Synchronized
    fun recycle(buffer: ByteArray) {
        if (pool.size >= maxTotal) return
        // 同容量去重，避免冗余
        pool.addFirst(buffer)
    }

    @Synchronized
    fun clear() {
        pool.clear()
    }
}
