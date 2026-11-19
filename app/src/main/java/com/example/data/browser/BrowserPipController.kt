package com.example.data.browser

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Rational
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class PipPlaybackState(
    val isBrowserOpen: Boolean = false,
    val activeTabId: String = "",
    val floatingPipTabId: String? = null,
    val isVideoPlaying: Boolean = false,
    val isAudioPlaying: Boolean = false,
    val isFullscreenVideo: Boolean = false,
    val isStreamPlayerOpen: Boolean = false,
    val mediaTitle: String = "",
    val mediaUrl: String = "",
    val videoWidth: Int = 16,
    val videoHeight: Int = 9,
    val videoLeftFrac: Float = 0f,
    val videoTopFrac: Float = 0f,
    val videoRightFrac: Float = 1f,
    val videoBottomFrac: Float = 1f,
    val containerRect: Rect? = null
) {
    val shouldEnterPipOnMinimize: Boolean
        get() = isBrowserOpen && (
            isVideoPlaying ||
                isFullscreenVideo ||
                isStreamPlayerOpen ||
                !floatingPipTabId.isNullOrBlank()
            )

    /**
     * True whenever audio OR video is actively playing in the browser, fullscreen player,
     * floating PiP tab, or Universal Stream Player — keeps foreground media service,
     * CPU/Wi-Fi wake locks, and WebView playback alive even after minimizing or display off.
     */
    val shouldKeepBackgroundMediaAlive: Boolean
        get() = shouldEnterPipOnMinimize || (isBrowserOpen && isAudioPlaying)
}

/**
 * Coordinates Android Picture-in-Picture (PiP) and background/screen-off media playback
 * for Browser WebViews, fullscreen HTML5 players, in-app floating PiP windows
 * (Document PiP & Video PiP), and the built-in HLS/DASH Universal Stream Player.
 */
object BrowserPipController {

    const val ACTION_PIP_TOGGLE_PLAY = "com.android.ddm.ACTION_PIP_TOGGLE_PLAY"

    private val _stateFlow = MutableStateFlow(PipPlaybackState())
    val stateFlow: StateFlow<PipPlaybackState> = _stateFlow.asStateFlow()

    private val _isInPipModeFlow = MutableStateFlow(false)
    val isInPipModeFlow: StateFlow<Boolean> = _isInPipModeFlow.asStateFlow()

    private val _isBackgroundMinimizedFlow = MutableStateFlow(false)
    val isBackgroundMinimizedFlow: StateFlow<Boolean> = _isBackgroundMinimizedFlow.asStateFlow()

    private val _isActivityResumedFlow = MutableStateFlow(true)
    val isActivityResumedFlow: StateFlow<Boolean> = _isActivityResumedFlow.asStateFlow()

    @Volatile
    var isExplicitBrowserClose: Boolean = false

    private val _togglePlayEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val togglePlayEvents: SharedFlow<Unit> = _togglePlayEvents.asSharedFlow()

    private val _stopPlaybackEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopPlaybackEvents: SharedFlow<Unit> = _stopPlaybackEvents.asSharedFlow()

    private val _pipPermissionMissingEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val pipPermissionMissingEvents: SharedFlow<Unit> = _pipPermissionMissingEvents.asSharedFlow()

    fun updateBrowserOpen(isOpen: Boolean) {
        if (isOpen) {
            isExplicitBrowserClose = false
        }
        _stateFlow.value = if (isOpen) {
            _stateFlow.value.copy(isBrowserOpen = true)
        } else {
            _isBackgroundMinimizedFlow.value = false
            PipPlaybackState(isBrowserOpen = false)
        }
    }

    fun setBackgroundMinimized(minimized: Boolean) {
        _isBackgroundMinimizedFlow.value = minimized
    }

    fun onActivityResumedChanged(resumed: Boolean) {
        _isActivityResumedFlow.value = resumed
        if (resumed) {
            _isBackgroundMinimizedFlow.value = false
        }
    }

    fun updateActiveTab(tabId: String) {
        val cur = _stateFlow.value
        if (cur.activeTabId != tabId) {
            val keepPlayback = !cur.floatingPipTabId.isNullOrBlank() || cur.isAudioPlaying || cur.isVideoPlaying
            _stateFlow.value = cur.copy(
                activeTabId = tabId,
                isVideoPlaying = if (keepPlayback) cur.isVideoPlaying else false,
                isAudioPlaying = if (keepPlayback) cur.isAudioPlaying else false
            )
        }
    }

    fun updateFloatingPipTab(tabId: String?) {
        val normalized = tabId?.takeIf { it.isNotBlank() }
        val cur = _stateFlow.value
        if (cur.floatingPipTabId != normalized) {
            _stateFlow.value = cur.copy(floatingPipTabId = normalized)
        }
    }

    fun updateMediaPlaybackState(
        tabId: String,
        isVideoPlaying: Boolean,
        isAudioPlaying: Boolean,
        mediaTitle: String = "",
        mediaUrl: String = "",
        videoWidth: Int = 16,
        videoHeight: Int = 9,
        leftFrac: Float = 0f,
        topFrac: Float = 0f,
        rightFrac: Float = 1f,
        bottomFrac: Float = 1f
    ) {
        val cur = _stateFlow.value
        val isTargetActive = cur.activeTabId.isEmpty() || cur.activeTabId == tabId
        val isTargetFloating = !cur.floatingPipTabId.isNullOrBlank() && cur.floatingPipTabId == tabId
        if (!isTargetActive && !isTargetFloating) return
        val safeW = if (videoWidth > 0) videoWidth else cur.videoWidth.coerceAtLeast(16)
        val safeH = if (videoHeight > 0) videoHeight else cur.videoHeight.coerceAtLeast(9)
        fun q(v: Float): Float = (kotlin.math.round(v.coerceIn(0f, 1f) * 50f) / 50f)
        val next = cur.copy(
            activeTabId = cur.activeTabId.ifEmpty { tabId },
            isVideoPlaying = if (isTargetActive) isVideoPlaying else cur.isVideoPlaying,
            isAudioPlaying = if (isTargetActive) isAudioPlaying else cur.isAudioPlaying,
            mediaTitle = mediaTitle.trim().takeIf { it.isNotBlank() } ?: cur.mediaTitle,
            mediaUrl = mediaUrl.trim().takeIf { it.isNotBlank() } ?: cur.mediaUrl,
            videoWidth = safeW,
            videoHeight = safeH,
            videoLeftFrac = q(leftFrac),
            videoTopFrac = q(topFrac),
            videoRightFrac = q(rightFrac),
            videoBottomFrac = q(bottomFrac)
        )
        if (next != cur) {
            _stateFlow.value = next
        }
    }

    fun updateWebVideoState(
        tabId: String,
        isPlaying: Boolean,
        videoWidth: Int,
        videoHeight: Int,
        leftFrac: Float = 0f,
        topFrac: Float = 0f,
        rightFrac: Float = 1f,
        bottomFrac: Float = 1f
    ) {
        updateMediaPlaybackState(
            tabId = tabId,
            isVideoPlaying = isPlaying,
            isAudioPlaying = false,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            leftFrac = leftFrac,
            topFrac = topFrac,
            rightFrac = rightFrac,
            bottomFrac = bottomFrac
        )
    }

    fun updateFullscreenVideo(isFullscreen: Boolean) {
        val cur = _stateFlow.value
        _stateFlow.value = cur.copy(
            isFullscreenVideo = isFullscreen
        )
    }

    fun updateStreamPlayerOpen(isOpen: Boolean, streamTitle: String = "", streamUrl: String = "") {
        val cur = _stateFlow.value
        _stateFlow.value = cur.copy(
            isStreamPlayerOpen = isOpen,
            mediaTitle = if (isOpen && streamTitle.isNotBlank()) streamTitle.trim() else cur.mediaTitle,
            mediaUrl = if (isOpen && streamUrl.isNotBlank()) streamUrl.trim() else cur.mediaUrl
        )
    }

    fun updateContainerRect(rect: Rect?) {
        val cur = _stateFlow.value
        if (cur.containerRect != rect) {
            _stateFlow.value = cur.copy(containerRect = rect)
        }
    }

    fun onPipModeChanged(inPip: Boolean) {
        _isInPipModeFlow.value = inPip
        if (inPip) {
            _isBackgroundMinimizedFlow.value = false
        }
    }

    fun dispatchTogglePlayPause() {
        if (!BackgroundMediaWebViewStore.dispatchTogglePlayOnRetained()) {
            _togglePlayEvents.tryEmit(Unit)
        }
    }

    fun dispatchStopPlayback() {
        _isBackgroundMinimizedFlow.value = false
        val hadRetained = BackgroundMediaWebViewStore.hasRetainedWebViews()
        BackgroundMediaWebViewStore.stopAndReleaseAll()
        val cur = _stateFlow.value
        _stateFlow.value = cur.copy(
            isBrowserOpen = if (hadRetained) false else cur.isBrowserOpen,
            isVideoPlaying = false,
            isAudioPlaying = false,
            isFullscreenVideo = false
        )
        _stopPlaybackEvents.tryEmit(Unit)
    }

    fun reset() {
        BackgroundMediaWebViewStore.stopAndReleaseAll()
        _stateFlow.value = PipPlaybackState()
        _isInPipModeFlow.value = false
        _isBackgroundMinimizedFlow.value = false
    }

    /**
     * Clamps arbitrary video dimensions strictly within Android's valid PiP aspect ratio bounds
     * ([1:2.39 .. 2.39:1]) so PictureInPictureParams.Builder.setAspectRatio never throws.
     */
    fun computeSafePipAspectRatio(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val ratio = width.toDouble() / height.toDouble()
        val minRatio = 1.0 / 2.35
        val maxRatio = 2.35
        return when {
            ratio > maxRatio -> 235 to 100
            ratio < minRatio -> 100 to 235
            else -> {
                val normW = (ratio * 1000.0).roundToInt().coerceIn(426, 2350)
                normW to 1000
            }
        }
    }

    fun computeSourceRectHint(state: PipPlaybackState): Rect? {
        val base = state.containerRect ?: return null
        if (base.isEmpty) return null
        if (state.isFullscreenVideo || state.isStreamPlayerOpen) return base
        val w = base.width()
        val h = base.height()
        if (w <= 0 || h <= 0) return base
        val l = state.videoLeftFrac
        val t = state.videoTopFrac
        val r = state.videoRightFrac
        val b = state.videoBottomFrac
        if (r - l < 0.08f || b - t < 0.08f) return base
        val left = (base.left + w * l).roundToInt().coerceIn(base.left, base.right - 1)
        val top = (base.top + h * t).roundToInt().coerceIn(base.top, base.bottom - 1)
        val right = (base.left + w * r).roundToInt().coerceIn(left + 1, base.right)
        val bottom = (base.top + h * b).roundToInt().coerceIn(top + 1, base.bottom)
        return Rect(left, top, right, bottom)
    }

    fun isPipSupported(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            runCatching {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            }.getOrDefault(false)
    }

    fun hasPipPermission(context: Context): Boolean {
        if (!isPipSupported(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return@runCatching true
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        }.getOrDefault(true)
    }

    fun openPipSettings(context: Context): Boolean {
        val packageUri = Uri.parse("package:${context.packageName}")
        val pipIntent = Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", packageUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openedPip = runCatching {
            context.startActivity(pipIntent)
            true
        }.getOrDefault(false)
        if (openedPip) return true

        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(detailsIntent)
            true
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun buildPictureInPictureParams(
        context: Context,
        state: PipPlaybackState = _stateFlow.value
    ): PictureInPictureParams {
        val (rw, rh) = computeSafePipAspectRatio(state.videoWidth, state.videoHeight)
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(rw, rh))

        val sourceRect = computeSourceRectHint(state)
        if (state.shouldEnterPipOnMinimize && sourceRect != null && !sourceRect.isEmpty) {
            builder.setSourceRectHint(sourceRect)
        }

        val toggleIntent = Intent(ACTION_PIP_TOGGLE_PLAY).apply {
            setPackage(context.packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            4041,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isPlayingOrActive = state.isVideoPlaying || state.isFullscreenVideo || state.isStreamPlayerOpen
        val iconRes = if (isPlayingOrActive) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val label = if (isPlayingOrActive) "Pause" else "Play"
        val action = RemoteAction(
            Icon.createWithResource(context, iconRes),
            label,
            label,
            pendingIntent
        )
        builder.setActions(listOf(action))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(state.shouldEnterPipOnMinimize)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    fun syncActivityPipParams(activity: Activity, state: PipPlaybackState = _stateFlow.value) {
        if (!isPipSupported(activity)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                activity.setPictureInPictureParams(buildPictureInPictureParams(activity, state))
            }
        }
    }

    fun enterPipFromWebRequest(
        activity: Activity,
        tabId: String,
        videoWidth: Int,
        videoHeight: Int,
        leftFrac: Float = 0f,
        topFrac: Float = 0f,
        rightFrac: Float = 1f,
        bottomFrac: Float = 1f
    ): Boolean {
        updateBrowserOpen(true)
        updateWebVideoState(
            tabId = tabId,
            isPlaying = true,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            leftFrac = leftFrac,
            topFrac = topFrac,
            rightFrac = rightFrac,
            bottomFrac = bottomFrac
        )
        return enterPipNow(activity)
    }

    fun enterPipNow(activity: Activity): Boolean {
        if (!isPipSupported(activity)) return false
        if (!hasPipPermission(activity)) {
            _pipPermissionMissingEvents.tryEmit(Unit)
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (activity.isInPictureInPictureMode) return true
            val currentState = _stateFlow.value
            syncActivityPipParams(activity, currentState)
            val entered = runCatching {
                val params = buildPictureInPictureParams(activity, currentState)
                activity.enterPictureInPictureMode(params)
            }.getOrDefault(false)
            if (!entered && !hasPipPermission(activity)) {
                _pipPermissionMissingEvents.tryEmit(Unit)
            }
            return entered
        }
        return false
    }
}
