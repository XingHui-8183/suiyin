package com.tool.btremote.sms

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager as TelephonySmsManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 实际发送短信的工具类。备机端使用。
 *
 * 注意:Android 31+ (S) 起应使用 `getSmsManagerForSubscriptionId` 或系统服务获取 SmsManager,
 * 这里采用兼容方式:31+ 通过 Context 拿 SmsManager,旧版本用静态 getDefault()。
 */
object SmsSender {

    private const val TAG = "SmsSender"

    data class Result(val success: Boolean, val error: String = "")

    @SuppressLint("UnspecifiedRegisterReceiverFlag", "MissingPermission")
    suspend fun sendSms(
        context: Context,
        destination: String,
        body: String
    ): Result = withContext(Dispatchers.IO) {
        try {
            val telephony = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(TelephonySmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                TelephonySmsManager.getDefault()
            } ?: return@withContext Result(false, "无法获取 SmsManager")

            val parts = telephony.divideMessage(body)
            val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                parts.forEach { _ ->
                    add(
                        PendingIntent.getBroadcast(
                            context,
                            System.currentTimeMillis().toInt() and 0xFFFF,
                            Intent("com.tool.btremote.SMS_SENT"),
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            else PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            }
            val deliveredIntents = ArrayList<PendingIntent>(parts.size).apply {
                parts.forEach { _ ->
                    add(
                        PendingIntent.getBroadcast(
                            context,
                            (System.currentTimeMillis() + 1).toInt() and 0xFFFF,
                            Intent("com.tool.btremote.SMS_DELIVERED"),
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                            else PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
            }

            telephony.sendMultipartTextMessage(
                destination,
                null,
                parts,
                sentIntents,
                deliveredIntents
            )
            Log.d(TAG, "SMS sent: $destination / ${parts.size} parts")
            Result(true)
        } catch (e: SecurityException) {
            Log.e(TAG, "No SEND_SMS permission", e)
            Result(false, "缺少 SEND_SMS 权限")
        } catch (e: Exception) {
            Log.e(TAG, "Send SMS failed", e)
            Result(false, e.message ?: "发送失败")
        }
    }

    /** CoreService 中无 Context 调用时使用 App 全局上下文 */
    suspend fun sendSms(destination: String, body: String): Result {
        return sendSms(com.tool.btremote.App.get(), destination, body)
    }
}
