package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.browser.BrowserPipController
import com.example.data.browser.PipPlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground media playback service (`foregroundServiceType="mediaPlayback"`) that keeps browser
 * and Universal Stream Player audio/video playing continuously after the user minimizes the app
 * or turns off the device display, backed by a [PowerManager.PARTIAL_WAKE_LOCK],
 * [WifiManager.WifiLock], [AudioManager] focus handling, and lockscreen/notification [MediaSession].
 */
class DragonMediaPlaybackService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(applicationContext)

        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DragonBrowser:BackgroundMediaWakeLock"
            )?.apply { setReferenceCounted(false) }
        }

        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(lockMode, "DragonBrowser:BackgroundMediaWifiLock")
                ?.apply { setReferenceCounted(false) }
        }

        runCatching {
            mediaSession = MediaSession(this, "DragonMediaSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        BrowserPipController.dispatchTogglePlayPause()
                    }

                    override fun onPause() {
                        BrowserPipController.dispatchTogglePlayPause()
                    }

                    override fun onStop() {
                        BrowserPipController.dispatchStopPlayback()
                        stopAndReleaseSelf()
                    }
                })
                isActive = true
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_MEDIA_PLAY_PAUSE -> {
                BrowserPipController.dispatchTogglePlayPause()
            }
            ACTION_MEDIA_STOP -> {
                BrowserPipController.dispatchStopPlayback()
                stopAndReleaseSelf()
                return START_NOT_STICKY
            }
        }

        val state = BrowserPipController.stateFlow.value
        val notification = buildMediaNotification(this, state, mediaSession?.sessionToken)
        promoteToForeground(notification)

        if (!hasPlayableBackgroundMedia(state)) {
            stopAndReleaseSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        updateMediaSessionState(state)
        startObservingPlayback()
        return START_NOT_STICKY
    }

    private fun startObservingPlayback() {
        if (observerJob?.isActive == true) return
        observerJob = serviceScope.launch {
            BrowserPipController.stateFlow.collect { state ->
                if (!hasPlayableBackgroundMedia(state)) {
                    stopAndReleaseSelf()
                } else {
                    acquireLocks()
                    updateMediaSessionState(state)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(
                        NOTIFICATION_ID_MEDIA_PLAYBACK,
                        buildMediaNotification(this@DragonMediaPlaybackService, state, mediaSession?.sessionToken)
                    )
                }
            }
        }
    }

    private fun updateMediaSessionState(state: PipPlaybackState) {
        val session = mediaSession ?: return
        val isPlaying = state.shouldKeepBackgroundMediaAlive
        val title = resolveDisplayTitle(state)
        val subtitle = resolveDisplaySubtitle(state)

        runCatching {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                    .build()
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_STOP
                    )
                    .setState(
                        if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (isPlaying) 1.0f else 0.0f
                    )
                    .build()
            )
            session.isActive = true
        }
    }

    private fun promoteToForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_MEDIA_PLAYBACK,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID_MEDIA_PLAYBACK, notification)
            }
        } catch (_: Throwable) {
            runCatching { startForeground(NOTIFICATION_ID_MEDIA_PLAYBACK, notification) }
        }
    }

    /**
     * Holds CPU + Wi-Fi locks while WebView media plays in PiP / background.
     * Note: Chromium WebView manages Android AudioFocus internally; requesting a second
     * competing AUDIOFOCUS_GAIN here would steal focus from WebView and pause HTML5 playback.
     */
    private fun acquireLocks() {
        runCatching {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(12L * 3600_000L)
            }
        }
        runCatching {
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun stopAndReleaseSelf() {
        releaseLocks()
        runCatching { mediaSession?.isActive = false }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        observerJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null
        }
    }

    companion object {
        const val CHANNEL_ID_MEDIA_PLAYBACK = "dragon_media_playback_channel"
        const val NOTIFICATION_ID_MEDIA_PLAYBACK = 4049
        const val ACTION_MEDIA_START_OR_SYNC = "com.android.ddm.ACTION_MEDIA_START_OR_SYNC"
        const val ACTION_MEDIA_PLAY_PAUSE = "com.android.ddm.ACTION_MEDIA_PLAY_PAUSE"
        const val ACTION_MEDIA_STOP = "com.android.ddm.ACTION_MEDIA_STOP"

        @Volatile
        private var lastDispatchedActive = false

        fun hasPlayableBackgroundMedia(state: PipPlaybackState): Boolean {
            return state.shouldKeepBackgroundMediaAlive
        }

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (nm.getNotificationChannel(CHANNEL_ID_MEDIA_PLAYBACK) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID_MEDIA_PLAYBACK,
                        "Background Audio & Video Playback",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Keeps browser audio and video playback active when minimized or screen off"
                        setShowBadge(false)
                        setSound(null, null)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }

        fun resolveDisplayTitle(state: PipPlaybackState): String {
            return state.mediaTitle.ifBlank {
                if (state.isAudioPlaying && !state.isVideoPlaying) "Background Audio"
                else "Background Media Playback"
            }
        }

        fun resolveDisplaySubtitle(state: PipPlaybackState): String {
            val host = runCatching { Uri.parse(state.mediaUrl).host?.removePrefix("www.") }.getOrNull()
            return if (!host.isNullOrBlank()) {
                "$host • Playing in background"
            } else {
                "Screen-off & background playback active"
            }
        }

        const val EXTRA_OPEN_BROWSER_TAB = "com.android.ddm.EXTRA_OPEN_BROWSER_TAB"

        fun buildMediaNotification(
            context: Context,
            state: PipPlaybackState,
            sessionToken: MediaSession.Token? = null
        ): Notification {
            ensureNotificationChannel(context)
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_OPEN_BROWSER_TAB, true)
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                4050,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val toggleIntent = Intent(context, DragonMediaPlaybackService::class.java).apply {
                action = ACTION_MEDIA_PLAY_PAUSE
            }
            val togglePendingIntent = PendingIntent.getService(
                context,
                4051,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stopIntent = Intent(context, DragonMediaPlaybackService::class.java).apply {
                action = ACTION_MEDIA_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                4052,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val isPlaying = state.shouldKeepBackgroundMediaAlive
            val toggleAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Notification.Action.Builder(
                    Icon.createWithResource(
                        context,
                        if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    ),
                    if (isPlaying) "Pause" else "Play",
                    togglePendingIntent
                ).build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Action.Builder(
                    if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (isPlaying) "Pause" else "Play",
                    togglePendingIntent
                ).build()
            }

            val stopAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stopPendingIntent
                ).build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopPendingIntent
                ).build()
            }

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID_MEDIA_PLAYBACK)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            builder
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(resolveDisplayTitle(state))
                .setContentText(resolveDisplaySubtitle(state))
                .setContentIntent(contentPendingIntent)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(toggleAction)
                .addAction(stopAction)

            val style = Notification.MediaStyle()
                .setShowActionsInCompactView(0, 1)
            if (sessionToken != null) {
                style.setMediaSession(sessionToken)
            }
            builder.style = style

            return builder.build()
        }

        fun syncWithPlaybackState(context: Context, state: PipPlaybackState) {
            val active = hasPlayableBackgroundMedia(state)
            if (active) {
                lastDispatchedActive = true
                val intent = Intent(context.applicationContext, DragonMediaPlaybackService::class.java).apply {
                    action = ACTION_MEDIA_START_OR_SYNC
                }
                runCatching {
                    ContextCompat.startForegroundService(context.applicationContext, intent)
                }.onFailure {
                    runCatching { context.applicationContext.startService(intent) }
                }
            } else if (lastDispatchedActive) {
                lastDispatchedActive = false
                val intent = Intent(context.applicationContext, DragonMediaPlaybackService::class.java).apply {
                    action = ACTION_MEDIA_STOP
                }
                runCatching { context.applicationContext.startService(intent) }
            }
        }
    }
}
