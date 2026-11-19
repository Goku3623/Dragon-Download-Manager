package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.data.downloader.DownloadNotificationController
import com.example.data.model.BatchSession
import com.example.data.model.DownloaderTask
import com.example.data.repository.KernelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground Service keeping active Dragon Download Manager (DDM) downloads, multi-connection
 * byte-range transfers, stream muxing, and batch queues alive even after the user exits or
 * removes the app from Recents.
 *
 * Uses [DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL] so only a single unified
 * Download Notification Panel is displayed in the system shade.
 */
class DragonDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watcherJob: Job? = null
    private var downloadWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            downloadWakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DragonDownloadManager:ActiveDownloadWakeLock"
            )?.apply { setReferenceCounted(false) }
        } catch (_: Exception) {}

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(lockMode, "DragonDownloadManager:ActiveDownloadWifiLock")
                ?.apply { setReferenceCounted(false) }
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_DOWNLOAD_SERVICE) {
            releaseLocks()
            stopForegroundCompat(removeNotification = false)
            stopSelf()
            return START_NOT_STICKY
        }

        val repo = KernelRepository.getInstance(applicationContext)
        val dm = repo.downloadManager
        dm.ensurePersistedTasksLoaded()
        val currentTasks = dm.tasksFlow.value
        val currentBatch = dm.activeBatchSessionFlow.value

        // Synchronously promote to Foreground immediately inside onStartCommand to avoid Android FGS timeouts
        promoteToForeground(dm.notificationController.buildForegroundDownloadNotification(currentTasks))

        if (!hasActiveWork(currentTasks, currentBatch)) {
            releaseLocks()
            stopForegroundCompat(removeNotification = false)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks()
        startObservingDownloads()
        return START_STICKY
    }

    private fun startObservingDownloads() {
        if (watcherJob?.isActive == true) return
        watcherJob = serviceScope.launch {
            val dm = KernelRepository.getInstance(applicationContext).downloadManager
            combine(dm.tasksFlow, dm.activeBatchSessionFlow) { tasks, batch ->
                hasActiveWork(tasks, batch)
            }.collect { active ->
                if (active) {
                    acquireLocks()
                } else {
                    releaseLocks()
                    stopForegroundCompat(removeNotification = false)
                    stopSelf()
                }
            }
        }
    }

    private fun promoteToForeground(notification: android.app.Notification) {
        val notifId = DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    notifId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(notifId, notification)
            }
        } catch (_: Exception) {
            try {
                startForeground(notifId, notification)
            } catch (_: Exception) {}
        }
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(
                    if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
                )
            } else {
                @Suppress("DEPRECATION")
                stopForeground(removeNotification)
            }
        } catch (_: Exception) {}
    }

    private fun acquireLocks() {
        try {
            if (downloadWakeLock?.isHeld != true) {
                downloadWakeLock?.acquire(6L * 3600_000L)
            }
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseLocks() {
        try {
            if (downloadWakeLock?.isHeld == true) {
                downloadWakeLock?.release()
            }
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val dm = KernelRepository.getInstance(applicationContext).downloadManager
            dm.persistTasksCheckpoint()
            if (hasActiveWork(dm.tasksFlow.value, dm.activeBatchSessionFlow.value)) {
                dm.notificationController.syncTasks(dm.tasksFlow.value, force = true)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        watcherJob?.cancel()
        serviceScope.cancel()
        releaseLocks()
    }

    companion object {
        const val ACTION_START_DOWNLOAD_SERVICE = "com.example.ddm.ACTION_START_DOWNLOAD_SERVICE"
        const val ACTION_STOP_DOWNLOAD_SERVICE = "com.example.ddm.ACTION_STOP_DOWNLOAD_SERVICE"

        fun hasActiveWork(tasks: List<DownloaderTask>, batch: BatchSession?): Boolean {
            val hasActiveTask = tasks.any { it.stage.isActive || it.isSmartRetrying }
            val hasActiveBatch = batch != null && !batch.isFinished && !batch.isCancelled && !batch.isPaused
            return hasActiveTask || hasActiveBatch
        }

        fun startForegroundDownloads(context: Context) {
            val intent = Intent(context.applicationContext, DragonDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD_SERVICE
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (_: Exception) {
                try {
                    context.applicationContext.startService(intent)
                } catch (_: Exception) {}
            }
        }

        fun stopForegroundDownloads(context: Context) {
            val intent = Intent(context.applicationContext, DragonDownloadService::class.java).apply {
                action = ACTION_STOP_DOWNLOAD_SERVICE
            }
            try {
                context.applicationContext.startService(intent)
            } catch (_: Exception) {}
        }
    }
}
