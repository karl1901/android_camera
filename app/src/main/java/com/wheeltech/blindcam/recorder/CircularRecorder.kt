package com.wheeltech.blindcam.recorder

import android.content.Context
import android.os.StatFs
import com.wheeltech.blindcam.util.Logger
import java.io.File

/**
 * CircularRecorder 循环录像模块（第 6 阶段实现，本阶段仅落地目录/容量管理骨架）。
 *
 * 规划：
 * 1. 按 [shardDurationSec] 固定时长分片，MJPEG 帧优先直接封装为 AVI（MJPEG 原生 AVI）
 * 2. 达到 [maxStorageBytes] 自动删除最早分片
 * 3. 预留 MediaCodec H.264 硬编码入口：[setH264EncoderEnabled]
 * 4. 录像写入在独立单线程 HandlerThread，帧来自 Domain 帧分发
 */
class CircularRecorder(private val context: Context) {

    companion object {
        private const val TAG = "recorder"
        private const val DIR_NAME = "uvc_recordings"
    }

    /** 录像配置（后续由设置页 DataStore 注入） */
    data class Config(
        val shardDurationSec: Int = 60,        // 单分片时长（秒）
        val maxStorageBytes: Long = 2L * 1024 * 1024 * 1024 // 最大占用 2GB
    )

    @Volatile
    var config: Config = Config()
        private set

    @Volatile
    private var recording = false

    /** H.264 硬编码开关（预留，当前仅 MJPEG 直封装） */
    @Volatile
    private var h264Enabled = false

    /**
     * 录像目录：优先 App 私有外部目录（无需任何存储权限，卸载自动删除），
     * 不可用时退回内部 filesDir。
     */
    fun resolveRecordDir(): File {
        val external = context.getExternalFilesDir(null)
        val base = external ?: context.filesDir
        return File(base, DIR_NAME).apply { mkdirs() }
    }

    fun updateConfig(newConfig: Config) {
        config = newConfig
        Logger.i(TAG, "config updated: ${newConfig.shardDurationSec}s, ${newConfig.maxStorageBytes}B")
    }

    fun setH264EncoderEnabled(enabled: Boolean) {
        // TODO(阶段6): 接入 MediaCodec H.264 硬编码（minSdk21 可用）
        h264Enabled = enabled
        Logger.i(TAG, "H.264 encoder requested = $enabled（当前阶段未实现）")
    }

    fun start() {
        if (recording) return
        resolveRecordDir()
        recording = true
        // TODO(阶段6): 启动 HandlerThread、创建分片文件、注册帧监听、封装 AVI
        Logger.i(TAG, "recorder start（骨架，阶段6实现 MJPEG 分片封装）")
    }

    fun stop() {
        if (!recording) return
        recording = false
        // TODO(阶段6): flush 尾部分片、停止线程
        Logger.i(TAG, "recorder stop（骨架）")
    }

    /** 删除最早分片直到总占用低于上限（阶段6实现具体扫描逻辑） */
    fun evictIfNeeded() {
        // TODO(阶段6): 按 lastModified 升序删除 .avi 分片
    }

    /** 当前可用字节数（供 UI 展示） */
    fun availableBytes(): Long {
        val dir = resolveRecordDir()
        val stat = StatFs(dir.absolutePath)
        return stat.availableBytes
    }
}
