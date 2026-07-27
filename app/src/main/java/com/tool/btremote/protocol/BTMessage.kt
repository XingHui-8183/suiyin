package com.tool.btremote.protocol

import org.json.JSONObject

data class BTMessage(
    val type: Int,
    val data: JSONObject = JSONObject()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("type", type)
        json.put("data", data)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): BTMessage? {
            return try {
                val json = JSONObject(jsonStr)
                val type = json.getInt("type")
                val data = json.optJSONObject("data") ?: JSONObject()
                BTMessage(type, data)
            } catch (e: Exception) {
                null
            }
        }

        fun heartbeat(): BTMessage = BTMessage(MsgType.HEARTBEAT)
        fun heartbeatAck(): BTMessage = BTMessage(MsgType.HEARTBEAT_ACK)

        fun deviceInfo(name: String, role: String): BTMessage {
            val data = JSONObject()
            data.put("name", name)
            data.put("role", role)
            return BTMessage(MsgType.DEVICE_INFO, data)
        }

        fun wifiState(enabled: Boolean): BTMessage {
            val data = JSONObject()
            data.put("enabled", enabled)
            return BTMessage(MsgType.WIFI_STATE, data)
        }

        fun wifiControl(enable: Boolean): BTMessage {
            val data = JSONObject()
            data.put("enable", enable)
            return BTMessage(MsgType.WIFI_CONTROL, data)
        }

        fun mobileDataState(enabled: Boolean): BTMessage {
            val data = JSONObject()
            data.put("enabled", enabled)
            return BTMessage(MsgType.MOBILE_DATA_STATE, data)
        }

        fun mobileDataControl(enable: Boolean): BTMessage {
            val data = JSONObject()
            data.put("enable", enable)
            return BTMessage(MsgType.MOBILE_DATA_CONTROL, data)
        }

        fun hotspotState(enabled: Boolean, error: String = ""): BTMessage {
            val data = JSONObject()
            data.put("enabled", enabled)
            if (error.isNotEmpty()) data.put("error", error)
            return BTMessage(MsgType.HOTSPOT_STATE, data)
        }

        fun hotspotControl(enable: Boolean): BTMessage {
            val data = JSONObject()
            data.put("enable", enable)
            return BTMessage(MsgType.HOTSPOT_CONTROL, data)
        }

        /** 请求对端上报当前状态。target 用于声明请求方期望哪类状态(network / media / all) */
        fun requestState(target: String = "all"): BTMessage {
            val data = JSONObject()
            data.put("target", target)
            return BTMessage(MsgType.REQUEST_STATE, data)
        }

        fun smsReceived(sender: String, body: String, time: Long): BTMessage {
            val data = JSONObject()
            data.put("sender", sender)
            data.put("body", body)
            data.put("time", time)
            return BTMessage(MsgType.SMS_RECEIVED, data)
        }

        /** 主机请求备机发送短信回复 */
        fun smsSend(sender: String, body: String): BTMessage {
            val data = JSONObject()
            data.put("sender", sender)
            data.put("body", body)
            return BTMessage(MsgType.SMS_SEND, data)
        }

        /** 备机回复发送结果 */
        fun smsSendResult(success: Boolean, error: String = ""): BTMessage {
            val data = JSONObject()
            data.put("success", success)
            data.put("error", error)
            return BTMessage(MsgType.SMS_SEND_RESULT, data)
        }

        fun mediaMetadata(title: String, artist: String, album: String, duration: Long): BTMessage {
            val data = JSONObject()
            data.put("title", title)
            data.put("artist", artist)
            data.put("album", album)
            data.put("duration", duration)
            return BTMessage(MsgType.MEDIA_METADATA, data)
        }

        fun mediaPlaybackState(state: Int, position: Long, speed: Float): BTMessage {
            val data = JSONObject()
            data.put("state", state)
            data.put("position", position)
            data.put("speed", speed.toDouble())
            return BTMessage(MsgType.MEDIA_PLAYBACK_STATE, data)
        }

        fun mediaVolume(volume: Int, maxVolume: Int): BTMessage {
            val data = JSONObject()
            data.put("volume", volume)
            data.put("max", maxVolume)
            return BTMessage(MsgType.MEDIA_VOLUME, data)
        }

        fun mediaCmdPlayPause(): BTMessage = BTMessage(MsgType.MEDIA_CMD_PLAY_PAUSE)
        fun mediaCmdNext(): BTMessage = BTMessage(MsgType.MEDIA_CMD_NEXT)
        fun mediaCmdPrev(): BTMessage = BTMessage(MsgType.MEDIA_CMD_PREV)

        fun mediaCmdSeek(position: Long): BTMessage {
            val data = JSONObject()
            data.put("position", position)
            return BTMessage(MsgType.MEDIA_CMD_SEEK, data)
        }

        fun mediaCmdVolume(volume: Int): BTMessage {
            val data = JSONObject()
            data.put("volume", volume)
            return BTMessage(MsgType.MEDIA_CMD_VOLUME, data)
        }

        /** 备机上报电池状态 */
        fun batteryState(
            level: Int,
            scale: Int = 100,
            plugged: Boolean = false,
            temperature: Int = 0
        ): BTMessage {
            val data = JSONObject()
            data.put("level", level)
            data.put("scale", scale)
            data.put("plugged", plugged)
            data.put("temperature", temperature)
            return BTMessage(MsgType.BATTERY_STATE, data)
        }

        /** 备机上报信号强度 */
        fun signalState(level: Int, type: String = ""): BTMessage {
            val data = JSONObject()
            data.put("level", level)
            data.put("type", type)
            return BTMessage(MsgType.SIGNAL_STATE, data)
        }
    }
}
