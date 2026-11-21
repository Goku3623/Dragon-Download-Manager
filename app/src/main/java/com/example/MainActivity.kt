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

    Box(modifier = Modifier.fillMaxSize()) {
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
                    }
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
