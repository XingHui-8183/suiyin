package cn.org.sdcd.suiyin.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import cn.org.sdcd.suiyin.common.Prefs
import cn.org.sdcd.suiyin.service.CoreService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Boot received: $action")

        if (Prefs.getBoolean(Prefs.KEY_AUTO_START, true) && Prefs.hasRole()) {
            startCoreService(context)
        }
    }

    private fun startCoreService(context: Context) {
        try {
            val serviceIntent = Intent(context, CoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "CoreService started on boot")
        } catch (e: Exception) {
            Log.e(TAG, "Start service on boot failed", e)
        }
    }
}
