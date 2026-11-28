package com.example

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.data.browser.BrowserPipController
import com.example.data.repository.KernelRepository
import com.example.receiver.DownloadNotificationReceiver
import com.example.service.DragonMediaPlaybackService
import com.example.ui.screens.BrowserScreen
import com.example.ui.theme.DragonKernelTheme

/**
 * Direct, instant-launch App Drawer Activity for Dragon Browser.
 *
 * Disabled by default in AndroidManifest.xml and toggled dynamically from the
 * bottom of the Browser 3-dot -> More menu via [setAppDrawerShortcutEnabled].
 * Renders [BrowserScreen] directly on Frame 1 with full PiP support, zero trampoline hop,
 * and zero DownloaderScreen composition overhead, keeping its own task alive for
 * sub-20ms warm re-launches from the App Drawer.
 */
class DragonBrowserLauncherActivity : ComponentActivity() {

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BrowserPipController.ACTION_PIP_TOGGLE_PLAY) {
                BrowserPipController.dispatchTogglePlayPause()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configurePowerEfficientRefreshRateWindow()
        BrowserPipController.isExplicitBrowserClose = false
        MainActivity.requestCloseEmbeddedBrowser()

        runCatching {
            ContextCompat.registerReceiver(
                this,
                pipActionReceiver,
                IntentFilter(BrowserPipController.ACTION_PIP_TOGGLE_PLAY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val themeConfig by repository.themeConfigFlow.collectAsState()

            val isLightTheme = themeConfig.soothingTheme.isLight
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = isLightTheme
                controller.isAppearanceLightNavigationBars = isLightTheme
            }

            LaunchedEffect(Unit) {
                BrowserPipController.updateBrowserOpen(true)
                BrowserPipController.stateFlow.collect { pipState ->
                    BrowserPipController.syncActivityPipParams(
                        this@DragonBrowserLauncherActivity,
                        pipState
                    )
                    DragonMediaPlaybackService.syncWithPlaybackState(
                        this@DragonBrowserLauncherActivity.applicationContext,
                        pipState
                    )
                }
            }

            DragonKernelTheme(
                accentIndex = accentIndex,
                isAmoledBlack = isAmoledBlack,
                themeConfig = themeConfig
            ) {
                BrowserScreen(
                    repository = repository,
                    onCloseBrowser = {
                        BrowserPipController.isExplicitBrowserClose =
                            !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
                        BrowserPipController.updateBrowserOpen(false)
                        openDragonDownloaderHome()
                    },
                    onOpenDownloadsTab = {
                        BrowserPipController.isExplicitBrowserClose =
                            !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
                        BrowserPipController.updateBrowserOpen(false)
                        openDragonDownloaderHome()
                    },
                    onExitBrowser = {
                        BrowserPipController.isExplicitBrowserClose =
                            !repository.downloadManager.hasActiveOrPausedBrowserStreamTasks()
                        BrowserPipController.updateBrowserOpen(false)
                        finish()
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        BrowserPipController.isExplicitBrowserClose = false
        MainActivity.requestCloseEmbeddedBrowser()
    }

    override fun onResume() {
        super.onResume()
        BrowserPipController.onActivityResumedChanged(true)
        BrowserPipController.updateBrowserOpen(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            BrowserPipController.onPipModeChanged(isInPictureInPictureMode)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val pipState = BrowserPipController.stateFlow.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BrowserPipController.syncActivityPipParams(this, pipState)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isInPictureInPictureMode && pipState.shouldEnterPipOnMinimize) {
                BrowserPipController.enterPipNow(this)
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode &&
            BrowserPipController.stateFlow.value.shouldKeepBackgroundMediaAlive &&
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        ) {
            BrowserPipController.setBackgroundMinimized(true)
        }
        BrowserPipController.onPipModeChanged(isInPictureInPictureMode)
        BrowserPipController.syncActivityPipParams(this)
    }

    override fun onPause() {
        super.onPause()
        BrowserPipController.onActivityResumedChanged(false)
        if (com.example.service.DragonFloatingBrowserService.isFloatingActiveFlow.value) {
            com.example.service.DragonFloatingBrowserService.keepFloatingMediaAlive()
        }
    }

    override fun onStop() {
        super.onStop()
        BrowserPipController.onActivityResumedChanged(false)
        if (com.example.service.DragonFloatingBrowserService.isFloatingActiveFlow.value) {
            com.example.service.DragonFloatingBrowserService.keepFloatingMediaAlive()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }

    private fun openDragonDownloaderHome() {
        MainActivity.requestLaunchRoute(MainActivity.ROUTE_DOWNLOADER)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(DownloadNotificationReceiver.EXTRA_OPEN_DOWNLOADER_TAB, true)
        }
        val opts = runCatching {
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        }.getOrNull()
        startActivity(launchIntent, opts)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun configurePowerEfficientRefreshRateWindow() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window.isFrameRatePowerSavingsBalanced = true
                window.setFrameRateBoostOnTouchEnabled(false)
            }
        } catch (_: Exception) {}
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, DragonBrowserLauncherActivity::class.java)
        }

        fun isAppDrawerShortcutEnabled(context: Context): Boolean {
            return runCatching {
                val state = context.applicationContext.packageManager
                    .getComponentEnabledSetting(getComponentName(context))
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }.getOrDefault(false)
        }

        fun setAppDrawerShortcutEnabled(context: Context, enabled: Boolean): Boolean {
            return runCatching {
                val newState = if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                context.applicationContext.packageManager.setComponentEnabledSetting(
                    getComponentName(context),
                    newState,
                    PackageManager.DONT_KILL_APP
                )
                enabled
            }.getOrDefault(isAppDrawerShortcutEnabled(context))
        }
    }
}
