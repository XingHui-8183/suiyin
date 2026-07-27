package com.tool.btremote.sms

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tool.btremote.App
import com.tool.btremote.R
import com.tool.btremote.ui.SmsPopupActivity

object SmsNotifier {

    private const val TAG = "SmsNotifier"
    private const val SMS_NOTIFICATION_ID_BASE = 10000

    fun notifySms(context: Context, sender: String, body: String, time: Long) {
        Log.d(TAG, "Notifying SMS from: $sender")

        playNotificationSound(context)
        vibrate(context)
        showNotification(context, sender, body, time)
        showPopup(context, sender, body, time)
    }

    private fun playNotificationSound(context: Context) {
        try {
            val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, notificationUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Play sound failed", e)
        }
    }

    private fun vibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 300, 200, 300), -1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vibrate failed", e)
        }
    }

    private fun showNotification(context: Context, sender: String, body: String, time: Long) {
        try {
            val notificationId = (time % Int.MAX_VALUE).toInt() + SMS_NOTIFICATION_ID_BASE

            val popupIntent = Intent(context, SmsPopupActivity::class.java).apply {
                putExtra("sender", sender)
                putExtra("body", body)
                putExtra("time", time)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.getActivity(
                    context, notificationId, popupIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getActivity(
                    context, notificationId, popupIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val notification = NotificationCompat.Builder(context, App.CHANNEL_SMS)
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle(sender)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setWhen(time)
                .setShowWhen(true)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Show notification failed", e)
        }
    }

    private fun showPopup(context: Context, sender: String, body: String, time: Long) {
        try {
            val intent = Intent(context, SmsPopupActivity::class.java).apply {
                putExtra("sender", sender)
                putExtra("body", body)
                putExtra("time", time)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Show popup failed", e)
        }
    }
}
