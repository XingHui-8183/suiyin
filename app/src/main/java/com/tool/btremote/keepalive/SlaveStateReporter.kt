package com.tool.btremote.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import com.tool.btremote.protocol.BTMessage
import com.tool.btremote.service.CoreService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 备机端状态上报:
 * - 电池电量/充电状态/温度(广播驱动,变化时上报)
 * - 移动网络信号强度(PhoneStateListener 驱动)
 *
 * 仅在备机端角色启用。设计为可被 [CoreService] 持有,
 * 随连接建立 start、断开或服务销毁时 stop。
 */
class SlaveStateReporter(private val context: Context) {

    companion object {
        private const val TAG = "SlaveStateReporter"
        /** 电池电量变化超过 1% 才上报,避免频繁刷新 */
        private const val BATTERY_LEVEL_THRESHOLD = 1
    }

    private val started = AtomicBoolean(false)
    private var lastBatteryLevel = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            handleBatteryChanged(intent)
        }
    }

    private var signalListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            // 注册电池广播(粘性广播,注册后立即收到一次当前状态)
            context.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Register battery receiver failed", e)
        }
        startSignalListener()
        Log.d(TAG, "SlaveStateReporter started")
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        try {
            signalListener?.let { sl ->
                @Suppress("DEPRECATION")
                telephonyManager?.listen(sl, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {}
        signalListener = null
        telephonyManager = null
        Log.d(TAG, "SlaveStateReporter stopped")
    }

    /** 主动上报一次当前状态(REQUEST_STATE 时调用) */
    fun reportOnce() {
        try {
            // 电池是粘性广播,主动取一次
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.let { handleBatteryChanged(it, force = true) }
        } catch (e: Exception) {
            Log.w(TAG, "Report battery once failed", e)
        }
        reportSignalOnce()
    }

    private fun handleBatteryChanged(intent: Intent, force: Boolean = false) {
        try {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val plugged = status == BatteryManager.BATTERY_STATUS_CHARGING
            val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            val pct = if (scale > 0) (level * 100) / scale else level

            if (!force && pct in 0..100 && kotlin.math.abs(pct - lastBatteryLevel) < BATTERY_LEVEL_THRESHOLD) {
                return
            }
            lastBatteryLevel = pct

            CoreService.instance?.sendMessage(
                BTMessage.batteryState(pct, 100, plugged, temp)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Handle battery changed failed", e)
        }
    }

    private fun startSignalListener() {
        try {
            telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            signalListener = object : PhoneStateListener() {
                @Deprecated("deprecated in API 31, but still works on Android 13/14 for level reporting")
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                    super.onSignalStrengthsChanged(signalStrength)
                    reportSignalOnce(signalStrength)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager?.listen(signalListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for signal listener", e)
        } catch (e: Exception) {
            Log.e(TAG, "Start signal listener failed", e)
        }
    }

    private fun reportSignalOnce(signalStrength: SignalStrength? = null) {
        try {
            val ss = signalStrength ?: run {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                @Suppress("DEPRECATION")
                tm?.signalStrength
            } ?: return
            val level = ss.level // 0..4
            val type = describeNetworkType()
            CoreService.instance?.sendMessage(BTMessage.signalState(level, type))
        } catch (e: Exception) {
            Log.w(TAG, "Report signal failed", e)
        }
    }

    private fun describeNetworkType(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return "unknown"
            val networkType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                tm.dataNetworkType
            } else {
                @Suppress("DEPRECATION")
                tm.networkType
            }
            when (networkType) {
                android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                android.telephony.TelephonyManager.NETWORK_TYPE_HSDPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_HSUPA,
                android.telephony.TelephonyManager.NETWORK_TYPE_HSPA -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                android.telephony.TelephonyManager.NETWORK_TYPE_GPRS,
                android.telephony.TelephonyManager.NETWORK_TYPE_EDGE -> "2G"
                else -> "unknown"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }
}
