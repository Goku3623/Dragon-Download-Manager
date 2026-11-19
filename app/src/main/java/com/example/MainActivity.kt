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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsPaused
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.data.repository.KernelRepository
import com.example.ui.haptics.LocalDragonHaptics
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.DownloaderScreen
import com.example.ui.theme.DragonKernelTheme
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }

    private val openDownloaderSignal = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val openBrowserSignal = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == com.example.data.browser.BrowserPipController.ACTION_PIP_TOGGLE_PLAY) {
                com.example.data.browser.BrowserPipController.dispatchTogglePlayPause()
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
        if (intent?.getBooleanExtra(com.example.receiver.DownloadNotificationReceiver.EXTRA_OPEN_DOWNLOADER_TAB, false) == true) {
            openDownloaderSignal.value += 1
        } else if (intent?.getBooleanExtra(com.example.service.DragonMediaPlaybackService.EXTRA_OPEN_BROWSER_TAB, false) == true) {
            openBrowserSignal.value += 1
        }

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val themeConfig by repository.themeConfigFlow.collectAsState()
            val downloaderNavTrigger by openDownloaderSignal.collectAsState()
            val browserNavTrigger by openBrowserSignal.collectAsState()

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
                    openBrowserTrigger = browserNavTrigger
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(com.example.receiver.DownloadNotificationReceiver.EXTRA_OPEN_DOWNLOADER_TAB, false)) {
            openDownloaderSignal.value += 1
        } else if (intent.getBooleanExtra(com.example.service.DragonMediaPlaybackService.EXTRA_OPEN_BROWSER_TAB, false)) {
            openBrowserSignal.value += 1
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.data.browser.BrowserPipController.onActivityResumedChanged(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            com.example.data.browser.BrowserPipController.onPipModeChanged(isInPictureInPictureMode)
        }
        repository.onMainActivityVisibilityChanged(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dm = repository.downloadManager
                dm.ensurePersistedTasksLoaded()
                dm.pruneDeletedCompletedTasks()
                dm.notificationController.syncTasks(dm.tasksFlow.value, force = false)
            } catch (_: Exception) {}
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            com.example.data.browser.BrowserPipController.syncActivityPipParams(this)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isInPictureInPictureMode && com.example.data.browser.BrowserPipController.stateFlow.value.shouldEnterPipOnMinimize) {
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
    }

    override fun onStop() {
        super.onStop()
        com.example.data.browser.BrowserPipController.onActivityResumedChanged(false)
        repository.onMainActivityVisibilityChanged(false)
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
    openBrowserTrigger: Int = 0
) {
    var isBrowserOpen by remember {
        mutableStateOf(
            com.example.data.browser.BackgroundMediaWebViewStore.hasRetainedWebViews() ||
                com.example.data.browser.BrowserPipController.stateFlow.value.shouldKeepBackgroundMediaAlive
        )
    }

    LaunchedEffect(isBrowserOpen) {
        if (isBrowserOpen || !com.example.data.browser.BackgroundMediaWebViewStore.hasRetainedWebViews()) {
            com.example.data.browser.BrowserPipController.updateBrowserOpen(isBrowserOpen)
        }
    }

    LaunchedEffect(openDownloaderTrigger) {
        if (openDownloaderTrigger > 0) {
            com.example.data.browser.BrowserPipController.isExplicitBrowserClose = true
            isBrowserOpen = false
        }
    }

    LaunchedEffect(openBrowserTrigger) {
        if (openBrowserTrigger > 0) {
            com.example.data.browser.BrowserPipController.isExplicitBrowserClose = false
            isBrowserOpen = true
        }
    }

    val feedbackMessage by repository.lastActionFeedbackFlow.collectAsState()
    val themeConfig by repository.themeConfigFlow.collectAsState()
    val haptics = LocalDragonHaptics.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedbackMessage) {
        feedbackMessage?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                duration = SnackbarDuration.Short
            )
            repository.clearFeedback()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                DragonDownloaderTopBar(
                    repository = repository,
                    isMaterial3Bar = themeConfig.surfaceMode == com.example.data.model.SurfaceThemeMode.MATERIAL_3,
                    onToggleNotifPanel = {
                        haptics.confirm()
                        repository.downloadManager.showNotificationPanel()
                    },
                    onBrowserClick = {
                        haptics.tap()
                        isBrowserOpen = true
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
                    onOpenBrowser = { isBrowserOpen = true }
                )
            }
        }

        if (isBrowserOpen) {
            BrowserScreen(
                repository = repository,
                onCloseBrowser = {
                    com.example.data.browser.BrowserPipController.isExplicitBrowserClose = true
                    isBrowserOpen = false
                },
                onOpenDownloadsTab = {
                    com.example.data.browser.BrowserPipController.isExplicitBrowserClose = true
                    isBrowserOpen = false
                }
            )
        }
    }
}

@Composable
private fun DragonDownloaderTopBar(
    repository: KernelRepository,
    isMaterial3Bar: Boolean,
    onToggleNotifPanel: () -> Unit,
    onBrowserClick: () -> Unit
) {
    val downloadManager = remember { repository.downloadManager }
    val tasks by downloadManager.tasksFlow.collectAsState()
    val isNotifVisible by downloadManager.notificationController.isPanelVisibleFlow.collectAsState()
    val activeCount = tasks.count { it.stage.isActive || it.isSmartRetrying }
    val totalActiveSpeedBps = tasks.filter { it.stage.isActive && !it.isSmartRetrying }.sumOf { it.speedBytesPerSec }
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isMaterial3Bar) MaterialTheme.colorScheme.surfaceContainer
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            )
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        .clip(CircleShape)
                        .border(1.dp, primaryColor.copy(alpha = 0.6f), CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (activeCount > 0) NeonCyan else SuccessGreen)
                )
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = "DRAGON DOWNLOAD MANAGER",
                    fontSize = 13.sp,
                    fontWeight = if (isMaterial3Bar) FontWeight.Bold else FontWeight.Black,
                    letterSpacing = if (isMaterial3Bar) 0.4.sp else 1.sp,
                    color = if (isMaterial3Bar) MaterialTheme.colorScheme.onSurface else primaryColor
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Download Notification Panel Status / Pin Pill
                val notifColor = if (isNotifVisible) SuccessGreen else WarningOrange
                val pillLabel = when {
                    activeCount > 0 && totalActiveSpeedBps > 0L ->
                        "$activeCount DL • ${com.example.data.model.formatBytes(totalActiveSpeedBps)}/s"
                    activeCount > 0 -> "$activeCount DL"
                    else -> "NOTIF"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp))
                        .background(notifColor.copy(alpha = 0.14f))
                        .border(
                            width = 1.dp,
                            color = notifColor.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp)
                        )
                        .clickable(onClick = onToggleNotifPanel)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("hud_notif_panel_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (isNotifVisible) Icons.Default.NotificationsActive else Icons.Default.NotificationsPaused,
                            contentDescription = "Download Notification Panel",
                            tint = notifColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = pillLabel,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            color = notifColor
                        )
                    }
                }

                // Built-in Browser & Media Grabber Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp))
                        .background(
                            if (isMaterial3Bar) MaterialTheme.colorScheme.primaryContainer
                            else NeonCyan.copy(alpha = 0.14f)
                        )
                        .border(
                            width = if (isMaterial3Bar) 0.8.dp else 1.dp,
                            color = if (isMaterial3Bar) MaterialTheme.colorScheme.outlineVariant else NeonCyan.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(if (isMaterial3Bar) 12.dp else 8.dp)
                        )
                        .clickable(onClick = onBrowserClick)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("hud_browser_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "Open Dragon Browser",
                            tint = if (isMaterial3Bar) MaterialTheme.colorScheme.onPrimaryContainer else NeonCyan,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "WEB",
                            fontSize = 10.sp,
                            fontFamily = if (isMaterial3Bar) FontFamily.SansSerif else FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isMaterial3Bar) MaterialTheme.colorScheme.onPrimaryContainer else NeonCyan
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            primaryColor.copy(alpha = 0.45f),
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}
