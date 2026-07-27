package com.tool.btremote.ui

import android.media.AudioManager
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tool.btremote.bluetooth.BTConnectionManager
import com.tool.btremote.common.PermissionHelper
import com.tool.btremote.common.Prefs
import com.tool.btremote.databinding.ActivitySlaveBinding
import com.tool.btremote.keepalive.KeepAliveManager
import com.tool.btremote.media.MediaSessionManager
import com.tool.btremote.network.NetworkController
import com.tool.btremote.protocol.BTMessage
import com.tool.btremote.service.CoreService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SlaveActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySlaveBinding
    private var keepAliveManager: KeepAliveManager? = null
    private var mediaManager: MediaSessionManager? = null

    /** 用户正在拖动进度条时为 true,避免 onMediaUpdated 把它强制回滚 */
    private var userSeeking = false
    private var userVolumeSeeking = false

    private val stateListener: (BTConnectionManager.State, String) -> Unit = { state, device ->
        runOnUiThread {
            updateConnectionState(state, device)
        }
    }

    private val mediaListener = object : MediaSessionManager.Listener {
        override fun onMediaUpdated(info: MediaSessionManager.MediaInfo) {
            runOnUiThread { updateMediaUI(info) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySlaveBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupStatusCard()
        setupMediaControls()
        setupSwitches()
        setupSettings()

        CoreService.start(this)
        keepAliveManager = KeepAliveManager(this)
    }

    override fun onResume() {
        super.onResume()
        CoreService.instance?.addStateListener(stateListener)
        mediaManager = CoreService.instance?.getMediaSessionManager()
        mediaManager?.addListener(mediaListener)
        checkBatteryOptimization()
        checkWriteSettings()
    }

    override fun onPause() {
        super.onPause()
        CoreService.instance?.removeStateListener(stateListener)
        mediaManager?.removeListener(mediaListener)
    }

    private fun setupToolbar() {
        binding.toolbar.title = "备机模式"
        setSupportActionBar(binding.toolbar)
    }

    private fun setupStatusCard() {
        binding.tvDeviceInfo.text = try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            "蓝牙设备名: ${adapter?.name ?: "未知"}"
        } catch (e: SecurityException) {
            "蓝牙设备名: 无权限"
        }
    }

    private fun setupMediaControls() {
        binding.btnMediaPrev.setOnClickListener {
            CoreService.instance?.sendMessage(BTMessage.mediaCmdPrev())
        }
        binding.btnMediaNext.setOnClickListener {
            CoreService.instance?.sendMessage(BTMessage.mediaCmdNext())
        }
        binding.btnMediaPlayPause.setOnClickListener {
            CoreService.instance?.sendMessage(BTMessage.mediaCmdPlayPause())
        }

        binding.sbMediaProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                val info = mediaManager?.getCurrentInfo() ?: return
                if (info.duration <= 0) return
                val targetPos = (info.duration * (sb?.progress ?: 0) / 100f).toLong()
                CoreService.instance?.sendMessage(BTMessage.mediaCmdSeek(targetPos))
            }
        })

        // 此处 setupMediaControls 在 onCreate 中调用,mediaManager 尚未赋值(在 onResume 才绑定)。
        // 先用系统 AudioManager 初始化 max,updateMediaUI 收到媒体信息后会再次校正。
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        binding.sbMediaVolume.max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        binding.sbMediaVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}

            override fun onStartTrackingTouch(sb: SeekBar?) {
                userVolumeSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                userVolumeSeeking = false
                CoreService.instance?.sendMessage(BTMessage.mediaCmdVolume(sb?.progress ?: 0))
            }
        })
    }

    private fun updateMediaUI(info: MediaSessionManager.MediaInfo) {
        binding.tvMediaTitle.text = info.title.ifEmpty { "未播放" }
        binding.tvMediaArtist.text = info.artist
        binding.btnMediaPlayPause.text = if (info.isPlaying) "暂停" else "播放"

        if (!userSeeking) {
            binding.sbMediaProgress.progress = info.progress
        }

        if (!userVolumeSeeking) {
            binding.sbMediaVolume.max = info.maxVolume
            binding.sbMediaVolume.progress = info.volume
        }
        binding.tvMediaVolume.text = "${info.volume}/${info.maxVolume}"
    }

    private fun setupSwitches() {
        binding.switchAutoStart.isChecked = Prefs.getBoolean(Prefs.KEY_AUTO_START, true)
        binding.switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            Prefs.putBoolean(Prefs.KEY_AUTO_START, isChecked)
        }

        binding.switchKeepAlive.isChecked = Prefs.getBoolean(Prefs.KEY_KEEP_ALIVE, true)
        binding.switchKeepAlive.setOnCheckedChangeListener { _, isChecked ->
            Prefs.putBoolean(Prefs.KEY_KEEP_ALIVE, isChecked)
            if (isChecked) {
                keepAliveManager?.start()
            } else {
                keepAliveManager?.stop()
            }
        }

        binding.switchSmsSync.isChecked = Prefs.getBoolean(Prefs.KEY_SMS_SYNC, true)
        binding.switchSmsSync.setOnCheckedChangeListener { _, isChecked ->
            Prefs.putBoolean(Prefs.KEY_SMS_SYNC, isChecked)
        }

        binding.switchMediaSync.isChecked = Prefs.getBoolean(Prefs.KEY_MEDIA_SYNC, true)
        binding.switchMediaSync.setOnCheckedChangeListener { _, isChecked ->
            Prefs.putBoolean(Prefs.KEY_MEDIA_SYNC, isChecked)
        }
    }

    private fun setupSettings() {
        binding.btnSwitchRole.setOnClickListener {
            Prefs.setRole("")
            startActivity(android.content.Intent(this, MainActivity::class.java))
            finish()
        }

        binding.btnBatteryOpt.setOnClickListener {
            requestBatteryOptimization()
        }

        binding.btnGrantWriteSettings.setOnClickListener {
            if (!PermissionHelper.requestWriteSettings(this)) {
                Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请开启本应用的修改系统设置权限", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnHotspotDiagnose.setOnClickListener {
            showHotspotDiagnosis()
        }
    }

    /** 热点诊断:收集 root/权限/当前状态并尝试开启,弹窗显示详细结果 */
    private fun showHotspotDiagnosis() {
        CoroutineScope(Dispatchers.Main).launch {
            val sb = StringBuilder()
            sb.appendLine("===== 热点诊断 =====")
            sb.appendLine()

            // 1. 基础状态
            val writeSettings = PermissionHelper.canWriteSettings(this@SlaveActivity)
            sb.appendLine("修改系统设置权限: ${if (writeSettings) "已授权" else "未授权"}")

            val hotspotOn = NetworkController.isHotspotEnabled(this@SlaveActivity)
            sb.appendLine("当前热点状态: ${if (hotspotOn) "已开启" else "未开启"}")

            val wifiOn = NetworkController.isWifiEnabled(this@SlaveActivity)
            sb.appendLine("当前 WiFi 状态: ${if (wifiOn) "已开启" else "未开启"}")

            // 2. Root 检测
            val hasRoot = withContext(Dispatchers.IO) { NetworkController.isRootAvailable() }
            sb.appendLine("Root 权限: ${if (hasRoot) "已获取" else "未获取"}")
            sb.appendLine()

            // 3. 尝试开启热点
            sb.appendLine("----- 尝试开启热点 -----")
            val result = NetworkController.setHotspotEnabled(this@SlaveActivity, true)
            sb.appendLine("结果: ${if (result.success) "成功" else "失败"}")
            if (!result.success) {
                sb.appendLine()
                sb.appendLine("失败详情:")
                sb.appendLine(result.error)
            }

            // 4. 开启后状态
            val hotspotOnAfter = NetworkController.isHotspotEnabled(this@SlaveActivity)
            val wifiOnAfter = NetworkController.isWifiEnabled(this@SlaveActivity)
            sb.appendLine()
            sb.appendLine("----- 操作后状态 -----")
            sb.appendLine("热点: ${if (hotspotOnAfter) "已开启" else "未开启"}")
            sb.appendLine("WiFi: ${if (wifiOnAfter) "已开启" else "未开启"}")

            // 5. 建议
            sb.appendLine()
            sb.appendLine("----- 建议 -----")
            if (!hasRoot) {
                sb.appendLine("· 备机未获取 root,MIUI 上必须 root 才能开热点")
                sb.appendLine("  请在 Magisk 管理器中为本应用授予 root 权限")
            }
            if (!writeSettings) {
                sb.appendLine("· 未授予修改系统设置权限")
            }
            if (hasRoot && !hotspotOnAfter) {
                sb.appendLine("· 已有 root 但热点仍开不了,可能原因:")
                sb.appendLine("  1. 首次使用需手动在系统设置里开一次热点以初始化配置")
                sb.appendLine("     (这是 MIUI 常见情况——系统要先有 SSID/密码配置")
                sb.appendLine("  2. 安装 LSPosed 并启用热点权限Hook 模块")
                sb.appendLine("  3. 点击下方按钮直接打开系统热点设置")
            }

            val dialog = AlertDialog.Builder(this@SlaveActivity)
                .setTitle("热点诊断结果")
                .setMessage(sb.toString())
                .setPositiveButton("关闭", null)

            if (hasRoot && !hotspotOnAfter) {
                dialog.setNeutralButton("打开系统热点设置") { _, _ ->
                    openHotspotSettings()
                }
            }
            dialog.show()
        }
    }

    private fun openHotspotSettings() {
        try {
            val intent = android.content.Intent()
            intent.setClassName(
                "com.android.settings",
                "com.android.settings.TetherSettings"
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateConnectionState(state: BTConnectionManager.State, device: String) {
        when (state) {
            BTConnectionManager.State.CONNECTED -> {
                binding.tvConnectionStatus.text = "已连接"
                binding.tvConnectedDevice.text = device
                binding.ivStatus.alpha = 1f
            }
            BTConnectionManager.State.LISTENING -> {
                binding.tvConnectionStatus.text = "等待连接中..."
                binding.tvConnectedDevice.text = ""
                binding.ivStatus.alpha = 0.5f
            }
            BTConnectionManager.State.CONNECTING -> {
                binding.tvConnectionStatus.text = "连接中..."
                binding.tvConnectedDevice.text = ""
                binding.ivStatus.alpha = 0.5f
            }
            BTConnectionManager.State.DISCONNECTED -> {
                binding.tvConnectionStatus.text = "未连接"
                binding.tvConnectedDevice.text = "等待主机连接"
                binding.ivStatus.alpha = 0.3f
                CoreService.instance?.startServer()
            }
        }
    }

    private fun checkBatteryOptimization() {
        val ignoring = keepAliveManager?.isIgnoringBatteryOptimizations() ?: false
        binding.tvBatteryStatus.text = if (ignoring) "已忽略电池优化" else "未忽略电池优化(推荐开启)"
    }

    private fun checkWriteSettings() {
        val granted = PermissionHelper.canWriteSettings(this)
        binding.tvWriteSettingsStatus.text = if (granted) "已授权" else "未授权(开启热点必需)"
        binding.btnGrantWriteSettings.isEnabled = !granted
    }

    private fun requestBatteryOptimization() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开设置", Toast.LENGTH_SHORT).show()
        }
    }
}
