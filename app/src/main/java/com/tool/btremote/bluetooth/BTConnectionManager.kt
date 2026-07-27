package com.tool.btremote.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.tool.btremote.protocol.BTMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BTConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BTConnectionManager"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val APP_NAME = "BTRemote"
        private const val HEARTBEAT_INTERVAL = 15000L
        private const val HEARTBEAT_TIMEOUT = 30000L
        private const val RECONNECT_DELAY = 3000L
    }

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        LISTENING
    }

    interface ConnectionListener {
        fun onStateChanged(state: State)
        fun onMessageReceived(message: BTMessage)
        fun onDeviceConnected(deviceName: String)
    }

    private var state: State = State.DISCONNECTED
        set(value) {
            field = value
            handler.post { listener?.onStateChanged(value) }
        }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var listener: ConnectionListener? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    private var lastHeartbeatTime = 0L
    private var isServerMode = false
    private var targetDeviceAddress: String? = null

    fun setListener(l: ConnectionListener?) {
        listener = l
        if (l != null) {
            handler.post { l.onStateChanged(state) }
        }
    }

    fun getState(): State = state

    fun startServer() {
        if (state == State.LISTENING || state == State.CONNECTED) return
        isServerMode = true
        scope.launch {
            runServer()
        }
    }

    fun connectToDevice(address: String) {
        if (state == State.CONNECTING || state == State.CONNECTED) return
        targetDeviceAddress = address
        isServerMode = false
        state = State.CONNECTING
        scope.launch {
            runClient(address)
        }
    }

    fun sendMessage(message: BTMessage): Boolean {
        if (state != State.CONNECTED) return false
        return try {
            val data = (message.toJson() + "\n").toByteArray()
            outputStream?.write(data)
            outputStream?.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send message failed", e)
            handleDisconnection()
            false
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        closeAll()
        state = State.DISCONNECTED
    }

    fun release() {
        disconnect()
        scope.cancel()
    }

    private suspend fun runServer() {
        try {
            state = State.LISTENING
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(APP_NAME, SPP_UUID)
            Log.d(TAG, "Server socket created, waiting for connection...")

            while (scope.isActive && state == State.LISTENING) {
                try {
                    val clientSocket = serverSocket?.accept() ?: break
                    Log.d(TAG, "Client connected: ${clientSocket.remoteDevice.name}")
                    setupConnection(clientSocket)
                    break
                } catch (e: IOException) {
                    Log.e(TAG, "Server accept failed", e)
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        }
    }

    private suspend fun runClient(address: String) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val device: BluetoothDevice = adapter.getRemoteDevice(address)

        while (scope.isActive) {
            try {
                state = State.CONNECTING
                Log.d(TAG, "Connecting to $address...")

                val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                try {
                    adapter.cancelDiscovery()
                    tmpSocket.connect()
                    setupConnection(tmpSocket)
                    return
                } catch (e: IOException) {
                    Log.e(TAG, "Connect failed, try fallback", e)
                    try {
                        tmpSocket.close()
                    } catch (_: Exception) {}
                }

                try {
                    val fallback = createFallbackSocket(device)
                    if (fallback != null) {
                        fallback.connect()
                        setupConnection(fallback)
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fallback connect failed", e)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Client error", e)
            }

            Log.d(TAG, "Reconnect in $RECONNECT_DELAY ms...")
            delay(RECONNECT_DELAY)
        }
    }

    private fun createFallbackSocket(device: BluetoothDevice): BluetoothSocket? {
        return try {
            val method = device.javaClass.getMethod(
                "createRfcommSocket", Int::class.javaPrimitiveType
            )
            method.invoke(device, 1) as BluetoothSocket
        } catch (e: Exception) {
            Log.e(TAG, "Fallback socket creation failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupConnection(btSocket: BluetoothSocket) {
        socket = btSocket
        inputStream = btSocket.inputStream
        outputStream = btSocket.outputStream
        state = State.CONNECTED
        lastHeartbeatTime = System.currentTimeMillis()

        val deviceName = try {
            btSocket.remoteDevice.name ?: "Unknown"
        } catch (e: SecurityException) {
            "Unknown"
        }
        handler.post { listener?.onDeviceConnected(deviceName) }

        startReading()
        startHeartbeat()
    }

    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(8192)
            val sb = StringBuilder()

            while (scope.isActive && state == State.CONNECTED) {
                try {
                    val bytes = inputStream?.read(buffer) ?: -1
                    if (bytes == -1) {
                        Log.d(TAG, "Stream closed")
                        handleDisconnection()
                        break
                    }

                    val chunk = String(buffer, 0, bytes)
                    sb.append(chunk)

                    var newlineIndex: Int
                    while (sb.indexOf('\n').also { newlineIndex = it } != -1) {
                        val line = sb.substring(0, newlineIndex).trim()
                        sb.delete(0, newlineIndex + 1)

                        if (line.isNotEmpty()) {
                            val msg = BTMessage.fromJson(line)
                            if (msg != null) {
                                handleMessage(msg)
                            }
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Read error", e)
                    handleDisconnection()
                    break
                }
            }
        }
    }

    private fun handleMessage(msg: BTMessage) {
        when (msg.type) {
            com.tool.btremote.protocol.MsgType.HEARTBEAT -> {
                lastHeartbeatTime = System.currentTimeMillis()
                sendMessage(BTMessage.heartbeatAck())
            }
            com.tool.btremote.protocol.MsgType.HEARTBEAT_ACK -> {
                lastHeartbeatTime = System.currentTimeMillis()
            }
            else -> {
                handler.post { listener?.onMessageReceived(msg) }
            }
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (scope.isActive && state == State.CONNECTED) {
                delay(HEARTBEAT_INTERVAL)
                if (state != State.CONNECTED) break

                val elapsed = System.currentTimeMillis() - lastHeartbeatTime
                if (elapsed > HEARTBEAT_TIMEOUT) {
                    Log.d(TAG, "Heartbeat timeout, disconnecting")
                    handleDisconnection()
                    break
                }

                sendMessage(BTMessage.heartbeat())
            }
        }
    }

    private fun handleDisconnection() {
        if (state == State.DISCONNECTED) return
        closeAll()
        state = State.DISCONNECTED

        if (!isServerMode && targetDeviceAddress != null) {
            scheduleReconnect()
        } else if (isServerMode) {
            scope.launch {
                delay(RECONNECT_DELAY)
                if (state == State.DISCONNECTED) {
                    runServer()
                }
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (state == State.DISCONNECTED && targetDeviceAddress != null) {
                runClient(targetDeviceAddress!!)
            }
        }
    }

    private fun closeAll() {
        readJob?.cancel()
        readJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            socket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        outputStream = null
        inputStream = null
        socket = null
        serverSocket = null
    }

    fun getPairedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return if (hasPermission()) {
            adapter.bondedDevices.toList()
        } else {
            emptyList()
        }
    }

    fun isBluetoothEnabled(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        return adapter.isEnabled
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
