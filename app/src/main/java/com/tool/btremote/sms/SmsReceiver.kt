package com.tool.btremote.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.tool.btremote.common.Prefs
import com.tool.btremote.protocol.BTMessage
import com.tool.btremote.service.CoreService

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.getBoolean(Prefs.KEY_SMS_SYNC, true)) return
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val sender = messages[0].originatingAddress ?: return
            val body = buildString {
                for (msg in messages) {
                    append(msg.messageBody)
                }
            }
            val time = messages[0].timestampMillis

            Log.d(TAG, "SMS received from: $sender, body length: ${body.length}")

            val service = CoreService.instance
            if (service != null) {
                service.sendMessage(BTMessage.smsReceived(sender, body, time))
            } else {
                Log.w(TAG, "CoreService not running, starting...")
                val startIntent = Intent(context, CoreService::class.java)
                context.startService(startIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SmsReceiver error", e)
        }
    }
}
