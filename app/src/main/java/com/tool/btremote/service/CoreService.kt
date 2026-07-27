package com.tool.btremote.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tool.btremote.App
import com.tool.btremote.R
import com.tool.btremote.bluetooth.BTConnectionManager
import com.tool.btremote.common.Prefs
import com.tool.btremote.keepalive.KeepAliveManager
import com.tool.btremote.keepalive.SlaveStateReporter
import com.tool.btremote.media.MediaListenerService
import com.tool.btremote.media.MediaSessionManager
import com.tool.btremote.network.NetworkController
import com.tool.btremote.protocol.BTMessage
import com.tool.btremote.protocol.MsgType
import com.tool.btremote.sms.SmsManager
import com.tool.btremote.sms.SmsNotifier
import com.tool.btremote.sms.SmsSender
import com.tool.btremote.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CoreService : Service() {

    companion object {
        private const val TAG = "CoreService"
        private const val FOREGROUND_ID = 1

        @Volatile
        var instance: CoreService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, CoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var btManager: BTConnectionManager? = null
    private var keepAliveManager: KeepAliveManager? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var slaveStateReporter: SlaveStateReporter? = null

    private var connectionState = BTConnectionManager.State.DISCONNECTED
    private var connectedDeviceName = ""

    private val stateListeners = mutableListOf<(BTConnectionManager.State, String) -> Unit>()
    private val messageListeners = mutableListOf<(BTMessage) -> Unit>()

    private val mediaControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaSessionManager.ACTION_PLAY_PAUSE -> sendMessage(BTMessage.mediaCmdPlayPause())
                MediaSessionManager.ACTION_NEXT -> sendMessage(BTMessage.mediaCmdNext())
                MediaSessionManager.ACTION_PREV -> sendMessage(BTMessage.mediaCmdPrev())
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "CoreService created")

        try {
            btManager = BTConnectionManager(this)
            btManager?.setListener(object : BTConnectionManager.ConnectionListener {
                override fun onStateChanged(state: BTConnectionManager.State) {
                    connectionState = state
                    stateListeners.forEach { it(state, connectedDeviceName) }
                    updateNotificationSafe()
                }

                override fun onMessageReceived(message: BTMessage) {
                    handleMessage(message)
                    messageListeners.forEach { it(message) }
                }

                override fun onDeviceConnected(deviceName: String) {
                    connectedDeviceName = deviceName
                    stateListeners.forEach { it(connectionState, deviceName) }
                    onConnected()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "BTConnectionManager init failed", e)
        }

        try {
            keepAliveManager = KeepAliveManager(this)
            keepAliveManager?.start()
        } catch (e: Exception) {
            Log.e(TAG, "KeepAliveManager init failed", e)
        }

        try {
            registerMediaReceiver()
        } catch (e: Exception) {
            Log.e(TAG, "Register media receiver failed", e)
        }

        try {
            startForeground(FOREGROUND_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Start foreground failed", e)
        }

        try {
            autoStartConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Auto start connection failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "CoreService destroyed")

        try {
            unregisterReceiver(mediaControlReceiver)
        } catch (_: Exception) {}

        mediaSessionManager?.stop()
        mediaSessionManager = null
        slaveStateReporter?.stop()
        slaveStateReporter = null
        keepAliveManager?.stop()
        keepAliveManager = null
        btManager?.release()
        scope.cancel()
    }

    private fun registerMediaReceiver() {
        val filter = IntentFilter().apply {
            addAction(MediaSessionManager.ACTION_PLAY_PAUSE)
            addAction(MediaSessionManager.ACTION_NEXT)
            addAction(MediaSessionManager.ACTION_PREV)
        }
        registerReceiver(mediaControlReceiver, filter)
    }

    private fun autoStartConnection() {
        val role = Prefs.getRole()
        if (role.isEmpty()) return

        if (role == Prefs.ROLE_SLAVE) {
            btManager?.startServer()
            mediaSessionManager = MediaSessionManager(this).apply { start() }
            slaveStateReporter = SlaveStateReporter(this).also { it.start() }
        } else {
            val targetDevice = Prefs.getString(Prefs.KEY_TARGET_DEVICE, "")
            if (targetDevice.isNotEmpty()) {
                btManager?.connectToDevice(targetDevice)
            }
        }
    }

    private fun onConnected() {
        val role = Prefs.getRole()

        // 双方互发设备信息
        val adapter = try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        } catch (_: Exception) {
            null
        }
        val myName = try {
            adapter?.name ?: "Unknown"
        } catch (_: SecurityException) {
            "Unknown"
        }
        sendMessage(BTMessage.deviceInfo(myName, role))

        // 主动请求对端上报状态
        sendMessage(BTMessage.requestState("all"))

        // 备机端如果是 slave,主动上报本地网络状态一次,让主机 UI 立即同步
        if (role == Prefs.ROLE_SLAVE) {
            scope.launch {
                reportNetworkStates()
            }
            // 也立即上报电池/信号状态一次
            slaveStateReporter?.reportOnce()
        }
    }

    /** 备机端:把当前 wifi / 移动数据 / 热点状态一次性上报给主机 */
    private suspend fun reportNetworkStates() {
        try {
            val wifi = NetworkController.isWifiEnabled(this)
            sendMessage(BTMessage.wifiState(wifi))
        } catch (e: Exception) {
            Log.w(TAG, "Report wifi state failed", e)
        }
        try {
            val data = NetworkController.isMobileDataEnabled(this)
            sendMessage(BTMessage.mobileDataState(data))
        } catch (e: Exception) {
            Log.w(TAG, "Report mobile data state failed", e)
        }
        try {
            val hotspot = NetworkController.isHotspotEnabled(this)
            sendMessage(BTMessage.hotspotState(hotspot))
        } catch (e: Exception) {
            Log.w(TAG, "Report hotspot state failed", e)
        }
    }

    fun getConnectionState(): BTConnectionManager.State = connectionState
    fun getConnectedDeviceName(): String = connectedDeviceName

    /** 备机端 UI 用来订阅媒体元数据/播放状态变化 */
    fun getMediaSessionManager(): MediaSessionManager? = mediaSessionManager

    fun addStateListener(listener: (BTConnectionManager.State, String) -> Unit) {
        stateListeners.add(listener)
        listener(connectionState, connectedDeviceName)
    }

    fun removeStateListener(listener: (BTConnectionManager.State, String) -> Unit) {
        stateListeners.remove(listener)
    }

    fun addMessageListener(listener: (BTMessage) -> Unit) {
        messageListeners.add(listener)
    }

    fun removeMessageListener(listener: (BTMessage) -> Unit) {
        messageListeners.remove(listener)
    }

    fun connectToDevice(address: String) {
        btManager?.connectToDevice(address)
    }

    fun startServer() {
        btManager?.startServer()
    }

    fun disconnect() {
        btManager?.disconnect()
    }

    fun getPairedDevices() = btManager?.getPairedDevices()

    fun sendMessage(message: BTMessage): Boolean {
        return btManager?.sendMessage(message) ?: false
    }

    private fun handleMessage(msg: BTMessage) {
        when (msg.type) {
            MsgType.DEVICE_INFO -> handleDeviceInfo(msg)

            MsgType.WIFI_CONTROL -> handleWifiControl(msg)
            MsgType.WIFI_STATE -> handleWifiState(msg)

            MsgType.MOBILE_DATA_CONTROL -> handleMobileDataControl(msg)
            MsgType.MOBILE_DATA_STATE -> handleMobileDataState(msg)

            MsgType.HOTSPOT_CONTROL -> handleHotspotControl(msg)
            MsgType.HOTSPOT_STATE -> handleHotspotState(msg)

            MsgType.REQUEST_STATE -> handleRequestState(msg)

            MsgType.SMS_RECEIVED -> handleSmsReceived(msg)
            MsgType.SMS_SEND -> handleSmsSend(msg)
            MsgType.SMS_SEND_RESULT -> handleSmsSendResult(msg)

            MsgType.MEDIA_METADATA -> handleMediaMetadata(msg)
            MsgType.MEDIA_PLAYBACK_STATE -> handleMediaPlaybackState(msg)
            MsgType.MEDIA_VOLUME -> handleMediaVolume(msg)

            MsgType.MEDIA_CMD_PLAY_PAUSE,
            MsgType.MEDIA_CMD_NEXT,
            MsgType.MEDIA_CMD_PREV,
            MsgType.MEDIA_CMD_SEEK,
            MsgType.MEDIA_CMD_VOLUME -> handleMediaCommand(msg)

            MsgType.BATTERY_STATE -> handleBatteryState(msg)
            MsgType.SIGNAL_STATE -> handleSignalState(msg)
        }
    }

    private fun handleDeviceInfo(msg: BTMessage) {
        val name = msg.data.optString("name", "")
        val role = msg.data.optString("role", "")
        Log.d(TAG, "Device info: name=$name, role=$role")
    }

    private fun handleWifiControl(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return
        val enable = msg.data.optBoolean("enable", false)
        scope.launch {
            val success = NetworkController.setWifiEnabled(enable)
            if (success) {
                val currentState = NetworkController.isWifiEnabled(this@CoreService)
                sendMessage(BTMessage.wifiState(currentState))
            } else {
                // 失败也回传当前真实状态,以便主机 UI 回滚开关
                val currentState = NetworkController.isWifiEnabled(this@CoreService)
                sendMessage(BTMessage.wifiState(currentState))
            }
        }
    }

    private fun handleWifiState(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return
        val enabled = msg.data.optBoolean("enabled", false)
        Log.d(TAG, "Wifi state: $enabled")
        // messageListeners 会把 msg 推给 UI,HostActivity 据此更新开关
    }

    private fun handleMobileDataControl(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return
        val enable = msg.data.optBoolean("enable", false)
        scope.launch {
            val success = NetworkController.setMobileDataEnabled(enable)
            val currentState = NetworkController.isMobileDataEnabled(this@CoreService)
            sendMessage(BTMessage.mobileDataState(currentState))
            if (!success) Log.w(TAG, "Mobile data control failed, reverted to $currentState")
        }
    }

    private fun handleMobileDataState(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return
        Log.d(TAG, "Mobile data state: ${msg.data.optBoolean("enabled")}")
    }

    private fun handleHotspotControl(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return
        val enable = msg.data.optBoolean("enable", false)
        scope.launch {
            val result = NetworkController.setHotspotEnabled(this@CoreService, enable)
            val currentState = NetworkController.isHotspotEnabled(this@CoreService)
            // 把实际状态和失败原因一起回传给主机
            sendMessage(BTMessage.hotspotState(currentState, if (!result.success) result.error else ""))
            if (!result.success) {
                Log.w(TAG, "Hotspot ${if (enable) "enable" else "disable"} failed: ${result.error}")
            }
        }
    }

    private fun handleHotspotState(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return
        Log.d(TAG, "Hotspot state: ${msg.data.optBoolean("enabled")}")
    }

    private fun handleRequestState(msg: BTMessage) {
        val target = msg.data.optString("target", "all")
        Log.d(TAG, "Request state: $target")
        if (Prefs.getRole() == Prefs.ROLE_SLAVE) {
            scope.launch { reportNetworkStates() }
            // 也立即上报电池/信号状态
            slaveStateReporter?.reportOnce()
        }
        // 主机端没有需要上报的状态(网络/媒体监听服务会主动推),无需处理
    }

    private fun handleSmsReceived(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return
        if (!Prefs.getBoolean(Prefs.KEY_SMS_SYNC, true)) return

        val sender = msg.data.optString("sender", "")
        val body = msg.data.optString("body", "")
        val time = msg.data.optLong("time", System.currentTimeMillis())

        Log.d(TAG, "SMS received from remote: $sender")

        SmsManager.addSms(sender, body, time, isFromRemote = true)
        SmsNotifier.notifySms(this, sender, body, time)
    }

    /** 备机端:收到主机发来的回复请求,调用真实 SmsManager 发送 */
    private fun handleSmsSend(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return

        val sender = msg.data.optString("sender", "")
        val body = msg.data.optString("body", "")
        if (sender.isEmpty() || body.isEmpty()) {
            sendMessage(BTMessage.smsSendResult(false, "收件人或内容为空"))
            return
        }

        Log.d(TAG, "Sending SMS reply to $sender")
        scope.launch {
            val result = SmsSender.sendSms(sender, body)
            sendMessage(BTMessage.smsSendResult(result.success, result.error))
        }
    }

    /** 主机端:收到备机的发送结果,通过 messageListeners 推给 UI */
    private fun handleSmsSendResult(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return
        val success = msg.data.optBoolean("success", false)
        val error = msg.data.optString("error", "")
        Log.d(TAG, "SMS send result: success=$success, error=$error")
        // msg 会被 messageListeners 推到 HostActivity / SmsPopupActivity
    }

    private fun handleMediaMetadata(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return
        if (!Prefs.getBoolean(Prefs.KEY_MEDIA_SYNC, true)) return

        val title = msg.data.optString("title", "")
        val artist = msg.data.optString("artist", "")
        val album = msg.data.optString("album", "")
        val duration = msg.data.optLong("duration", 0L)

        mediaSessionManager?.updateMetadata(title, artist, album, duration)
    }

    private fun handleMediaPlaybackState(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return
        if (!Prefs.getBoolean(Prefs.KEY_MEDIA_SYNC, true)) return

        val state = msg.data.optInt("state", 0)
        val position = msg.data.optLong("position", 0L)
        val speed = msg.data.optDouble("speed", 1.0).toFloat()

        mediaSessionManager?.updatePlaybackState(state, position, speed)
    }

    private fun handleMediaVolume(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_SLAVE) return
        val volume = msg.data.optInt("volume", 0)
        val maxVolume = msg.data.optInt("max", 15)
        Log.d(TAG, "Media volume: $volume/$maxVolume")
    }

    private fun handleMediaCommand(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return

        val listenerService = MediaListenerService::class.java
        try {
            val field = listenerService.getDeclaredField("instance")
            field.isAccessible = true
            val service = field.get(null) as? MediaListenerService
            val position = if (msg.type == MsgType.MEDIA_CMD_SEEK) {
                msg.data.optLong("position", 0L)
            } else null
            val volume = if (msg.type == MsgType.MEDIA_CMD_VOLUME) {
                msg.data.optInt("volume", 0)
            } else null
            service?.handleControlCommand(msg.type, position, volume)
        } catch (e: Exception) {
            Log.e(TAG, "Handle media command failed", e)
        }
    }

    /** 主机端:收到备机上报的电池状态(消息会被 messageListeners 推给 UI) */
    private fun handleBatteryState(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return
        Log.d(TAG, "Battery state: level=${msg.data.optInt("level")}, plugged=${msg.data.optBoolean("plugged")}")
    }

    /** 主机端:收到备机上报的信号状态(消息会被 messageListeners 推给 UI) */
    private fun handleSignalState(msg: BTMessage) {
        if (Prefs.getRole() != Prefs.ROLE_HOST) return
        Log.d(TAG, "Signal state: level=${msg.data.optInt("level")}, type=${msg.data.optString("type")}")
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val stateText = when (connectionState) {
            BTConnectionManager.State.CONNECTED -> "已连接: $connectedDeviceName"
            BTConnectionManager.State.CONNECTING -> "连接中..."
            BTConnectionManager.State.LISTENING -> "等待连接..."
            BTConnectionManager.State.DISCONNECTED -> "未连接"
        }

        return NotificationCompat.Builder(this, App.CHANNEL_FOREGROUND)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("蓝牙远程控制")
            .setContentText(stateText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FOREGROUND_ID, buildNotification())
    }

    private fun updateNotificationSafe() {
        try {
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Update notification failed", e)
        }
    }
}
