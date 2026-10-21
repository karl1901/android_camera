package com.wheeltech.blindcam.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.wheeltech.blindcam.R
import com.wheeltech.blindcam.UvcApp
import com.wheeltech.blindcam.util.Logger

/**
 * 前台保活服务：
 * - 切导航 / 后台时维持 USB 取流与循环录像（USB 流与录像线程挂在 Domain 层）
 * - targetSdk=33：foregroundServiceType=connectedDevice，
 *   不使用 camera 类型（避免强制 CAMERA 权限）
 * - Android 13+ 对前台服务的额外限制在 targetSdk33 下不会触发，
 *   所有高版本 API 均加版本守卫
 */
class UvcForegroundService : Service() {

    companion object {
        private const val TAG = "fg-service"
        private const val CHANNEL_ID = "uvc_blind_cam_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.wheeltech.blindcam.action.START"
        const val ACTION_STOP = "com.wheeltech.blindcam.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, UvcForegroundService::class.java)
                .setAction(ACTION_START)
            ContextCompatStart(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, UvcForegroundService::class.java)
                    .setAction(ACTION_STOP)
            )
        }

        private fun ContextCompatStart(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logger.i(TAG, "stop foreground service")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startAsForeground()
        }
        return START_STICKY // 被杀后系统尽量重建
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // 版本守卫：connectedDevice 类型 API29(Q) 才引入
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
        Logger.i(TAG, "foreground service started")

        // TODO(阶段7): 启动后确保 UvcCameraManager 已在取流、CircularRecorder 已录像
        val manager = (application as? UvcApp)?.cameraManager
        manager?.let {
            Logger.i(TAG, "attached to UvcCameraManager, cameras=${it.devices.value.size}")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Logger.i(TAG, "service onDestroy")
        super.onDestroy()
    }
}
