package com.example

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.example.data.filemanager.DragonFileManagerTaskEngine
import com.example.data.repository.KernelRepository
import com.example.receiver.DownloadNotificationReceiver
import com.example.ui.screens.DragonFileManagerScreen
import com.example.ui.theme.DragonKernelTheme

/**
 * Direct, instant-launch App Drawer Activity for Dragon Archiver.
 *
 * Disabled by default in AndroidManifest.xml and toggled dynamically from the
 * bottom of the Dragon Archiver 3-dot menu via [setAppDrawerShortcutEnabled].
 * Renders [DragonFileManagerScreen] directly on Frame 1 with zero trampoline hop
 * and zero DownloaderScreen composition overhead, keeping its own task alive for
 * sub-20ms warm re-launches from the App Drawer.
 */
class DragonArchiverLauncherActivity : ComponentActivity() {

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configurePowerEfficientRefreshRateWindow()
        DragonFileManagerTaskEngine.ensurePreferencesLoaded(applicationContext)

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val themeConfig by repository.themeConfigFlow.collectAsState()

            val isLightTheme = themeConfig.soothingTheme.isLight
            val view = androidx.compose.ui.platform.LocalView.current
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
                val ctx = LocalContext.current
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                ) {
                    DragonFileManagerScreen(
                        repository = repository,
                        onClose = { openDragonDownloaderHome() },
                        onOpenInSystemFileManager = {
                            repository.downloadManager.openDdmRootFolder(ctx)
                        },
                        onExit = { finish() }
                    )
                    com.example.ui.components.DragonMiniAudioPlayerHost(
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    )
                    com.example.ui.components.PackageInstallResolutionDialogHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        com.example.data.downloader.ApkPackageInstaller.notifyPackageInstallChanged()
        com.example.data.downloader.ApkPackageInstaller.resumePendingPostUninstallIfReady(this)
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
            return ComponentName(context.applicationContext, DragonArchiverLauncherActivity::class.java)
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
