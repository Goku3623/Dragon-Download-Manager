package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.repository.KernelRepository
import com.example.ui.components.ThemePickerSheet
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.DownloaderScreen
import com.example.ui.theme.DragonKernelTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_DRAGON_ARCHIVER = "com.example.dragon.ACTION_OPEN_DRAGON_ARCHIVER"
        const val ACTION_OPEN_DRAGON_BROWSER = "com.example.dragon.ACTION_OPEN_DRAGON_BROWSER"
        const val ROUTE_DOWNLOADER = "DOWNLOADER"
        const val ROUTE_BROWSER = "BROWSER"
        const val ROUTE_FILE_MANAGER = "FILE_MANAGER"

        private val routeSequence = java.util.concurrent.atomic.AtomicInteger(0)
        private val pendingLaunchRouteFlow = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, String>?>(null)
        private val closeEmbeddedBrowserSeqFlow = kotlinx.coroutines.flow.MutableStateFlow(0)

        fun requestLaunchRoute(route: String) {
            pendingLaunchRouteFlow.value = routeSequence.incrementAndGet() to route
        }

        fun requestCloseEmbeddedBrowser() {
            closeEmbeddedBrowserSeqFlow.value = routeSequence.incrementAndGet()
        }
    }

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }

    private val openDownloaderSignal = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val openBrowserSignal = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val openFileManagerSignal = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.example.data.browser.BrowserPipController.ACTION_PIP_TOGGLE_PLAY) {
                com.example.data.browser.BrowserPipController.dispatchTogglePlayPause()
            }
        }
    }

    private fun handleLaunchIntent(incomingIntent: Intent?) {
        val targetIntent = incomingIntent ?: return
        val openDownloader = targetIntent.getBooleanExtra(
            com.example.receiver.DownloadNotificationReceiver.EXTRA_OPEN_DOWNLOADER_TAB,
            false
        )
        val openBrowser = targetIntent.action == ACTION_OPEN_DRAGON_BROWSER ||
            targetIntent.getBooleanExtra(
                com.example.service.DragonMediaPlaybackService.EXTRA_OPEN_BROWSER_TAB,
                false
            )
        val openFileManager = targetIntent.action == ACTION_OPEN_DRAGON_ARCHIVER ||
            targetIntent.getBooleanExtra(
                com.example.data.filemanager.DragonFileManagerTaskEngine.EXTRA_OPEN_FILE_MANAGER_TAB,
                false
            )

        if (targetIntent.action == Intent.ACTION_VIEW) {
            val viewUri = targetIntent.data
            val viewMime = targetIntent.type ?: viewUri?.let { contentResolver.getType(it) }
            if (viewUri != null && com.example.data.audio.DragonAudioPlayerEngine.isAudioMimeOrName(viewMime, viewUri.lastPathSegment)) {
                com.example.data.audio.DragonAudioPlayerEngine.playUri(applicationContext, viewUri)
                targetIntent.action = null
                return
            }
        }

        when {
            openDownloader -> {
                targetIntent.removeExtra(com.example.receiver.DownloadNotificationReceiver.EXTRA_OPEN_DOWNLOADER_TAB)
                openDownloaderSignal.value += 1
                requestLaunchRoute(ROUTE_DOWNLOADER)
            }
            openBrowser -> {
                targetIntent.removeExtra(com.example.service.DragonMediaPlaybackService.EXTRA_OPEN_BROWSER_TAB)
                targetIntent.getStringExtra(com.example.service.DragonFloatingBrowserService.EXTRA_INITIAL_URL)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { targetUrl ->
                        repository.browserRepository.openNewTab(initialUrl = targetUrl)
                        targetIntent.removeExtra(com.example.service.DragonFloatingBrowserService.EXTRA_INITIAL_URL)
                    }
                openBrowserSignal.value += 1
                requestLaunchRoute(ROUTE_BROWSER)
            }
            openFileManager -> {
                targetIntent.removeExtra(com.example.data.filemanager.DragonFileManagerTaskEngine.EXTRA_OPEN_FILE_MANAGER_TAB)
                targetIntent.getStringExtra(com.example.service.DragonFloatingArchiverService.EXTRA_INITIAL_PATH)
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        com.example.data.filemanager.DragonFileManagerTaskEngine.savedCurrentDir = java.io.File(it)
                        targetIntent.removeExtra(com.example.service.DragonFloatingArchiverService.EXTRA_INITIAL_PATH)
                    }
                openFileManagerSignal.value += 1
                requestLaunchRoute(ROUTE_FILE_MANAGER)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configurePowerEfficientRefreshRateWindow()
        runCatching {
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                IntentFilter(com.example.data.browser.BrowserPipController.ACTION_PIP_TOGGLE_PLAY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
        handleLaunchIntent(intent)

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val themeConfig by repository.themeConfigFlow.collectAsState()
            val downloaderNavTrigger by openDownloaderSignal.collectAsState()
            val browserNavTrigger by openBrowserSignal.collectAsState()
            val fileManagerNavTrigger by openFileManagerSignal.collectAsState()
            val pendingLaunchRoute by pendingLaunchRouteFlow.collectAsState()
            val closeEmbeddedBrowserSeq by closeEmbeddedBrowserSeqFlow.collectAsState()

            val isLightTheme = themeConfig.soothingTheme.isLight
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = isLightTheme
                controller.isAppearanceLightNavigationBars = isLightTheme
            }

            DragonKernelTheme(
                accentIndex = accentIndex,
                isAmoledBlack = isAmoledBlack,
                themeConfig = themeConfig
            ) {
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val dm = repository.downloadManager
                            dm.ensurePersistedTasksLoaded()
                            dm.notificationController.syncTasks(dm.tasksFlow.value, force = true)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                LaunchedEffect(Unit) {
                    com.example.data.browser.BrowserPipController.stateFlow.collect { pipState ->
                        com.example.data.browser.BrowserPipController.syncActivityPipParams(
                            this@MainActivity,
                            pipState
                        )
                        com.example.service.DragonMediaPlaybackService.syncWithPlaybackState(
                            this@MainActivity.applicationContext,
                            pipState
                        )
                    }
                }

                DragonDownloadManagerMainScreen(
                    repository = repository,
                    openDownloaderTrigger = downloaderNavTrigger,
                    openBrowserTrigger = browserNavTrigger,
                    openFileManagerTrigger = fileManagerNavTrigger,
                    pendingLaunchRoute = pendingLaunchRoute,
                    closeEmbeddedBrowserSeq = closeEmbeddedBrowserSeq,
                    onConsumeLaunchRoute = { seq ->
                        if (pendingLaunchRouteFlow.value?.first == seq) {
                            pendingLaunchRouteFlow.value = null
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.example.data.browser.BrowserPipController.onActivityResumedChanged(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            com.example.data.browser.BrowserPipController.onPipModeChanged(isInPictureInPictureMode)
        }
        repository.onMainActivityVisibilityChanged(true)
        com.example.data.downloader.ApkPackageInstaller.notifyPackageInstallChanged()
        com.example.data.downloader.ApkPackageInstaller.resumePendingPostUninstallIfReady(this@MainActivity)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
            val dm = repository.downloadManager
            dm.ensurePersistedTasksLoaded()
            dm.pruneDeletedCompletedTasks()
            dm.resumePendingPackageInstallIfReady(this@MainActivity)
            dm.notificationController.syncTasks(dm.tasksFlow.value, force = false)
            com.example.data.cache.DragonSmartCacheController.runSmartMaintenanceAsync(
                context = this@MainActivity.applicationContext,
                tasks = dm.tasksFlow.value,
                aggressive = false
            )
            } catch (_: Exception) {}
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val pipState = com.example.data.browser.BrowserPipController.stateFlow.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            com.example.data.browser.BrowserPipController.syncActivityPipParams(this, pipState)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isInPictureInPictureMode && pipState.shouldEnterPipOnMinimize) {
                com.example.data.browser.BrowserPipController.enterPipNow(this)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode &&
            com.example.data.browser.BrowserPipController.stateFlow.value.shouldKeepBackgroundMediaAlive &&
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            // User tapped the cross (X) button in System PiP: minimize to background playback instead of closing video.
            com.example.data.browser.BrowserPipController.setBackgroundMinimized(true)
        }
        com.example.data.browser.BrowserPipController.onPipModeChanged(isInPictureInPictureMode)
        com.example.data.browser.BrowserPipController.syncActivityPipParams(this)
    }

    override fun onPause() {
        super.onPause()
        com.example.data.browser.BrowserPipController.onActivityResumedChanged(false)
        repository.onMainActivityVisibilityChanged(false)
        if (com.example.service.DragonFloatingBrowserService.isFloatingActiveFlow.value) {
            com.example.service.DragonFloatingBrowserService.keepFloatingMediaAlive()
        }
    }

    override fun onStop() {
        super.onStop()
        com.example.data.browser.BrowserPipController.onActivityResumedChanged(false)
        repository.onMainActivityVisibilityChanged(false)
        if (com.example.service.DragonFloatingBrowserService.isFloatingActiveFlow.value) {
            com.example.service.DragonFloatingBrowserService.keepFloatingMediaAlive()
        }
        com.example.data.cache.DragonSmartCacheController.runSmartMaintenanceAsync(
            context = applicationContext,
            tasks = repository.downloadManager.tasksFlow.value,
            aggressive = false
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.example.data.cache.DragonSmartCacheController.runSmartMaintenanceAsync(
                context = applicationContext,
                tasks = repository.downloadManager.tasksFlow.value,
                aggressive = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            )
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }

    private fun configurePowerEfficientRefreshRateWindow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.isFrameRatePowerSavingsBalanced = true
                window.setFrameRateBoostOnTouchEnabled(false)
            }
        } catch (_: Exception) {}
    }
}

@Composable
fun DragonDownloadManagerMainScreen(
    repository: KernelRepository,
    openDownloaderTrigger: Int = 0,
    openBrowserTrigger: Int = 0,
    openFileManagerTrigger: Int = 0,
    pendingLaunchRoute: Pair<Int, String>? = null,
    closeEmbeddedBrowserSeq: Int = 0,
    onConsumeLaunchRoute: (Int) -> Unit = {}
) {
    var isFileManagerOpen by remember {
        mutableStateOf(
            pendingLaunchRoute?.second == MainActivity.ROUTE_FILE_MANAGER ||
                openFileManagerTrigger > 0
        )
    }
    var isBrowserOpen by remember {
        mutableStateOf(
            when {
                pendingLaunchRoute?.second == MainActivity.ROUTE_FILE_MANAGER || openFileManagerTrigger > 0 -> false
                pendingLaunchRoute?.second == MainActivity.ROUTE_BROWSER || openBrowserTrigger > 0 -> true
                pendingLaunchRoute?.second == MainActivity.ROUTE_DOWNLOADER || openDownloaderTrigger > 0 -> false
                else -> com.example.data.browser.BackgroundMediaWebViewStore.hasRetainedWebViews() ||
                    com.example.data.browser.BrowserPipController.stateFlow.value.shouldKeepBackgroundMediaAlive
            }
        )
    }

    LaunchedEffect(isBrowserOpen) {
        com.example.data.browser.BrowserPipController.updateBrowserOpen(isBrowserOpen)
    }

    LaunchedEffect(pendingLaunchRoute) {
        val event = pendingLaunchRoute ?: return@LaunchedEffect
        when (event.second) {
            MainActivity.ROUTE_FILE_MANAGER -> {
                isBrowserOpen = false
                isFileManagerOpen = true
            }
            MainActivity.ROUTE_BROWSER -> {
                com.example.data.browser.BrowserPipController.isExplicitBrowserClose = false
                isFileManagerOpen = false
                isBrowserOpen = true
            }
            MainActivity.ROUTE_DOWNLOADER -> {
                com.example.data.browser.BrowserPipController.isExplicitBrowserClose =
                    !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
                isBrowserOpen = false
                isFileManagerOpen = false
            }
        }
        onConsumeLaunchRoute(event.first)
    }

    LaunchedEffect(openDownloaderTrigger) {
        if (openDownloaderTrigger > 0) {
            com.example.data.browser.BrowserPipController.isExplicitBrowserClose =
                !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
            isBrowserOpen = false
            isFileManagerOpen = false
        }
    }

    LaunchedEffect(openBrowserTrigger) {
        if (openBrowserTrigger > 0) {
            com.example.data.browser.BrowserPipController.isExplicitBrowserClose = false
            isFileManagerOpen = false
            isBrowserOpen = true
        }
    }

    LaunchedEffect(openFileManagerTrigger) {
        if (openFileManagerTrigger > 0) {
            isBrowserOpen = false
            isFileManagerOpen = true
        }
    }

    LaunchedEffect(closeEmbeddedBrowserSeq) {
        if (closeEmbeddedBrowserSeq > 0 && isBrowserOpen) {
            isBrowserOpen = false
        }
    }

    val feedbackMessage by repository.lastActionFeedbackFlow.collectAsState()
    val themeConfig by repository.themeConfigFlow.collectAsState()
    val haptics = LocalDragonHaptics.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showThemePickerSheet by remember { mutableStateOf(false) }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            repository.clearFeedback()
        }
    }

    val retainedWvCount by com.example.data.browser.BackgroundMediaWebViewStore.retainedCountFlow.collectAsState()
    val downloadTasks by repository.downloadManager.tasksFlow.collectAsState()
    val hasRetainedBrowserDownload = remember(downloadTasks) {
        downloadTasks.any { t ->
            repository.downloadManager.isExternalWebManagedTask(t.id) &&
                (t.stage.isActive || t.stage == com.example.data.model.DownloadStateStage.PAUSED)
        }
    }
    var keepRetainedHostGrace by remember { mutableStateOf(false) }
    LaunchedEffect(hasRetainedBrowserDownload) {
        if (hasRetainedBrowserDownload) {
            keepRetainedHostGrace = true
        } else if (keepRetainedHostGrace) {
            kotlinx.coroutines.delay(2500L)
            keepRetainedHostGrace = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Keep retained Telegram/media WebView attached at FULL viewport size behind the opaque Scaffold while
        // viewing Download Panel so cancel/pause/resume and deferred scroll-back cancellation always have a live attached window
        if (!isBrowserOpen && retainedWvCount > 0) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.01f),
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        isClickable = false
                        isFocusable = false
                    }
                },
                update = { container ->
                    val wv = com.example.data.browser.BackgroundMediaWebViewStore.getPrimaryRetainedWebView()
                    if (wv != null && wv.parent !== container) {
                        container.removeAllViews()
                        runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
                        runCatching {
                            container.addView(
                                wv,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                            wv.resumeTimers()
                            wv.onResume()
                        }
                    }
                },
                onRelease = { container ->
                    container.removeAllViews()
                }
            )
        }

        if (!isFileManagerOpen && !isBrowserOpen) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    DragonDownloaderTopBar(
                        repository = repository,
                        onOpenThemeStudio = {
                            haptics.tap()
                            showThemePickerSheet = true
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    DownloaderScreen(
                        repository = repository,
                        onOpenBrowser = { isBrowserOpen = true },
                        onOpenBrowserWithUrl = { targetUrl ->
                            com.example.data.browser.BrowserPipController.isExplicitBrowserClose = false
                            repository.browserRepository.openNewTab(initialUrl = targetUrl)
                            isBrowserOpen = true
                        },
                        onOpenFileManager = {
                            isFileManagerOpen = true
                        }
                    )
                }
            }
        }

        if (isFileManagerOpen) {
            val ctx = LocalContext.current
            com.example.ui.screens.DragonFileManagerScreen(
                repository = repository,
                onClose = { isFileManagerOpen = false },
                onOpenInSystemFileManager = {
                    repository.downloadManager.openDdmRootFolder(ctx)
                },
                onExit = {
                    isFileManagerOpen = false
                    var c: Context? = ctx
                    while (c is android.content.ContextWrapper && c !is android.app.Activity) {
                        c = c.baseContext
                    }
                    (c as? android.app.Activity)?.moveTaskToBack(true)
                }
            )
        }

        if (isBrowserOpen) {
            val ctx = LocalContext.current
            BrowserScreen(
                repository = repository,
                onCloseBrowser = {
                    com.example.data.browser.BrowserPipController.isExplicitBrowserClose =
                        !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
                    isBrowserOpen = false
                },
                onOpenDownloadsTab = {
                    com.example.data.browser.BrowserPipController.isExplicitBrowserClose =
                        !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
                    isBrowserOpen = false
                },
                onExitBrowser = {
                    com.example.data.browser.BrowserPipController.isExplicitBrowserClose =
                        !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
                    isBrowserOpen = false
                    var c: Context? = ctx
                    while (c is android.content.ContextWrapper && c !is android.app.Activity) {
                        c = c.baseContext
                    }
                    (c as? android.app.Activity)?.moveTaskToBack(true)
                }
            )
        }

        com.example.ui.components.DragonMiniAudioPlayerHost(
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        com.example.ui.components.PackageInstallResolutionDialogHost()

        if (showThemePickerSheet) {
            ThemePickerSheet(
                themeConfig = themeConfig,
                onSelectTheme = { selected ->
                    repository.setSoothingAppTheme(selected)
                },
                onToggleDynamicWallpaper = { enabled ->
                    repository.toggleDynamicColor(enabled)
                },
                onSelectCornerRadius = { radius ->
                    repository.setCornerRadiusDp(radius)
                },
                onDismiss = { showThemePickerSheet = false }
            )
        }
    }
}

@Composable
private fun DragonDownloaderTopBar(
    repository: KernelRepository,
    onOpenThemeStudio: () -> Unit
) {
    val downloadManager = remember { repository.downloadManager }
    val tasks by downloadManager.tasksFlow.collectAsState()
    val themeConfig by repository.themeConfigFlow.collectAsState()
    val isFlatMinimal = themeConfig.soothingTheme.isFlatMinimal
    val activeCount = tasks.count { it.stage.isActive || it.isSmartRetrying }
    val primaryColor = MaterialTheme.colorScheme.primary
    val pillRadius = if (isFlatMinimal) 8.dp else 14.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isFlatMinimal) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                val context = LocalContext.current
                AsyncImage(
                    model = remember(context) {
                        ImageRequest.Builder(context)
                            .data(R.drawable.ic_ddm_cloud_bolt_logo_1794942953000)
                            .size(96, 96)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = "Dragon Download Manager Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(if (isFlatMinimal) RoundedCornerShape(7.dp) else CircleShape)
                        .border(
                            width = 1.dp,
                            color = if (isFlatMinimal) MaterialTheme.colorScheme.outlineVariant else primaryColor.copy(alpha = 0.55f),
                            shape = if (isFlatMinimal) RoundedCornerShape(7.dp) else CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (activeCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondary
                        )
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "DRAGON DOWNLOAD MANAGER",
                    fontSize = 13.sp,
                    fontWeight = if (isFlatMinimal) FontWeight.SemiBold else FontWeight.Bold,
                    letterSpacing = 0.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Theme Studio Section Trigger Pill
            Surface(
                shape = RoundedCornerShape(pillRadius),
                color = if (isFlatMinimal) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isFlatMinimal) 1.dp else 0.9.dp,
                    color = if (isFlatMinimal) MaterialTheme.colorScheme.outlineVariant
                    else primaryColor.copy(alpha = 0.35f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(pillRadius))
                    .clickable(onClick = onOpenThemeStudio)
                    .testTag("hud_theme_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Customize UI Theme",
                        tint = if (isFlatMinimal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Theme",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isFlatMinimal) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        if (isFlatMinimal) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                primaryColor.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }
}
