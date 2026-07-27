package cn.org.sdcd.suiyin.keepalive

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.PowerManager.WakeLock
import android.util.Log
import cn.org.sdcd.suiyin.common.Prefs

class KeepAliveManager(private val context: Context) {

    companion object {
        private const val TAG = "KeepAliveManager"
        private const val WAKE_LOCK_TAG = "BTRemote:WakeLock"
    }

    private var wakeLock: WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    fun start() {
        if (!Prefs.getBoolean(Prefs.KEY_KEEP_ALIVE, true)) return

        acquireWakeLock()
        acquireWifiLock()
        Log.d(TAG, "KeepAliveManager started")
    }

    fun stop() {
        releaseWakeLock()
        releaseWifiLock()
        Log.d(TAG, "KeepAliveManager stopped")
    }

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Acquire WakeLock failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Release WakeLock failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        try {
            val wm = context.applicationContext.getSystemService(
                Context.WIFI_SERVICE
            ) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "BTRemote:WifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Acquire WifiLock failed", e)
            try {
                val wm = context.applicationContext.getSystemService(
                    Context.WIFI_SERVICE
                ) as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL,
                    "BTRemote:WifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Acquire WifiLock fallback failed", e2)
            }
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
            Log.d(TAG, "WifiLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Release WifiLock failed", e)
        }
    }

    fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }
}
