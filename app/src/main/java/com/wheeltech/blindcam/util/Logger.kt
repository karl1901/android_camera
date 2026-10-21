package com.wheeltech.blindcam.util

import android.util.Log

/**
 * 统一日志入口，后期可加文件落盘 / 车机调试开关
 */
object Logger {
    private const val PREFIX = "UVC-"

    fun d(tag: String, msg: String) = Log.d(PREFIX + tag, msg)
    fun i(tag: String, msg: String) = Log.i(PREFIX + tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = Log.w(PREFIX + tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = Log.e(PREFIX + tag, msg, t)
}
