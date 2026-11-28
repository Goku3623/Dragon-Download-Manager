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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
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
import com.example.data.filemanager.DragonFileManagerTaskEngine
import com.example.data.repository.KernelRepository
import com.example.ui.screens.FloatingDragonArchiverWindow
import com.example.ui.theme.DragonKernelTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.roundToInt

/**
 * True system-level Floating Overlay Service for Dragon Archiver.
 *
 * Hosts a manually resizable, draggable, minimizable Jetpack Compose file manager window
 * via [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] so the user can interact with
 * Dragon Archiver, Dragon Download Manager, and any other Android app simultaneously.
 */
class DragonFloatingArchiverService : Service(),
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
    private var overlayView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private val isMinimizedState = mutableStateOf(false)
    private val windowWidthDpState = mutableIntStateOf(340)
    private val windowHeightDpState = mutableIntStateOf(460)
    private val initialPathState = mutableStateOf<String?>(null)

    private var savedExpandedX: Int = 24
    private var savedExpandedY: Int = 120
    private var savedExpandedWidthPx: Int = 920
    private var savedExpandedHeightPx: Int = 1280
    private var currentInputFocusable: Boolean = false

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FLOATING -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPAND_FULLSCREEN -> {
                val path = DragonFileManagerTaskEngine.savedCurrentDir?.absolutePath
                    ?: initialPathState.value
                openFullScreenArchiver(path)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val reqPath = intent?.getStringExtra(EXTRA_INITIAL_PATH)
        if (!reqPath.isNullOrBlank()) {
            initialPathState.value = reqPath
            DragonFileManagerTaskEngine.savedCurrentDir = File(reqPath)
        }

        startForegroundSafe()

        if (overlayView == null) {
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
                    NOTIFICATION_ID_FLOATING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID_FLOATING, notification)
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID_FLOATING) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_FLOATING,
                    "Dragon Archiver Floating Window",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the resizable floating Dragon Archiver window active over other apps"
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

        val expandIntent = Intent(this, DragonFloatingArchiverService::class.java).apply {
            action = ACTION_EXPAND_FULLSCREEN
        }
        val expandPi = PendingIntent.getService(this, 7810, expandIntent, piFlags)

        val closeIntent = Intent(this, DragonFloatingArchiverService::class.java).apply {
            action = ACTION_STOP_FLOATING
        }
        val closePi = PendingIntent.getService(this, 7811, closeIntent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID_FLOATING)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setContentTitle("Dragon Archiver Floating")
            .setContentText("Resizable floating window active over apps")
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
        val initW = (screenW * 0.84f).roundToInt().coerceIn(dpToPx(270f), screenW - dpToPx(20f))
        val initH = (screenH * 0.56f).roundToInt().coerceIn(dpToPx(300f), screenH - dpToPx(96f))
        val initX = ((screenW - initW) / 2).coerceAtLeast(dpToPx(8f))
        val initY = ((screenH - initH) / 3).coerceAtLeast(dpToPx(42f))

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

        val composeView = ComposeView(themedContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@DragonFloatingArchiverService)
            setViewTreeViewModelStoreOwner(this@DragonFloatingArchiverService)
            setViewTreeSavedStateRegistryOwner(this@DragonFloatingArchiverService)

            setContent {
                val accentIndex by repository.accentColorIndexFlow.collectAsState()
                val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
                val themeConfig by repository.themeConfigFlow.collectAsState()

                DragonKernelTheme(
                    accentIndex = accentIndex,
                    isAmoledBlack = isAmoledBlack,
                    themeConfig = themeConfig
                ) {
                    FloatingDragonArchiverWindow(
                        repository = repository,
                        initialDirectoryPath = initialPathState.value,
                        isMinimized = isMinimizedState.value,
                        windowWidthDp = windowWidthDpState.intValue,
                        windowHeightDp = windowHeightDpState.intValue,
                        onDragWindow = { dx, dy -> moveWindowBy(dx, dy) },
                        onResizeWindow = { dw, dh, anchorLeft -> resizeWindowBy(dw, dh, anchorLeft) },
                        onApplySizePreset = { preset -> applySizePreset(preset) },
                        onToggleMinimize = { setMinimized(!isMinimizedState.value) },
                        onInputFocusNeeded = { needed -> setFocusableForInput(needed) },
                        onExpandToFullScreen = { activePath ->
                            openFullScreenArchiver(activePath)
                            stopSelf()
                        },
                        onCloseFloating = {
                            stopSelf()
                        }
                    )
                }
            }
        }

        overlayView = composeView
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        runCatching {
            windowManager.addView(composeView, params)
        }.onFailure {
            overlayView = null
            stopSelf()
        }
    }

    private fun detachFloatingOverlay() {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            windowManager.removeViewImmediate(view)
        }
    }

    private fun updateLayoutSafely() {
        val view = overlayView ?: return
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
        val minW = dpToPx(250f)
        val maxW = (screenW - dpToPx(12f)).coerceAtLeast(minW)
        val minH = dpToPx(260f)
        val maxH = (screenH - dpToPx(64f)).coerceAtLeast(minH)

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
            1 -> 0.86f to 0.58f // Balanced
            else -> 0.95f to 0.78f // Large
        }
        val targetW = (screenW * wFrac).roundToInt().coerceIn(dpToPx(250f), screenW - dpToPx(12f))
        val targetH = (screenH * hFrac).roundToInt().coerceIn(dpToPx(260f), screenH - dpToPx(64f))
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
            params.x = (screenW - bubblePx - dpToPx(12f)).coerceAtLeast(dpToPx(8f))
            params.y = params.y.coerceIn(dpToPx(72f), (screenH - bubblePx - dpToPx(96f)).coerceAtLeast(dpToPx(72f)))
        } else {
            params.width = savedExpandedWidthPx.coerceIn(dpToPx(250f), screenW - dpToPx(12f))
            params.height = savedExpandedHeightPx.coerceIn(dpToPx(260f), screenH - dpToPx(64f))
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
        if (currentInputFocusable == focusable) return
        currentInputFocusable = focusable
        params.flags = if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        updateLayoutSafely()
    }

    private fun openFullScreenArchiver(activePath: String?) {
        if (!activePath.isNullOrBlank()) {
            DragonFileManagerTaskEngine.savedCurrentDir = File(activePath)
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(DragonFileManagerTaskEngine.EXTRA_OPEN_FILE_MANAGER_TAB, true)
            if (!activePath.isNullOrBlank()) {
                putExtra(EXTRA_INITIAL_PATH, activePath)
            }
        }
        runCatching { startActivity(intent) }
    }

    companion object {
        const val CHANNEL_ID_FLOATING = "dragon_archiver_floating_v1"
        const val NOTIFICATION_ID_FLOATING = 7810
        const val ACTION_START_FLOATING = "com.example.dragon.action.START_FLOATING_ARCHIVER"
        const val ACTION_STOP_FLOATING = "com.example.dragon.action.STOP_FLOATING_ARCHIVER"
        const val ACTION_EXPAND_FULLSCREEN = "com.example.dragon.action.EXPAND_FLOATING_ARCHIVER"
        const val EXTRA_INITIAL_PATH = "extra_floating_archiver_initial_path"

        private val _isFloatingActiveFlow = MutableStateFlow(false)
        val isFloatingActiveFlow: StateFlow<Boolean> = _isFloatingActiveFlow.asStateFlow()

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

        fun startFloating(context: Context, initialPath: String? = null) {
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, DragonFloatingArchiverService::class.java).apply {
                action = ACTION_START_FLOATING
                if (!initialPath.isNullOrBlank()) {
                    putExtra(EXTRA_INITIAL_PATH, initialPath)
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
            val intent = Intent(appCtx, DragonFloatingArchiverService::class.java).apply {
                action = ACTION_STOP_FLOATING
            }
            runCatching { appCtx.stopService(intent) }
        }
    }
}
