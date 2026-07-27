package com.tool.btremote.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tool.btremote.bluetooth.BTConnectionManager
import com.tool.btremote.databinding.ActivitySmsPopupBinding
import com.tool.btremote.protocol.BTMessage
import com.tool.btremote.protocol.MsgType
import com.tool.btremote.service.CoreService
import com.tool.btremote.sms.SmsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsPopupActivity : AppCompatActivity() {

    companion object {
        /** 发送 SMS 后等待结果的最长时间,超时则提示用户 */
        private const val SEND_TIMEOUT_MS = 30_000L
    }

    private lateinit var binding: ActivitySmsPopupBinding
    private var sender: String = ""
    private var body: String = ""
    private var time: Long = 0L
    private var replyMode = false
    /** 是否已发出 SMS_SEND 并在等待 SMS_SEND_RESULT */
    private var awaitingResult = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        if (awaitingResult) {
            awaitingResult = false
            binding.tvSendStatus.text = "发送超时,请确认备机在线后重试"
            binding.tvSendStatus.visibility = View.VISIBLE
            binding.btnReply.isEnabled = true
            binding.btnReply.text = "重试发送"
        }
    }

    private val messageListener: (BTMessage) -> Unit = { msg ->
        runOnUiThread {
            if (msg.type == MsgType.SMS_SEND_RESULT) {
                cancelTimeout()
                awaitingResult = false
                val success = msg.data.optBoolean("success", false)
                val error = msg.data.optString("error", "")
                if (success) {
                    Toast.makeText(this, "已发送", Toast.LENGTH_SHORT).show()
                    addOutgoingSmsToLocal()
                    finish()
                } else {
                    binding.tvSendStatus.text = "发送失败: $error"
                    binding.tvSendStatus.visibility = View.VISIBLE
                    binding.btnReply.isEnabled = true
                    binding.btnReply.text = "重试发送"
                }
            }
        }
    }

    private val stateListener: (BTConnectionManager.State, String) -> Unit = { state, _ ->
        runOnUiThread {
            if (state != BTConnectionManager.State.CONNECTED && awaitingResult) {
                cancelTimeout()
                awaitingResult = false
                binding.tvSendStatus.text = "蓝牙已断开,发送已取消"
                binding.tvSendStatus.visibility = View.VISIBLE
                binding.btnReply.isEnabled = true
                binding.btnReply.text = "重试发送"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        binding = ActivitySmsPopupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sender = intent.getStringExtra("sender") ?: "未知号码"
        body = intent.getStringExtra("body") ?: ""
        time = intent.getLongExtra("time", System.currentTimeMillis())

        binding.tvSender.text = sender
        binding.tvBody.text = body
        binding.tvTime.text = formatTime(time)

        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnReply.setOnClickListener {
            if (!replyMode) {
                enterReplyMode()
            } else {
                sendReply()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CoreService.instance?.addMessageListener(messageListener)
        CoreService.instance?.addStateListener(stateListener)
    }

    override fun onPause() {
        super.onPause()
        cancelTimeout()
        CoreService.instance?.removeMessageListener(messageListener)
        CoreService.instance?.removeStateListener(stateListener)
    }

    private fun enterReplyMode() {
        replyMode = true
        binding.tilReply.visibility = View.VISIBLE
        binding.btnReply.text = "发送"
        binding.btnClose.text = "取消"
        binding.etReply.requestFocus()
    }

    private fun sendReply() {
        val replyBody = binding.etReply.text?.toString()?.trim().orEmpty()
        if (TextUtils.isEmpty(replyBody)) {
            Toast.makeText(this, "请输入回复内容", Toast.LENGTH_SHORT).show()
            return
        }

        val connected = CoreService.instance?.getConnectionState() == BTConnectionManager.State.CONNECTED
        if (!connected) {
            Toast.makeText(this, "未连接备机,无法发送", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnReply.isEnabled = false
        binding.btnReply.text = "发送中..."
        binding.tvSendStatus.visibility = View.GONE

        awaitingResult = true
        val sent = CoreService.instance?.sendMessage(BTMessage.smsSend(sender, replyBody)) ?: false
        if (!sent) {
            awaitingResult = false
            binding.tvSendStatus.text = "发送失败:蓝牙未连接"
            binding.tvSendStatus.visibility = View.VISIBLE
            binding.btnReply.isEnabled = true
            binding.btnReply.text = "重试发送"
            return
        }
        mainHandler.postDelayed(timeoutRunnable, SEND_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
    }

    private fun addOutgoingSmsToLocal() {
        val replyBody = binding.etReply.text?.toString()?.trim().orEmpty()
        if (replyBody.isNotEmpty()) {
            SmsManager.addSms(sender, replyBody, System.currentTimeMillis(), isFromRemote = false)
        }
    }

    private fun formatTime(time: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(time))
    }
}
