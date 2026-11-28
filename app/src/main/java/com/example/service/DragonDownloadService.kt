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
import com.example.data.filemanager.DragonFileManagerTaskEngine
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
 * byte-range transfers, stream muxing, batch queues, AND Dragon Pro File Manager background
 * tasks alive even after the user switches screens, exits the app, or removes it from Recents.
 */
class DragonDownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watcherJob: Job? = null
    private var downloadWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var currentForegroundNotifId: Int = -1

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
        val hasDdmDownloadWork = hasActiveDownloadWork(currentTasks, currentBatch)
        val fmTask = DragonFileManagerTaskEngine.activeTaskFlow.value

        // Synchronously promote to Foreground immediately inside onStartCommand to avoid Android FGS timeouts.
        // If only a File Manager task is active, promote using its separate persistent notification (ID 7740).
        // If DDM downloads are active, promote using the DDM panel (ID 4010) while File Manager task posts separately on ID 7740.
        if (!hasDdmDownloadWork && fmTask != null) {
            promoteToForeground(
                notifId = DragonFileManagerTaskEngine.NOTIFICATION_ID_FILE_MANAGER_TASK,
                notification = DragonFileManagerTaskEngine.buildRunningTaskNotification(applicationContext, fmTask)
            )
        } else {
            promoteToForeground(
                notifId = DownloadNotificationController.NOTIFICATION_ID_DOWNLOAD_PANEL,
                notification = dm.notificationController.buildForegroundDownloadNotification(currentTasks)
            )
        }

        if (!hasActiveWork(currentTasks, currentBatch)) {
            releaseLocks()
            val isFmFg = currentForegroundNotifId == DragonFileManagerTaskEngine.NOTIFICATION_ID_FILE_MANAGER_TASK
            stopForegroundCompat(removeNotification = false)
            if (isFmFg) {
                DragonFileManagerTaskEngine.repostTerminalNotificationIfNeeded(applicationContext)
            }
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
            combine(
                dm.tasksFlow,
                dm.activeBatchSessionFlow,
                DragonFileManagerTaskEngine.activeTaskFlow
            ) { tasks, batch, fmTask ->
                hasActiveDownloadWork(tasks, batch) || fmTask != null
            }.collect { active ->
                if (active) {
                    acquireLocks()
                } else {
                    releaseLocks()
                    val isFmFg = currentForegroundNotifId == DragonFileManagerTaskEngine.NOTIFICATION_ID_FILE_MANAGER_TASK
                    stopForegroundCompat(removeNotification = false)
                    if (isFmFg) {
                        DragonFileManagerTaskEngine.repostTerminalNotificationIfNeeded(applicationContext)
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun promoteToForeground(
        notifId: Int,
        notification: android.app.Notification
    ) {
        currentForegroundNotifId = notifId
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
            if (hasActiveDownloadWork(dm.tasksFlow.value, dm.activeBatchSessionFlow.value)) {
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

        fun hasActiveDownloadWork(tasks: List<DownloaderTask>, batch: BatchSession?): Boolean {
            val hasActiveTask = tasks.any { it.stage.isActive || it.isSmartRetrying }
            val hasActiveBatch = batch != null && !batch.isFinished && !batch.isCancelled && !batch.isPaused
            return hasActiveTask || hasActiveBatch
        }

        fun hasActiveWork(tasks: List<DownloaderTask>, batch: BatchSession?): Boolean {
            return hasActiveDownloadWork(tasks, batch) || DragonFileManagerTaskEngine.hasActiveTask()
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
