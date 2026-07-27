package com.tool.btremote.ui

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.tool.btremote.bluetooth.BTConnectionManager
import com.tool.btremote.common.Prefs
import com.tool.btremote.databinding.ActivityHostBinding
import com.tool.btremote.databinding.DialogNewSmsBinding
import com.tool.btremote.media.MediaListenerService
import com.tool.btremote.R
import com.tool.btremote.protocol.BTMessage
import com.tool.btremote.protocol.MsgType
import com.tool.btremote.service.CoreService
import com.tool.btremote.sms.SmsManager
import com.tool.btremote.sms.SmsMessageItem

class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private var smsAdapter: SmsListAdapter? = null
    private var currentState: BTConnectionManager.State = BTConnectionManager.State.DISCONNECTED
    private var connectedDevice = ""

    /** 防止收到远端状态回写时再次触发 switch listener 导致回环 */
    private var syncingFromRemote = false

    /** 待发送的短信(等收到 SMS_SEND_RESULT 后用来保存到本地列表) */
    private var pendingRecipient: String? = null
    private var pendingBody: String? = null

    private val stateListener: (BTConnectionManager.State, String) -> Unit = { state, device ->
        currentState = state
        connectedDevice = device
        updateConnectionState(state, device)
    }

    private val smsListener: (List<SmsMessageItem>) -> Unit = { list ->
        runOnUiThread {
            smsAdapter?.submitList(list)
        }
    }

    private val messageListener: (BTMessage) -> Unit = { msg ->
        runOnUiThread { handleIncomingMessage(msg) }
    }

    private val hostMediaListener = object : MediaListenerService.Listener {
        override fun onHostMediaChanged(info: MediaListenerService.HostMediaInfo) {
            runOnUiThread {
                val titleDisplay = info.title.ifEmpty { "未播放" }
                binding.tvHostMediaTitle.text = if (info.isPlaying) "▶ $titleDisplay" else "⏸ $titleDisplay"
                binding.tvHostMediaArtist.text = info.artist
                binding.tvHostMediaSource.text = buildString {
                    if (info.packageName.isNotEmpty()) {
                        append("来源: ${info.packageName}")
                    }
                    if (info.duration > 0L) {
                        if (isNotEmpty()) append(" · ")
                        append("时长: ${formatDuration(info.duration)}")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupConnectionCard()
        setupNetworkControls()
        setupSmsList()
        setupMediaSettings()
        setupAbout()
        setupBottomNav()

        CoreService.start(this)
    }

    override fun onResume() {
        super.onResume()
        CoreService.instance?.addStateListener(stateListener)
        CoreService.instance?.addMessageListener(messageListener)
        SmsManager.addListener(smsListener)
        MediaListenerService.addHostListener(hostMediaListener)
    }

    override fun onPause() {
        super.onPause()
        CoreService.instance?.removeStateListener(stateListener)
        CoreService.instance?.removeMessageListener(messageListener)
        SmsManager.removeListener(smsListener)
        MediaListenerService.removeHostListener(hostMediaListener)
    }

    private fun setupToolbar() {
        binding.toolbar.title = "主机控制"
        setSupportActionBar(binding.toolbar)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_host, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_switch_role -> {
                showSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 主机端处理来自备机的消息 */
    private fun handleIncomingMessage(msg: BTMessage) {
        when (msg.type) {
            MsgType.WIFI_STATE -> {
                val enabled = msg.data.optBoolean("enabled", false)
                syncingFromRemote = true
                binding.switchWifi.isChecked = enabled
                syncingFromRemote = false
            }
            MsgType.MOBILE_DATA_STATE -> {
                val enabled = msg.data.optBoolean("enabled", false)
                syncingFromRemote = true
                binding.switchMobileData.isChecked = enabled
                syncingFromRemote = false
            }
            MsgType.HOTSPOT_STATE -> {
                val enabled = msg.data.optBoolean("enabled", false)
                val error = msg.data.optString("error", "")
                syncingFromRemote = true
                binding.switchHotspot.isChecked = enabled
                syncingFromRemote = false
                if (error.isNotEmpty()) {
                    Toast.makeText(this, "热点打开失败: $error", Toast.LENGTH_LONG).show()
                }
            }
            MsgType.SMS_SEND_RESULT -> {
                val success = msg.data.optBoolean("success", false)
                val error = msg.data.optString("error", "")
                if (success) {
                    Toast.makeText(this, "短信已发送", Toast.LENGTH_SHORT).show()
                    // 把已发送的短信保存到本地列表
                    val recipient = pendingRecipient
                    val body = pendingBody
                    if (!recipient.isNullOrEmpty() && !body.isNullOrEmpty()) {
                        SmsManager.addSms(recipient, body, System.currentTimeMillis(), isFromRemote = false)
                    }
                    pendingRecipient = null
                    pendingBody = null
                } else {
                    Toast.makeText(this, "短信发送失败: $error", Toast.LENGTH_LONG).show()
                    pendingRecipient = null
                    pendingBody = null
                }
            }
            MsgType.BATTERY_STATE -> {
                val level = msg.data.optInt("level", -1)
                val plugged = msg.data.optBoolean("plugged", false)
                val temp = msg.data.optInt("temperature", 0)
                binding.tvSlaveBattery.text = if (level in 0..100) {
                    "$level%${if (plugged) " 充电中" else ""}"
                } else "未知"
                binding.tvSlaveTemp.text = if (temp > 0) {
                    "${temp / 10.0}°C"
                } else "--"
            }
            MsgType.SIGNAL_STATE -> {
                val level = msg.data.optInt("level", -1)
                val type = msg.data.optString("type", "")
                binding.tvSlaveSignal.text = if (level in 0..4) {
                    val bars = "▮".repeat(level + 1) + "▯".repeat(4 - level)
                    if (type.isNotEmpty()) "$bars $type" else bars
                } else "未知"
            }
            else -> Unit
        }
    }

    private fun setupConnectionCard() {
        binding.btnSelectDevice.setOnClickListener {
            showDeviceList()
        }

        binding.btnDisconnect.setOnClickListener {
            CoreService.instance?.disconnect()
        }
    }

    private fun setupNetworkControls() {
        binding.switchWifi.setOnCheckedChangeListener { _, isChecked ->
            if (syncingFromRemote) return@setOnCheckedChangeListener
            if (currentState == BTConnectionManager.State.CONNECTED) {
                CoreService.instance?.sendMessage(BTMessage.wifiControl(isChecked))
            } else {
                binding.switchWifi.isChecked = !isChecked
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchMobileData.setOnCheckedChangeListener { _, isChecked ->
            if (syncingFromRemote) return@setOnCheckedChangeListener
            if (currentState == BTConnectionManager.State.CONNECTED) {
                CoreService.instance?.sendMessage(BTMessage.mobileDataControl(isChecked))
            } else {
                binding.switchMobileData.isChecked = !isChecked
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchHotspot.setOnCheckedChangeListener { _, isChecked ->
            if (syncingFromRemote) return@setOnCheckedChangeListener
            if (currentState == BTConnectionManager.State.CONNECTED) {
                CoreService.instance?.sendMessage(BTMessage.hotspotControl(isChecked))
            } else {
                binding.switchHotspot.isChecked = !isChecked
                Toast.makeText(this, "请先连接设备", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupSmsList() {
        smsAdapter = SmsListAdapter { item ->
            val intent = Intent(this, SmsPopupActivity::class.java).apply {
                putExtra("sender", item.sender)
                putExtra("body", item.body)
                putExtra("time", item.time)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
        binding.rvSms.apply {
            layoutManager = LinearLayoutManager(this@HostActivity)
            adapter = smsAdapter
        }

        binding.btnNewSms.setOnClickListener {
            showNewSmsDialog()
        }
    }

    private fun showNewSmsDialog() {
        val dialogBinding = DialogNewSmsBinding.inflate(layoutInflater)
        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("发送", null)
            .setNegativeButton("取消", null)
            .create()

        alertDialog.setOnShowListener {
            val sendBtn = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)
            sendBtn.setOnClickListener {
                val recipient = dialogBinding.etRecipient.text?.toString()?.trim().orEmpty()
                val body = dialogBinding.etSmsBody.text?.toString()?.trim().orEmpty()

                if (TextUtils.isEmpty(recipient)) {
                    dialogBinding.tilRecipient.error = "请输入收件人手机号"
                    return@setOnClickListener
                }
                dialogBinding.tilRecipient.error = null

                if (TextUtils.isEmpty(body)) {
                    dialogBinding.tilSmsBody.error = "请输入短信内容"
                    return@setOnClickListener
                }
                dialogBinding.tilSmsBody.error = null

                if (currentState != BTConnectionManager.State.CONNECTED) {
                    Toast.makeText(this, "未连接备机,无法发送", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val sent = CoreService.instance?.sendMessage(BTMessage.smsSend(recipient, body)) ?: false
                if (sent) {
                    pendingRecipient = recipient
                    pendingBody = body
                    Toast.makeText(this, "已发送到备机,等待发送结果", Toast.LENGTH_SHORT).show()
                    alertDialog.dismiss()
                } else {
                    Toast.makeText(this, "发送失败,请检查蓝牙连接", Toast.LENGTH_SHORT).show()
                }
            }
        }

        alertDialog.show()
    }

    private fun setupMediaSettings() {
        binding.btnGrantMediaPermission.setOnClickListener {
            requestMediaPermission()
        }

        binding.switchMediaSync.setOnCheckedChangeListener { _, isChecked ->
            Prefs.putBoolean(Prefs.KEY_MEDIA_SYNC, isChecked)
        }

        binding.switchMediaSync.isChecked = Prefs.getBoolean(Prefs.KEY_MEDIA_SYNC, true)

        updateMediaPermissionStatus()
    }

    private fun setupAbout() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0"
        }
        binding.tvVersionName.text = versionName

        binding.tvGithubLink.setOnClickListener {
            val uri = android.net.Uri.parse("https://github.com/XingHui-8183/suiyin-")
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_control -> {
                    binding.viewFlipper.displayedChild = 0
                    true
                }
                R.id.nav_sms -> {
                    binding.viewFlipper.displayedChild = 1
                    true
                }
                R.id.nav_settings -> {
                    binding.viewFlipper.displayedChild = 2
                    true
                }
                else -> false
            }
        }
        binding.bottomNav.selectedItemId = R.id.nav_control
    }

    private fun updateConnectionState(state: BTConnectionManager.State, device: String) {
        runOnUiThread {
            when (state) {
                BTConnectionManager.State.CONNECTED -> {
                    binding.tvConnectionStatus.text = "已连接"
                    binding.tvDeviceName.text = device
                    binding.btnDisconnect.isEnabled = true
                }
                BTConnectionManager.State.CONNECTING -> {
                    binding.tvConnectionStatus.text = "连接中..."
                    binding.tvDeviceName.text = ""
                    binding.btnDisconnect.isEnabled = false
                }
                BTConnectionManager.State.LISTENING -> {
                    binding.tvConnectionStatus.text = "等待连接..."
                    binding.tvDeviceName.text = ""
                    binding.btnDisconnect.isEnabled = true
                }
                BTConnectionManager.State.DISCONNECTED -> {
                    binding.tvConnectionStatus.text = "未连接"
                    binding.tvDeviceName.text = "点击选择设备"
                    binding.btnDisconnect.isEnabled = false
                    resetNetworkSwitches()
                    resetSlaveStatus()
                }
            }
        }
    }

    private fun resetNetworkSwitches() {
        binding.switchWifi.isChecked = false
        binding.switchMobileData.isChecked = false
        binding.switchHotspot.isChecked = false
    }

    private fun resetSlaveStatus() {
        binding.tvSlaveBattery.text = "未知"
        binding.tvSlaveTemp.text = "--"
        binding.tvSlaveSignal.text = "未知"
    }

    private fun showDeviceList() {
        val service = CoreService.instance ?: run {
            Toast.makeText(this, "服务未启动", Toast.LENGTH_SHORT).show()
            return
        }

        val devices = service.getPairedDevices()
        if (devices.isNullOrEmpty()) {
            Toast.makeText(this, "没有已配对的设备，请先在系统设置中配对", Toast.LENGTH_SHORT).show()
            return
        }

        val deviceNames = devices.map { device ->
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                device.address
            } else {
                "${device.name ?: "未知设备"}\n${device.address}"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("选择设备")
            .setItems(deviceNames.toTypedArray()) { _, which ->
                val device = devices[which]
                Prefs.putString(Prefs.KEY_TARGET_DEVICE, device.address)
                service.connectToDevice(device.address)
            }
            .show()
    }

    private fun requestMediaPermission() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请在设置中开启本应用的通知使用权", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMediaPermissionStatus() {
        val granted = isNotificationListenerEnabled()
        binding.tvMediaPermissionStatus.text = if (granted) "已授权" else "未授权"
        binding.btnGrantMediaPermission.isEnabled = !granted
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, MediaListenerService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun showSettings() {
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(arrayOf("切换角色", "退出")) { _, which ->
                when (which) {
                    0 -> {
                        Prefs.setRole("")
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                    1 -> {
                        finish()
                    }
                }
            }
            .show()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "%d:%02d".format(m, s)
    }
}
