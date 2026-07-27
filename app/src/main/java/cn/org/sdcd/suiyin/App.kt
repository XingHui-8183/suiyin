package cn.org.sdcd.suiyin

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import cn.org.sdcd.suiyin.common.Prefs

class App : Application() {

    companion object {
        const val CHANNEL_FOREGROUND = "foreground_service"
        const val CHANNEL_SMS = "sms_notification"
        const val CHANNEL_MEDIA = "media_notification"
        const val CHANNEL_ALERT = "alert_notification"

        private lateinit var instance: App
        fun get(): App = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val foregroundChannel = NotificationChannel(
                CHANNEL_FOREGROUND,
                "后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "蓝牙远程控制后台服务"
                setShowBadge(false)
            }

            val smsChannel = NotificationChannel(
                CHANNEL_SMS,
                "短信通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "同步短信通知"
                enableVibration(true)
                enableLights(true)
            }

            val mediaChannel = NotificationChannel(
                CHANNEL_MEDIA,
                "媒体控制",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "媒体同步控制"
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "提醒通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "连接状态等提醒"
            }

            nm.createNotificationChannels(
                listOf(foregroundChannel, smsChannel, mediaChannel, alertChannel)
            )
        }
    }
}
