package cn.org.sdcd.suiyin.media

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import cn.org.sdcd.suiyin.common.Prefs
import cn.org.sdcd.suiyin.protocol.BTMessage
import cn.org.sdcd.suiyin.service.CoreService

class MediaListenerService : NotificationListenerService() {

    /** 主机端 UI 用来订阅当前播放媒体的元数据变化(主线程回调) */
    interface Listener {
        fun onHostMediaChanged(info: HostMediaInfo)
    }

    data class HostMediaInfo(
        val packageName: String,
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val state: Int,
        val position: Long
    ) {
        val isPlaying: Boolean get() = state == PlaybackState.STATE_PLAYING
    }

    companion object {
        private const val TAG = "MediaListenerService"
        private var instance: MediaListenerService? = null

        fun isRunning(): Boolean = instance != null

        private val hostListeners = mutableListOf<Listener>()

        fun addHostListener(l: Listener) {
            hostListeners.add(l)
            instance?.let { svc -> l.onHostMediaChanged(svc.snapshotHostInfo()) }
        }

        fun removeHostListener(l: Listener) {
            hostListeners.remove(l)
        }
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())

    private var lastTitle = ""
    private var lastArtist = ""
    private var lastAlbum = ""
    private var lastDuration = 0L
    private var lastState = PlaybackState.STATE_NONE
    private var lastPosition = 0L
    private var lastPackageName = ""

    private val metadataCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            sendMetadata(metadata)
            updateHostSnapshot(metadata, activeController?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            sendPlaybackState(state)
            updateHostSnapshot(activeController?.metadata, state)
        }
    }

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateActiveController(controllers)
        }

    private fun snapshotHostInfo(): HostMediaInfo = HostMediaInfo(
        packageName = lastPackageName,
        title = lastTitle,
        artist = lastArtist,
        album = lastAlbum,
        duration = lastDuration,
        state = lastState,
        position = lastPosition
    )

    private fun updateHostSnapshot(metadata: MediaMetadata?, state: PlaybackState?) {
        lastTitle = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: lastTitle
        lastArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: lastArtist
        lastAlbum = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: lastAlbum
        lastDuration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: lastDuration
        lastState = state?.state ?: lastState
        lastPosition = state?.position ?: lastPosition
        lastPackageName = activeController?.packageName ?: lastPackageName
        val info = snapshotHostInfo()
        hostListeners.forEach { it.onHostMediaChanged(info) }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MediaListenerService created")
        setupMediaSessionListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        cleanup()
        Log.d(TAG, "MediaListenerService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {}

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun setupMediaSessionListener() {
        try {
            mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(this, MediaListenerService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                activeSessionsListener, component, handler
            )
            val controllers = mediaSessionManager?.getActiveSessions(component)
            updateActiveController(controllers)
        } catch (e: Exception) {
            Log.e(TAG, "Setup media session listener failed", e)
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        activeController?.unregisterCallback(metadataCallback)

        if (controllers.isNullOrEmpty()) {
            activeController = null
            Log.d(TAG, "No active media sessions")
            // 重置当前快照并通知 UI 显示"未播放"
            lastTitle = ""
            lastArtist = ""
            lastAlbum = ""
            lastDuration = 0L
            lastState = PlaybackState.STATE_NONE
            lastPosition = 0L
            lastPackageName = ""
            hostListeners.forEach { it.onHostMediaChanged(snapshotHostInfo()) }
            return
        }

        val controller = controllers.firstOrNull {
            val pkg = it.packageName
            pkg != packageName
        } ?: controllers.firstOrNull()

        activeController = controller
        controller?.registerCallback(metadataCallback, handler)

        Log.d(TAG, "Active media session: ${controller?.packageName}")
        sendMetadata(controller?.metadata)
        sendPlaybackState(controller?.playbackState)
        updateHostSnapshot(controller?.metadata, controller?.playbackState)
    }

    private fun sendMetadata(metadata: MediaMetadata?) {
        if (!Prefs.getBoolean(Prefs.KEY_MEDIA_SYNC, true)) return
        if (Prefs.getRole() != Prefs.ROLE_HOST) return

        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        CoreService.instance?.sendMessage(
            BTMessage.mediaMetadata(title, artist, album, duration)
        )
    }

    private fun sendPlaybackState(state: PlaybackState?) {
        if (!Prefs.getBoolean(Prefs.KEY_MEDIA_SYNC, true)) return
        if (Prefs.getRole() != Prefs.ROLE_HOST) return

        val playbackState = state?.state ?: PlaybackState.STATE_NONE
        val position = state?.position ?: 0L
        val speed = state?.playbackSpeed ?: 1f

        CoreService.instance?.sendMessage(
            BTMessage.mediaPlaybackState(playbackState, position, speed)
        )
    }

    fun handleControlCommand(type: Int, position: Long? = null, volume: Int? = null) {
        val controller = activeController ?: run {
            Log.w(TAG, "No active controller for command: $type")
            return
        }

        try {
            when (type) {
                cn.org.sdcd.suiyin.protocol.MsgType.MEDIA_CMD_PLAY_PAUSE -> {
                    val state = controller.playbackState?.state
                    if (state == PlaybackState.STATE_PLAYING) {
                        controller.transportControls.pause()
                    } else {
                        controller.transportControls.play()
                    }
                }
                cn.org.sdcd.suiyin.protocol.MsgType.MEDIA_CMD_NEXT -> {
                    controller.transportControls.skipToNext()
                }
                cn.org.sdcd.suiyin.protocol.MsgType.MEDIA_CMD_PREV -> {
                    controller.transportControls.skipToPrevious()
                }
                cn.org.sdcd.suiyin.protocol.MsgType.MEDIA_CMD_SEEK -> {
                    position?.let { controller.transportControls.seekTo(it) }
                }
                cn.org.sdcd.suiyin.protocol.MsgType.MEDIA_CMD_VOLUME -> {
                    if (volume != null) {
                        val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.setStreamVolume(
                            android.media.AudioManager.STREAM_MUSIC,
                            volume,
                            0
                        )
                    }
                }
            }
            Log.d(TAG, "Handled media command: $type")
        } catch (e: Exception) {
            Log.e(TAG, "Handle control command failed", e)
        }
    }

    private fun cleanup() {
        try {
            activeController?.unregisterCallback(metadataCallback)
            activeController = null
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (_: Exception) {}
    }
}
