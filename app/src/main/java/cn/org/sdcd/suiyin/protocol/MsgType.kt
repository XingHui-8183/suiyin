package cn.org.sdcd.suiyin.protocol

object MsgType {
    const val HEARTBEAT = 0
    const val HEARTBEAT_ACK = 1

    const val DEVICE_INFO = 10

    const val WIFI_STATE = 100
    const val WIFI_CONTROL = 101

    const val MOBILE_DATA_STATE = 110
    const val MOBILE_DATA_CONTROL = 111

    const val HOTSPOT_STATE = 120
    const val HOTSPOT_CONTROL = 121

    // 请求对端上报当前状态(连接建立时双向发送)
    const val REQUEST_STATE = 130

    const val SMS_RECEIVED = 200
    // 主机->备机:转发回复内容,备机调用 SmsManager 真实发送
    const val SMS_SEND = 201
    // 备机->主机:回复发送结果(success/fail + error)
    const val SMS_SEND_RESULT = 202

    const val MEDIA_STATE = 300
    const val MEDIA_METADATA = 301
    const val MEDIA_PLAYBACK_STATE = 302
    const val MEDIA_VOLUME = 303

    const val MEDIA_CMD_PLAY_PAUSE = 310
    const val MEDIA_CMD_NEXT = 311
    const val MEDIA_CMD_PREV = 312
    const val MEDIA_CMD_SEEK = 313
    const val MEDIA_CMD_VOLUME = 314

    // 备机状态上报
    const val BATTERY_STATE = 400
    const val SIGNAL_STATE = 401
}
