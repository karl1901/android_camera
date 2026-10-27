package com.wheeltech.blindcam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.ui.main.MainActivity
import com.wheeltech.blindcam.util.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 开机自启（车机断电重启场景）：
 * - 启动前台保活服务，让常驻 CameraSupervisor 重新枚举 USB 并自动申请权限；
 * - 尝试拉起主界面，使已授权摄像头尽快出画面（需求 III.2.7）。
 *
 * 说明：Android 没有任何 API 能让 USB 权限跨重启自动保留；重启后系统仍要求重新授权。
 * 本接收器的作用是“自动打开 APP 并串行弹出授权”，用户点允许后所有已授权摄像头恢复显示，
 * 而不是要求用户手动找图标启动。部分魔改车机会拦截后台启动 Activity，此时至少服务已启动，
 * 用户从桌面打开即可正常恢复。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "boot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        Logger.i(TAG, "boot completed action=$action sdk=${Build.VERSION.SDK_INT}")
        // 用户可在设置里关闭“开机自启动”。DataStore 读取放到后台线程，避免阻塞广播。
        val pending = goAsync()
        Thread {
            val enabled = runCatching {
                val app = context.applicationContext as UvcApp
                runBlocking { app.settingsRepository.settings.first().bootAutoStart }
            }.getOrDefault(true)
            try {
                if (!enabled) {
                    Logger.i(TAG, "boot auto start disabled by user")
                    return@Thread
                }
                runCatching { UvcForegroundService.start(context) }
                    .onFailure { Logger.w(TAG, "start foreground service failed: ${it.message}") }

                runCatching {
                    val launch = Intent(context, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    context.startActivity(launch)
                    Logger.i(TAG, "MainActivity launch requested")
                }.onFailure {
                    // Android 10+ 后台启动 Activity 可能被系统拦截，不视为致命错误
                    Logger.w(TAG, "launch MainActivity blocked: ${it.message}")
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
