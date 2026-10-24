package com.wheeltech.blindcam.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一日志入口：
 * - 同时输出到 logcat（带 UVC- 前缀）与应用私有日志文件（files/logs/blindcam.log）
 * - 内存环形缓冲兜底，文件最多保留 2 个、单个约 2MB
 * - 支持「导出日志」到用户选择的目录（见 [snapshot]），便于排查问题
 *
 * 注意：所有方法线程安全；文件写入为单次 append，调用点不在每帧热路径，
 * 成功取流帧不写日志，避免影响性能。
 */
object Logger {
    private const val PREFIX = "UVC-"
    private const val MAX_RING = 4000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val LOG_NAME = "blindcam.log"
    private const val LOG_NAME_PREV = "blindcam.log.1"

    private val ring = ArrayDeque<String>()
    private val lock = Any()
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private var fos: FileOutputStream? = null
    private var logFile: File? = null
    private var written: Long = 0L

    /** Application 启动时调用，开启文件落盘 */
    fun init(context: Context) {
        synchronized(lock) {
            runCatching {
                val dir = File(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    "logs"
                ).apply { mkdirs() }
                val f = File(dir, LOG_NAME)
                logFile = f
                fos = FileOutputStream(f, true)
                written = f.length()
            }
        }
    }

    fun d(tag: String, msg: String) {
        Log.d(PREFIX + tag, msg)
        write("D", tag, msg, null)
    }

    fun i(tag: String, msg: String) {
        Log.i(PREFIX + tag, msg)
        write("I", tag, msg, null)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        Log.w(PREFIX + tag, msg, t)
        write("W", tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(PREFIX + tag, msg, t)
        write("E", tag, msg, t)
        flush()
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = buildString {
            append(ts.format(Date()))
            append(' ').append(level).append('/').append(PREFIX).append(tag)
            append(' ').append(msg)
            if (t != null) {
                append('\n').append(Log.getStackTraceString(t))
            }
        }
        synchronized(lock) {
            ring.addLast(line)
            while (ring.size > MAX_RING) ring.removeFirst()
            val out = fos ?: return
            runCatching {
                out.write((line + "\n").toByteArray())
                written += line.length + 1
                if (written >= MAX_FILE_BYTES) rotateLocked()
            }
        }
    }

    private fun rotateLocked() {
        runCatching {
            fos?.flush()
            fos?.close()
            val f = logFile ?: return
            val prev = File(f.parentFile, LOG_NAME_PREV)
            if (prev.exists()) prev.delete()
            f.renameTo(prev)
            fos = FileOutputStream(f, false)
            written = 0L
        }
    }

    private fun flush() {
        synchronized(lock) {
            runCatching { fos?.flush() }
        }
    }

    /** 当前日志文件所在目录（未 init 时为 null） */
    fun logDir(): File? = logFile?.parentFile

    /**
     * 汇总日志文本：旧轮转文件 + 当前日志文件；若文件不可用则退回内存缓冲。
     */
    fun snapshot(): String = synchronized(lock) {
        runCatching { fos?.flush() }
        val sb = StringBuilder()
        val f = logFile
        if (f != null) {
            val prev = File(f.parentFile, LOG_NAME_PREV)
            if (prev.exists()) runCatching { sb.append(prev.readText()).append('\n') }
            if (f.exists()) runCatching { sb.append(f.readText()) }
        }
        if (sb.isEmpty()) {
            ring.forEach { sb.append(it).append('\n') }
        }
        sb.toString()
    }
}
