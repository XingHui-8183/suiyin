package cn.org.sdcd.suiyin.media

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import cn.org.sdcd.suiyin.App
import cn.org.sdcd.suiyin.protocol.BTMessage
import cn.org.sdcd.suiyin.protocol.MsgType
import cn.org.sdcd.suiyin.service.CoreService

class MediaSessionManager(private val context: Context) {

    companion object {
        private const val TAG = "MediaSessionManager"
        private const val CHANNEL_ID = "media_control"
        private const val NOTIFICATION_ID = 2001
        /** 进度条自动刷新间隔(ms)。仅在 STATE_PLAYING 时启动定时器 */
        private const val PROGRESS_TICK_MS = 500L
        const val ACTION_PLAY_PAUSE = "cn.org.sdcd.suiyin.media.PLAY_PAUSE"
        const val ACTION_NEXT = "cn.org.sdcd.suiyin.media.NEXT"
        const val ACTION_PREV = "cn.org.sdcd.suiyin.media.PREV"
    }

    /** UI 监听器:元数据或播放状态变化时回调(主线程) */
    interface Listener {
        fun onMediaUpdated(info: MediaInfo)
    }

    /** 当前媒体的快照,供 UI 显示 */
    data class MediaInfo(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val state: Int,
        val position: Long,
        val speed: Float,
        val volume: Int,
        val maxVolume: Int
    ) {
        val isPlaying: Boolean get() = state == PlaybackState.STATE_PLAYING
        val progress: Int
            get() = if (duration <= 0) 0 else ((position.toFloat() / duration) * 100).toInt().coerceIn(0, 100)
    }

    private var mediaSession: MediaSession? = null
    private var audioManager: AudioManager? = null
    private var volumeReceiver: VolumeReceiver? = null

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentDuration = 0L
    private var currentState = PlaybackState.STATE_NONE
    private var currentPosition = 0L
    private var currentSpeed = 1f
    private var currentVolume = 0
    private var currentMaxVolume = 15
    private var isActive = false

    /** 上一次刷新 position 时对应的 elapsedRealtime,用于按 speed 推算当前进度 */
    private var lastTickElapsed = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressTicker = object : Runnable {
        override fun run() {
            if (currentState != PlaybackState.STATE_PLAYING) return
            val now = SystemClock.elapsedRealtime()
            if (lastTickElapsed > 0L) {
                val deltaMs = (now - lastTickElapsed) * currentSpeed
                currentPosition = (currentPosition + deltaMs.toLong())
                    .coerceAtLeast(0L)
                    .coerceAtMost(currentDuration.coerceAtLeast(0L))
            }
            lastTickElapsed = now
            notifyListeners()
            mainHandler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private val listeners = mutableListOf<Listener>()

    fun start() {
        try {
            mediaSession = MediaSession(context, "BTRemoteMediaSession").apply {
                setCallback(MediaSessionCallback())
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                isActive = true
            }
            isActive = true

            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            currentMaxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0

            volumeReceiver = VolumeReceiver()
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            context.registerReceiver(volumeReceiver, filter)

            updatePlaybackState()
            notifyListeners()
            Log.d(TAG, "MediaSession started")
        } catch (e: Exception) {
            Log.e(TAG, "Start MediaSession failed", e)
        }
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onMediaUpdated(getCurrentInfo())
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun getCurrentInfo(): MediaInfo = MediaInfo(
        title = currentTitle,
        artist = currentArtist,
        album = currentAlbum,
        duration = currentDuration,
        state = currentState,
        position = currentPosition,
        speed = currentSpeed,
        volume = currentVolume,
        maxVolume = currentMaxVolume
    )

    private fun notifyListeners() {
        val info = getCurrentInfo()
        listeners.forEach { it.onMediaUpdated(info) }
    }

    fun stop() {
        try {
            mainHandler.removeCallbacks(progressTicker)
            isActive = false
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
            volumeReceiver?.let {
                try {
                    context.unregisterReceiver(it)
                } catch (_: Exception) {}
            }
            volumeReceiver = null
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            Log.d(TAG, "MediaSession stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Stop MediaSession failed", e)
        }
    }

    fun updateMetadata(title: String, artist: String, album: String, duration: Long) {
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentDuration = duration

        try {
            val metadata = MediaMetadata.Builder().apply {
                putString(MediaMetadata.METADATA_KEY_TITLE, title)
                putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
            }.build()

            mediaSession?.setMetadata(metadata)
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Update metadata failed", e)
        }
        notifyListeners()
    }

    fun updatePlaybackState(state: Int, position: Long = 0L, speed: Float = 1f) {
        currentState = state
        currentPosition = position
        currentSpeed = if (speed > 0f) speed else 1f
        // 重置 tick 基准时间,下次 tick 从当前点开始累计
        lastTickElapsed = SystemClock.elapsedRealtime()
        updateProgressTicker()
        updatePlaybackState()
        notifyListeners()
    }

    /** 仅在 STATE_PLAYING 时启动定时器,其它状态一律停止 */
    private fun updateProgressTicker() {
        mainHandler.removeCallbacks(progressTicker)
        if (currentState == PlaybackState.STATE_PLAYING) {
            lastTickElapsed = SystemClock.elapsedRealtime()
            mainHandler.postDelayed(progressTicker, PROGRESS_TICK_MS)
        }
    }

    private fun updatePlaybackState() {
        try {
            val state = PlaybackState.Builder().apply {
                setState(currentState, currentPosition, currentSpeed)
                setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_SET_RATING
                )
            }.build()

            mediaSession?.setPlaybackState(state)
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Update playback state failed", e)
        }
    }

    private fun updateNotification() {
        try {
            if (!isActive || mediaSession == null) return

            val isPlaying = currentState == PlaybackState.STATE_PLAYING

            val playPauseIntent = Intent(ACTION_PLAY_PAUSE).let {
                PendingIntent.getBroadcast(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
            }
            val nextIntent = Intent(ACTION_NEXT).let {
                PendingIntent.getBroadcast(context, 1, it, PendingIntent.FLAG_IMMUTABLE)
            }
            val prevIntent = Intent(ACTION_PREV).let {
                PendingIntent.getBroadcast(context, 2, it, PendingIntent.FLAG_IMMUTABLE)
            }

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, App.CHANNEL_MEDIA)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(currentTitle.ifEmpty { "未播放" })
                    .setContentText(currentArtist)
                    .setSubText(currentAlbum)
                    .setOngoing(isPlaying)
                    .setOnlyAlertOnce(true)
                    .addAction(
                        android.R.drawable.ic_media_previous,
                        "上一首",
                        prevIntent
                    )
                    .addAction(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        if (isPlaying) "暂停" else "播放",
                        playPauseIntent
                    )
                    .addAction(
                        android.R.drawable.ic_media_next,
                        "下一首",
                        nextIntent
                    )
                    .setStyle(
                        Notification.MediaStyle()
                            .setMediaSession(mediaSession?.sessionToken)
                            .setShowActionsInCompactView(0, 1, 2)
                    )
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(currentTitle.ifEmpty { "未播放" })
                    .setContentText(currentArtist)
                    .setSubText(currentAlbum)
                    .setOngoing(isPlaying)
                    .setOnlyAlertOnce(true)
                    .addAction(
                        android.R.drawable.ic_media_previous,
                        "上一首",
                        prevIntent
                    )
                    .addAction(
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        if (isPlaying) "暂停" else "播放",
                        playPauseIntent
                    )
                    .addAction(
                        android.R.drawable.ic_media_next,
                        "下一首",
                        nextIntent
                    )
                    .setStyle(
                        Notification.MediaStyle()
                            .setMediaSession(mediaSession?.sessionToken)
                            .setShowActionsInCompactView(0, 1, 2)
                    )
                    .build()
            }

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Update notification failed", e)
        }
    }

    inner class MediaSessionCallback : MediaSession.Callback() {
        override fun onPlay() {
            super.onPlay()
            sendCommand(MsgType.MEDIA_CMD_PLAY_PAUSE)
        }

        override fun onPause() {
            super.onPause()
            sendCommand(MsgType.MEDIA_CMD_PLAY_PAUSE)
        }

        override fun onSkipToNext() {
            super.onSkipToNext()
            sendCommand(MsgType.MEDIA_CMD_NEXT)
        }

        override fun onSkipToPrevious() {
            super.onSkipToPrevious()
            sendCommand(MsgType.MEDIA_CMD_PREV)
        }

        override fun onSeekTo(pos: Long) {
            super.onSeekTo(pos)
            CoreService.instance?.sendMessage(BTMessage.mediaCmdSeek(pos))
        }
    }

    inner class VolumeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val streamType = intent.getIntExtra(
                    "android.media.EXTRA_VOLUME_STREAM_TYPE", -1
                )
                if (streamType == AudioManager.STREAM_MUSIC) {
                    val volume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
                    val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
                    currentVolume = volume
                    currentMaxVolume = maxVolume
                    CoreService.instance?.sendMessage(
                        BTMessage.mediaCmdVolume(volume)
                    )
                    notifyListeners()
                }
            }
        }
    }

    private fun sendCommand(type: Int) {
        CoreService.instance?.let {
            when (type) {
                MsgType.MEDIA_CMD_PLAY_PAUSE -> it.sendMessage(BTMessage.mediaCmdPlayPause())
                MsgType.MEDIA_CMD_NEXT -> it.sendMessage(BTMessage.mediaCmdNext())
                MsgType.MEDIA_CMD_PREV -> it.sendMessage(BTMessage.mediaCmdPrev())
                else -> {}
            }
        }
    }

    fun getSessionToken(): MediaSession.Token? = mediaSession?.sessionToken
}
