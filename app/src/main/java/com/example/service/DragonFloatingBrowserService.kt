package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.MainActivity
import com.example.R
import com.example.data.browser.BrowserStateRepository
import com.example.data.repository.KernelRepository
import com.example.ui.screens.FloatingDragonBrowserWindow
import com.example.ui.theme.DragonKernelTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * True system-level Floating Overlay Service for Dragon Browser.
 *
 * Hosts a manually resizable, draggable, minimizable Jetpack Compose + WebView browser window
 * via [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] so the user can browse the web
 * simultaneously while using Dragon Download Manager, Dragon Archiver, or any other Android app.
 */
class DragonFloatingBrowserService : Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var overlayRoot: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val isMinimizedState = mutableStateOf(false)
    private val windowWidthDpState = mutableIntStateOf(350)
    private val windowHeightDpState = mutableIntStateOf(490)
    private val inputFocusEnabledState = mutableStateOf(false)
    private val initialUrlState = mutableStateOf(BrowserStateRepository.DEFAULT_HOME_URL)

    private var savedExpandedX: Int = 20
    private var savedExpandedY: Int = 100
    private var savedExpandedWidthPx: Int = 940
    private var savedExpandedHeightPx: Int = 1320
    private var currentActiveUrl: String = BrowserStateRepository.DEFAULT_HOME_URL

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DragonFloatingBrowser:MediaWakeLock"
            )?.apply { setReferenceCounted(false) }
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "DragonFloatingBrowser:MediaWifiLock")
                ?.apply { setReferenceCounted(false) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FLOATING -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPAND_FULLSCREEN -> {
                openFullScreenBrowser(currentActiveUrl)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val reqUrl = intent?.getStringExtra(EXTRA_INITIAL_URL)?.trim()
        if (!reqUrl.isNullOrBlank()) {
            initialUrlState.value = reqUrl
            currentActiveUrl = reqUrl
        }

        startForegroundSafe()

        if (overlayRoot == null) {
            attachFloatingOverlay()
        } else if (isMinimizedState.value) {
            setMinimized(false)
        }
        _isFloatingActiveFlow.value = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        _isFloatingActiveFlow.value = false
        keepAliveResumeHandler = null
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        detachFloatingOverlay()
        runCatching {
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            }
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
        store.clear()
        super.onDestroy()
    }

    private fun startForegroundSafe() {
        ensureNotificationChannel()
        val notification = buildOverlayNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_FLOATING_BROWSER,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID_FLOATING_BROWSER, notification)
            }
        }.onFailure {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID_FLOATING_BROWSER,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                } else {
                    startForeground(NOTIFICATION_ID_FLOATING_BROWSER, notification)
                }
            }
        }
        runCatching {
            if (wakeLock?.isHeld != true) wakeLock?.acquire(12L * 3600_000L)
        }
        runCatching {
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID_FLOATING_BROWSER) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_FLOATING_BROWSER,
                    "Dragon Browser Floating Window",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the resizable floating Dragon Browser active over other apps"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildOverlayNotification(): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val expandIntent = Intent(this, DragonFloatingBrowserService::class.java).apply {
            action = ACTION_EXPAND_FULLSCREEN
        }
        val expandPi = PendingIntent.getService(this, 7820, expandIntent, piFlags)

        val closeIntent = Intent(this, DragonFloatingBrowserService::class.java).apply {
            action = ACTION_STOP_FLOATING
        }
        val closePi = PendingIntent.getService(this, 7821, closeIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID_FLOATING_BROWSER)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle("Dragon Browser Floating")
            .setContentText("Resizable floating browser active over apps")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(expandPi)
            .addAction(0, "Full Screen", expandPi)
            .addAction(0, "Close", closePi)
            .build()
    }

    private fun screenMetrics(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels.coerceAtLeast(720) to dm.heightPixels.coerceAtLeast(1280)
    }

    private fun dpToPx(dp: Float): Int = (dp * resources.displayMetrics.density).roundToInt()

    private fun pxToDp(px: Int): Int = (px / resources.displayMetrics.density).roundToInt()

    private fun attachFloatingOverlay() {
        val (screenW, screenH) = screenMetrics()
        val initW = (screenW * 0.86f).roundToInt().coerceIn(dpToPx(270f), screenW - dpToPx(16f))
        val initH = (screenH * 0.58f).roundToInt().coerceIn(dpToPx(300f), screenH - dpToPx(88f))
        val initX = ((screenW - initW) / 2).coerceAtLeast(dpToPx(8f))
        val initY = ((screenH - initH) / 3).coerceAtLeast(dpToPx(40f))

        savedExpandedX = initX
        savedExpandedY = initY
        savedExpandedWidthPx = initW
        savedExpandedHeightPx = initH
        windowWidthDpState.intValue = pxToDp(initW)
        windowHeightDpState.intValue = pxToDp(initH)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            initW,
            initH,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        layoutParams = params

        val repository = KernelRepository.getInstance(applicationContext)
        val themedContext = ContextThemeWrapper(this, R.style.Theme_MyApplication)

        val rootContainer = object : FrameLayout(themedContext) {
            override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
                // Automatically release keyboard focus when user interacts with another app outside this window,
                // while proactively keeping floating browser video/audio playback running.
                if (ev?.action == MotionEvent.ACTION_OUTSIDE) {
                    if (inputFocusEnabledState.value) {
                        setFocusableForInput(false)
                    }
                    keepFloatingMediaAlive()
                }
                return super.dispatchTouchEvent(ev)
            }
        }

        val composeView = ComposeView(themedContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@DragonFloatingBrowserService)
            setViewTreeViewModelStoreOwner(this@DragonFloatingBrowserService)
            setViewTreeSavedStateRegistryOwner(this@DragonFloatingBrowserService)

            setContent {
                val accentIndex by repository.accentColorIndexFlow.collectAsState()
                val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
                val themeConfig by repository.themeConfigFlow.collectAsState()

                DragonKernelTheme(
                    accentIndex = accentIndex,
                    isAmoledBlack = isAmoledBlack,
                    themeConfig = themeConfig
                ) {
                    FloatingDragonBrowserWindow(
                        repository = repository,
                        themedContext = themedContext,
                        initialUrl = initialUrlState.value,
                        isMinimized = isMinimizedState.value,
                        isInputFocusEnabled = inputFocusEnabledState.value,
                        windowWidthDp = windowWidthDpState.intValue,
                        windowHeightDp = windowHeightDpState.intValue,
                        onActiveUrlChanged = { url -> currentActiveUrl = url },
                        onDragWindow = { dx, dy -> moveWindowBy(dx, dy) },
                        onResizeWindow = { dw, dh, anchorLeft -> resizeWindowBy(dw, dh, anchorLeft) },
                        onApplySizePreset = { preset -> applySizePreset(preset) },
                        onToggleMinimize = { setMinimized(!isMinimizedState.value) },
                        onInputFocusNeeded = { needed -> setFocusableForInput(needed) },
                        onExpandToFullScreen = { activeUrl ->
                            openFullScreenBrowser(activeUrl)
                            stopSelf()
                        },
                        onCloseFloating = {
                            stopSelf()
                        }
                    )
                }
            }
        }

        rootContainer.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        rootContainer.setViewTreeLifecycleOwner(this)
        rootContainer.setViewTreeViewModelStoreOwner(this)
        rootContainer.setViewTreeSavedStateRegistryOwner(this)

        overlayRoot = rootContainer
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        runCatching {
            windowManager.addView(rootContainer, params)
        }.onFailure {
            overlayRoot = null
            stopSelf()
        }
    }

    private fun detachFloatingOverlay() {
        val view = overlayRoot ?: return
        overlayRoot = null
        runCatching {
            windowManager.removeViewImmediate(view)
        }
    }

    private fun updateLayoutSafely() {
        val view = overlayRoot ?: return
        val params = layoutParams ?: return
        runCatching {
            windowManager.updateViewLayout(view, params)
        }
    }

    private fun moveWindowBy(dxPx: Float, dyPx: Float) {
        val params = layoutParams ?: return
        val (screenW, screenH) = screenMetrics()
        val marginKeepVisible = dpToPx(48f)
        val minX = -(params.width - marginKeepVisible)
        val maxX = screenW - marginKeepVisible
        val minY = dpToPx(24f)
        val maxY = screenH - marginKeepVisible

        params.x = (params.x + dxPx.roundToInt()).coerceIn(minX, maxX)
        params.y = (params.y + dyPx.roundToInt()).coerceIn(minY, maxY)
        if (!isMinimizedState.value) {
            savedExpandedX = params.x
            savedExpandedY = params.y
        }
        updateLayoutSafely()
    }

    private fun resizeWindowBy(dwPx: Float, dhPx: Float, anchorLeft: Boolean) {
        if (isMinimizedState.value) return
        val params = layoutParams ?: return
        val (screenW, screenH) = screenMetrics()
        val minW = dpToPx(230f)
        val maxW = (screenW - dpToPx(12f)).coerceAtLeast(minW)
        val minH = dpToPx(200f)
        val maxH = (screenH - dpToPx(64f)).coerceAtLeast(minH)

        val oldW = params.width
        val oldH = params.height
        val oldX = params.x

        val deltaW = dwPx.roundToInt()
        val deltaH = dhPx.roundToInt()

        if (anchorLeft) {
            val newW = (params.width - deltaW).coerceIn(minW, maxW)
            val actualChange = params.width - newW
            params.width = newW
            params.x = (params.x + actualChange).coerceIn(-dpToPx(40f), screenW - dpToPx(80f))
        } else {
            params.width = (params.width + deltaW).coerceIn(minW, maxW)
        }
        params.height = (params.height + deltaH).coerceIn(minH, maxH)

        if (params.width == oldW && params.height == oldH && params.x == oldX) {
            return
        }

        savedExpandedX = params.x
        savedExpandedY = params.y
        savedExpandedWidthPx = params.width
        savedExpandedHeightPx = params.height
        windowWidthDpState.intValue = pxToDp(params.width)
        windowHeightDpState.intValue = pxToDp(params.height)
        updateLayoutSafely()
    }

    private fun applySizePreset(presetIndex: Int) {
        if (isMinimizedState.value) return
        val params = layoutParams ?: return
        val (screenW, screenH) = screenMetrics()
        val (wFrac, hFrac) = when (presetIndex % 3) {
            0 -> 0.72f to 0.44f // Compact
            1 -> 0.86f to 0.60f // Balanced
            else -> 0.96f to 0.80f // Large
        }
        val targetW = (screenW * wFrac).roundToInt().coerceIn(dpToPx(230f), screenW - dpToPx(12f))
        val targetH = (screenH * hFrac).roundToInt().coerceIn(dpToPx(200f), screenH - dpToPx(64f))
        params.width = targetW
        params.height = targetH
        params.x = ((screenW - targetW) / 2).coerceAtLeast(dpToPx(6f))
        params.y = params.y.coerceIn(dpToPx(28f), (screenH - targetH - dpToPx(24f)).coerceAtLeast(dpToPx(28f)))

        savedExpandedX = params.x
        savedExpandedY = params.y
        savedExpandedWidthPx = params.width
        savedExpandedHeightPx = params.height
        windowWidthDpState.intValue = pxToDp(targetW)
        windowHeightDpState.intValue = pxToDp(targetH)
        updateLayoutSafely()
    }

    private fun setMinimized(minimized: Boolean) {
        val params = layoutParams ?: return
        if (minimized == isMinimizedState.value) return
        val (screenW, screenH) = screenMetrics()
        if (minimized) {
            savedExpandedX = params.x
            savedExpandedY = params.y
            savedExpandedWidthPx = params.width
            savedExpandedHeightPx = params.height
            setFocusableForInput(false)
            val bubblePx = dpToPx(62f)
            params.width = bubblePx
            params.height = bubblePx
            params.x = dpToPx(12f)
            params.y = params.y.coerceIn(dpToPx(84f), (screenH - bubblePx - dpToPx(96f)).coerceAtLeast(dpToPx(84f)))
        } else {
            params.width = savedExpandedWidthPx.coerceIn(dpToPx(230f), screenW - dpToPx(12f))
            params.height = savedExpandedHeightPx.coerceIn(dpToPx(200f), screenH - dpToPx(64f))
            params.x = savedExpandedX.coerceIn(dpToPx(6f), (screenW - dpToPx(80f)).coerceAtLeast(dpToPx(6f)))
            params.y = savedExpandedY.coerceIn(dpToPx(28f), (screenH - dpToPx(100f)).coerceAtLeast(dpToPx(28f)))
            windowWidthDpState.intValue = pxToDp(params.width)
            windowHeightDpState.intValue = pxToDp(params.height)
        }
        isMinimizedState.value = minimized
        updateLayoutSafely()
    }

    private fun setFocusableForInput(focusable: Boolean) {
        val params = layoutParams ?: return
        if (inputFocusEnabledState.value == focusable) return
        inputFocusEnabledState.value = focusable
        params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        updateLayoutSafely()
    }

    private fun openFullScreenBrowser(activeUrl: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(DragonMediaPlaybackService.EXTRA_OPEN_BROWSER_TAB, true)
            if (!activeUrl.isNullOrBlank()) {
                putExtra(EXTRA_INITIAL_URL, activeUrl)
            }
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val CHANNEL_ID_FLOATING_BROWSER = "dragon_browser_floating_v1"
        const val NOTIFICATION_ID_FLOATING_BROWSER = 7820
        const val ACTION_START_FLOATING = "com.example.dragon.action.START_FLOATING_BROWSER"
        const val ACTION_STOP_FLOATING = "com.example.dragon.action.STOP_FLOATING_BROWSER"
        const val ACTION_EXPAND_FULLSCREEN = "com.example.dragon.action.EXPAND_FLOATING_BROWSER"
        const val EXTRA_INITIAL_URL = "extra_floating_browser_initial_url"

        private val _isFloatingActiveFlow = MutableStateFlow(false)
        val isFloatingActiveFlow: StateFlow<Boolean> = _isFloatingActiveFlow.asStateFlow()

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var keepAliveResumeHandler: (() -> Unit)? = null

        fun registerKeepAliveResumeHandler(handler: (() -> Unit)?) {
            keepAliveResumeHandler = handler
        }

        fun keepFloatingMediaAlive() {
            val trigger = Runnable {
                runCatching { keepAliveResumeHandler?.invoke() }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                trigger.run()
            } else {
                mainHandler.post(trigger)
            }
            mainHandler.postDelayed(trigger, 140L)
            mainHandler.postDelayed(trigger, 480L)
        }

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun openOverlayPermissionSettings(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            }
        }

        fun startFloating(context: Context, initialUrl: String? = null) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, DragonFloatingBrowserService::class.java).apply {
                action = ACTION_START_FLOATING
                if (!initialUrl.isNullOrBlank()) {
                    putExtra(EXTRA_INITIAL_URL, initialUrl)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
        }

        fun stopFloating(context: Context) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, DragonFloatingBrowserService::class.java).apply {
                action = ACTION_STOP_FLOATING
            }
            runCatching { appCtx.stopService(intent) }
        }
    }
}
