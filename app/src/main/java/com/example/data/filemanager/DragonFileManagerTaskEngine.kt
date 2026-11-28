package com.example.data.filemanager

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.downloader.ApkAutoSigner
import com.example.data.downloader.ApkPackageInstaller
import com.example.data.downloader.DragonDownloadManager
import com.example.data.model.formatBytes
import com.example.receiver.DownloadNotificationReceiver
import com.example.service.DragonDownloadService
import com.example.ui.screens.ClipboardTransferOperation
import com.example.ui.screens.FileItemEntry
import com.example.ui.screens.FileManagerClipboardState
import com.example.ui.screens.FileManagerSortField
import com.example.ui.screens.FileManagerViewStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class FileManagerRunningTaskState(
    val taskId: Int,
    val title: String,
    val subtitle: String,
    val currentFileName: String,
    val completedItems: Int,
    val totalItems: Int,
    val processedBytes: Long,
    val totalBytes: Long,
    val progressPercent: Int,
    val isIndeterminate: Boolean,
    val isPaused: Boolean = false,
    val startedAtMs: Long = System.currentTimeMillis()
)

enum class FileConflictAction {
    COPY_AND_REPLACE,
    KEEP_BOTH,
    SKIP
}

enum class FileManagerTerminalStatus {
    COMPLETED,
    CANCELLED,
    FAILED
}

data class FileManagerTerminalState(
    val taskId: Int,
    val title: String,
    val summaryMessage: String,
    val status: FileManagerTerminalStatus
)

data class FileConflictPromptState(
    val taskId: Int,
    val operationLabel: String,
    val fileName: String,
    val destinationPath: String,
    val isDirectory: Boolean = false,
    val existingSizeBytes: Long = -1L,
    val incomingSizeBytes: Long = -1L,
    val allowApplyToAll: Boolean = true
)

/**
 * Application-scoped Background Execution Engine & Separate Material 3 Persistent Notification Manager
 * for Dragon Pro File Manager.
 *
 * Ensures that all File Manager operations (Copy, Move/Cut, Paste, Duplicate, Delete,
 * Archive Extraction, Universal Multi-Format Compression, Batch Rename, and Standalone V1+V2 APK Auto-Signing)
 * continue running uninterrupted even when the user switches back to DDM ("Returned to DDM")
 * or exits the app completely, while displaying live progress in a dedicated Material 3
 * persistent notification panel (`NOTIFICATION_ID_FILE_MANAGER_TASK`) with interactive
 * Pause / Resume and Cancel controls.
 */
object DragonFileManagerTaskEngine {

    const val CHANNEL_ID_FILE_MANAGER_TASKS = "dragon_file_manager_tasks_v1"
    const val NOTIFICATION_ID_FILE_MANAGER_TASK = 7740
    const val NOTIFICATION_ID_FILE_MANAGER_COMPLETED = 7741
    const val EXTRA_OPEN_FILE_MANAGER_TAB = "extra_open_file_manager_tab"
    private const val GROUP_FILE_MANAGER_TASK_HUD = "com.example.dragon.GROUP_FILE_MANAGER_TASK_HUD"

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskCounter = AtomicInteger(1)

    private val pauseLock = Object()
    private val conflictLock = Object()

    @Volatile
    private var activeTaskId: Int = 0

    @Volatile
    private var isTaskPaused: Boolean = false

    @Volatile
    private var cancelledTaskId: Int = -1

    @Volatile
    private var taskWideConflictAction: FileConflictAction? = null

    @Volatile
    private var pendingConflictDecision: FileConflictAction? = null

    @Volatile
    private var activeTaskJob: Job? = null

    @Volatile
    private var lastSystemNotifPostElapsedMs: Long = 0L

    @Volatile
    private var lastSystemNotifPercent: Int = -1

    @Volatile
    private var lastSystemNotifItems: Int = -1

    @Volatile
    private var lastTaskEndedByCancel: Boolean = false

    @Volatile
    private var lastTerminalTaskState: FileManagerTerminalState? = null

    private const val MIN_SYSTEM_NOTIF_INTERVAL_MS = 420L

    private val _activeTaskFlow = MutableStateFlow<FileManagerRunningTaskState?>(null)
    val activeTaskFlow: StateFlow<FileManagerRunningTaskState?> = _activeTaskFlow.asStateFlow()

    private val _pendingConflictFlow = MutableStateFlow<FileConflictPromptState?>(null)
    val pendingConflictFlow: StateFlow<FileConflictPromptState?> = _pendingConflictFlow.asStateFlow()

    private val _refreshTickFlow = MutableStateFlow(0)
    val refreshTickFlow: StateFlow<Int> = _refreshTickFlow.asStateFlow()

    // Preserved File Manager session state across DDM <-> File Manager switches
    @Volatile
    var savedCurrentDir: File? = null

    @Volatile
    var savedNavigationHistory: List<File> = emptyList()

    @Volatile
    var savedClipboard: FileManagerClipboardState? = null

    private const val PREFS_NAME_ARCHIVER = "dragon_archiver_prefs_v1"
    private const val KEY_VIEW_STYLE = "view_style"
    private const val KEY_SORT_FIELD = "sort_field"
    private const val KEY_SORT_ASCENDING = "sort_ascending"
    private const val KEY_SHOW_HIDDEN = "show_hidden_files"
    private const val KEY_SECONDARY_SORT_FIELD = "secondary_sort_field"
    private const val KEY_SECONDARY_SORT_ASCENDING = "secondary_sort_ascending"
    private const val KEY_SECONDARY_SHOW_HIDDEN = "secondary_show_hidden_files"
    private const val KEY_ACTIVE_SPLIT_PANE = "active_split_pane"

    @Volatile
    private var prefsLoaded: Boolean = false

    @Volatile
    var savedViewStyle: FileManagerViewStyle = FileManagerViewStyle.DETAILED_LIST

    @Volatile
    var savedSortField: FileManagerSortField = FileManagerSortField.NAME

    @Volatile
    var savedSortAscending: Boolean = true

    @Volatile
    var savedShowHiddenFiles: Boolean = false

    @Volatile
    var savedSecondaryDir: File? = null

    @Volatile
    var savedSecondarySortField: FileManagerSortField = FileManagerSortField.NAME

    @Volatile
    var savedSecondarySortAscending: Boolean = true

    @Volatile
    var savedSecondaryShowHiddenFiles: Boolean = false

    @Volatile
    var savedActiveSplitPane: Int = 0

    fun ensurePreferencesLoaded(context: Context) {
        if (prefsLoaded) return
        synchronized(this) {
            if (prefsLoaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME_ARCHIVER, Context.MODE_PRIVATE)
            prefs.getString(KEY_VIEW_STYLE, null)?.let { name ->
                runCatching { FileManagerViewStyle.valueOf(name) }.getOrNull()?.let { savedViewStyle = it }
            }
            prefs.getString(KEY_SORT_FIELD, null)?.let { name ->
                runCatching { FileManagerSortField.valueOf(name) }.getOrNull()?.let { savedSortField = it }
            }
            savedSortAscending = prefs.getBoolean(KEY_SORT_ASCENDING, true)
            savedShowHiddenFiles = prefs.getBoolean(KEY_SHOW_HIDDEN, false)

            prefs.getString(KEY_SECONDARY_SORT_FIELD, null)?.let { name ->
                runCatching { FileManagerSortField.valueOf(name) }.getOrNull()?.let { savedSecondarySortField = it }
            }
            savedSecondarySortAscending = prefs.getBoolean(KEY_SECONDARY_SORT_ASCENDING, true)
            savedSecondaryShowHiddenFiles = prefs.getBoolean(KEY_SECONDARY_SHOW_HIDDEN, false)
            savedActiveSplitPane = prefs.getInt(KEY_ACTIVE_SPLIT_PANE, 0).coerceIn(0, 1)
            prefsLoaded = true
        }
    }

    fun saveViewStyle(context: Context, style: FileManagerViewStyle) {
        savedViewStyle = style
        context.applicationContext
            .getSharedPreferences(PREFS_NAME_ARCHIVER, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIEW_STYLE, style.name)
            .apply()
    }

    fun saveSortPreferences(context: Context, field: FileManagerSortField, ascending: Boolean) {
        savedSortField = field
        savedSortAscending = ascending
        context.applicationContext
            .getSharedPreferences(PREFS_NAME_ARCHIVER, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SORT_FIELD, field.name)
            .putBoolean(KEY_SORT_ASCENDING, ascending)
            .apply()
    }

    fun saveShowHiddenFiles(context: Context, showHidden: Boolean) {
        savedShowHiddenFiles = showHidden
        context.applicationContext
            .getSharedPreferences(PREFS_NAME_ARCHIVER, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_HIDDEN, showHidden)
            .apply()
    }

    fun saveSecondaryPanePreferences(
        context: Context,
        field: FileManagerSortField,
        ascending: Boolean,
        showHidden: Boolean
    ) {
        savedSecondarySortField = field
        savedSecondarySortAscending = ascending
        savedSecondaryShowHiddenFiles = showHidden
        context.applicationContext
            .getSharedPreferences(PREFS_NAME_ARCHIVER, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SECONDARY_SORT_FIELD, field.name)
            .putBoolean(KEY_SECONDARY_SORT_ASCENDING, ascending)
            .putBoolean(KEY_SECONDARY_SHOW_HIDDEN, showHidden)
            .apply()
    }

    fun saveActiveSplitPane(context: Context, paneIndex: Int) {
        val clamped = paneIndex.coerceIn(0, 1)
        savedActiveSplitPane = clamped
        context.applicationContext
            .getSharedPreferences(PREFS_NAME_ARCHIVER, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_ACTIVE_SPLIT_PANE, clamped)
            .apply()
    }

    // Persistent in-memory UI snapshot cache for instant (0ms) upper-panel & folder switching
    private val directorySnapshotCache = ConcurrentHashMap<String, List<FileItemEntry>>()

    fun getCachedDirectoryItems(cacheKey: String): List<FileItemEntry>? {
        return directorySnapshotCache[cacheKey]
    }

    fun putCachedDirectoryItems(cacheKey: String, items: List<FileItemEntry>) {
        if (directorySnapshotCache.size > 80) {
            directorySnapshotCache.clear()
        }
        directorySnapshotCache[cacheKey] = items
    }

    fun invalidateDirectorySnapshotCache(dirPath: String? = null) {
        if (dirPath == null) {
            directorySnapshotCache.clear()
            AndroidDataObbBridge.invalidateDirectoryCache(null)
        } else {
            directorySnapshotCache.keys.removeIf { it.startsWith("$dirPath|") }
            AndroidDataObbBridge.invalidateDirectoryCache(dirPath)
        }
    }

    fun hasActiveTask(): Boolean = _activeTaskFlow.value != null

    fun triggerDirectoryRefresh() {
        invalidateDirectorySnapshotCache(null)
        _refreshTickFlow.value += 1
    }

    /**
     * Cooperative checkpoint called from I/O streaming loops (copy, move, extract, compress, delete, rename).
     * Immediately blocks while paused and wakes instantaneously on Resume or Cancel.
     */
    fun checkPauseOrCancel(taskId: Int = activeTaskId) {
        if (taskId > 0 && cancelledTaskId == taskId) {
            throw CancellationException("File Manager task cancelled by user")
        }
        if (Thread.currentThread().isInterrupted) {
            throw CancellationException("File Manager task interrupted")
        }
        if (isTaskPaused && (taskId <= 0 || activeTaskId == taskId)) {
            synchronized(pauseLock) {
                while (isTaskPaused && (taskId <= 0 || cancelledTaskId != taskId)) {
                    try {
                        pauseLock.wait(200L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw CancellationException("File Manager task interrupted while paused")
                    }
                }
            }
        }
        if (taskId > 0 && cancelledTaskId == taskId) {
            throw CancellationException("File Manager task cancelled by user")
        }
    }

    fun pauseActiveTask(context: Context) {
        val current = _activeTaskFlow.value ?: return
        if (current.isPaused && isTaskPaused) return
        isTaskPaused = true
        val pausedState = current.copy(isPaused = true)
        postOrUpdateRunningNotification(context.applicationContext, pausedState, forceUpdate = true)
    }

    fun resumeActiveTask(context: Context) {
        val current = _activeTaskFlow.value ?: return
        isTaskPaused = false
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        val resumedState = current.copy(isPaused = false)
        postOrUpdateRunningNotification(context.applicationContext, resumedState, forceUpdate = true)
    }

    fun togglePauseActiveTask(context: Context) {
        val current = _activeTaskFlow.value ?: return
        if (current.isPaused || isTaskPaused) {
            resumeActiveTask(context)
        } else {
            pauseActiveTask(context)
        }
    }

    fun cancelActiveTask(context: Context) {
        val appContext = context.applicationContext
        val current = _activeTaskFlow.value
        if (current == null) {
            runCatching {
                NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID_FILE_MANAGER_TASK)
            }
            return
        }

        val cleanName = current.title.removePrefix("File Manager • ").ifBlank { "Task" }
        val detailFile = current.currentFileName.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        val cancelSummary = "Cancelled by user$detailFile"

        cancelledTaskId = current.taskId
        lastTaskEndedByCancel = true
        lastTerminalTaskState = FileManagerTerminalState(
            taskId = current.taskId,
            title = "Cancelled • $cleanName",
            summaryMessage = cancelSummary,
            status = FileManagerTerminalStatus.CANCELLED
        )
        isTaskPaused = false
        taskWideConflictAction = null
        pendingConflictDecision = null
        _pendingConflictFlow.value = null
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        synchronized(conflictLock) {
            conflictLock.notifyAll()
        }
        activeTaskJob?.cancel(CancellationException("Cancelled by user"))
        activeTaskJob = null
        _activeTaskFlow.value = null
        triggerDirectoryRefresh()

        ensureChannel(appContext)
        if (hasNotificationPermission(appContext)) {
            runCatching {
                val nm = NotificationManagerCompat.from(appContext)
                nm.cancel(NOTIFICATION_ID_FILE_MANAGER_COMPLETED)
                nm.notify(
                    NOTIFICATION_ID_FILE_MANAGER_TASK,
                    buildCompletedTaskNotification(
                        context = appContext,
                        title = "Cancelled • $cleanName",
                        summaryMessage = cancelSummary,
                        status = FileManagerTerminalStatus.CANCELLED
                    )
                )
            }
            engineScope.launch {
                kotlinx.coroutines.delay(460L)
                if (activeTaskId == current.taskId && _activeTaskFlow.value == null && lastTaskEndedByCancel) {
                    repostTerminalNotificationIfNeeded(appContext)
                }
            }
        }

        ApkPackageInstaller.showToastOnMain(appContext, "Cancelled: $cleanName")
    }

    fun submitFileConflictDecision(action: FileConflictAction, applyToAll: Boolean) {
        synchronized(conflictLock) {
            if (applyToAll) {
                taskWideConflictAction = action
            }
            pendingConflictDecision = action
            _pendingConflictFlow.value = null
            conflictLock.notifyAll()
        }
    }

    /**
     * Checks if [candidateDest] already exists. If it does, pauses the current task thread and
     * presents a compact conflict dialog ("Copy and replace", "Keep both", "Skip") unless the user
     * already chose "Apply to all" for the running task.
     *
     * Returns:
     * - [candidateDest] when there is no conflict OR user chose "Copy and replace"
     * - A unique non-colliding sibling file when user chose "Keep both"
     * - `null` when user chose "Skip"
     */
    fun resolveConflictTargetFile(
        context: Context,
        candidateDest: File,
        operationLabel: String,
        isDirectory: Boolean = false,
        incomingSizeBytes: Long = -1L,
        allowApplyToAll: Boolean = true,
        taskId: Int = activeTaskId
    ): File? {
        checkPauseOrCancel(taskId)
        if (!AndroidDataObbBridge.existsUniversal(context, candidateDest)) {
            return candidateDest
        }

        val action: FileConflictAction = taskWideConflictAction ?: run {
            val existingSize = if (!isDirectory && !AndroidDataObbBridge.isDirectoryUniversal(context, candidateDest)) {
                runCatching { candidateDest.length() }.getOrDefault(-1L)
            } else {
                -1L
            }
            val prompt = FileConflictPromptState(
                taskId = taskId,
                operationLabel = operationLabel,
                fileName = candidateDest.name,
                destinationPath = candidateDest.parentFile?.absolutePath ?: candidateDest.absolutePath,
                isDirectory = isDirectory || AndroidDataObbBridge.isDirectoryUniversal(context, candidateDest),
                existingSizeBytes = existingSize,
                incomingSizeBytes = incomingSizeBytes,
                allowApplyToAll = allowApplyToAll
            )
            synchronized(conflictLock) {
                pendingConflictDecision = null
                _pendingConflictFlow.value = prompt
                try {
                    while (pendingConflictDecision == null && (taskId <= 0 || cancelledTaskId != taskId)) {
                        if (Thread.currentThread().isInterrupted) {
                            throw CancellationException("File Manager task interrupted during conflict prompt")
                        }
                        try {
                            conflictLock.wait(200L)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw CancellationException("File Manager task interrupted during conflict prompt")
                        }
                    }
                    if (taskId > 0 && cancelledTaskId == taskId) {
                        throw CancellationException("File Manager task cancelled by user")
                    }
                    val chosen = pendingConflictDecision ?: FileConflictAction.SKIP
                    pendingConflictDecision = null
                    chosen
                } finally {
                    if (_pendingConflictFlow.value?.taskId == taskId) {
                        _pendingConflictFlow.value = null
                    }
                }
            }
        }

        checkPauseOrCancel(taskId)
        return when (action) {
            FileConflictAction.SKIP -> null
            FileConflictAction.KEEP_BOTH -> resolveUniqueDestinationFile(
                context = context,
                dir = candidateDest.parentFile ?: File("/"),
                desiredName = candidateDest.name
            )
            FileConflictAction.COPY_AND_REPLACE -> candidateDest
        }
    }

    private fun prepareNewTask(taskId: Int) {
        activeTaskId = taskId
        isTaskPaused = false
        cancelledTaskId = -1
        taskWideConflictAction = null
        pendingConflictDecision = null
        _pendingConflictFlow.value = null
        lastTaskEndedByCancel = false
        lastTerminalTaskState = null
        lastSystemNotifPostElapsedMs = 0L
        lastSystemNotifPercent = -1
        lastSystemNotifItems = -1
        synchronized(pauseLock) {
            pauseLock.notifyAll()
        }
        synchronized(conflictLock) {
            conflictLock.notifyAll()
        }
    }

    fun wasLastTaskCancelled(): Boolean = lastTaskEndedByCancel

    private fun handleTaskCancelled(context: Context, taskId: Int) {
        val appContext = context.applicationContext
        val previous = _activeTaskFlow.value?.takeIf { it.taskId == taskId }
        val cleanName = previous?.title?.removePrefix("File Manager • ")?.ifBlank { "Task" } ?: "Task"
        val detailFile = previous?.currentFileName?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty()
        val cancelSummary = "Cancelled by user$detailFile"

        lastTaskEndedByCancel = true
        lastTerminalTaskState = FileManagerTerminalState(
            taskId = taskId,
            title = "Cancelled • $cleanName",
            summaryMessage = cancelSummary,
            status = FileManagerTerminalStatus.CANCELLED
        )
        taskWideConflictAction = null
        pendingConflictDecision = null
        if (_pendingConflictFlow.value?.taskId == taskId) {
            _pendingConflictFlow.value = null
        }
        if (_activeTaskFlow.value?.taskId == taskId) {
            _activeTaskFlow.value = null
        }
        if (activeTaskId == taskId) {
            isTaskPaused = false
        }
        synchronized(conflictLock) {
            conflictLock.notifyAll()
        }
        triggerDirectoryRefresh()
        ensureChannel(appContext)
        if (hasNotificationPermission(appContext)) {
            runCatching {
                val nm = NotificationManagerCompat.from(appContext)
                nm.cancel(NOTIFICATION_ID_FILE_MANAGER_COMPLETED)
                nm.notify(
                    NOTIFICATION_ID_FILE_MANAGER_TASK,
                    buildCompletedTaskNotification(
                        context = appContext,
                        title = "Cancelled • $cleanName",
                        summaryMessage = cancelSummary,
                        status = FileManagerTerminalStatus.CANCELLED
                    )
                )
            }
            engineScope.launch {
                kotlinx.coroutines.delay(460L)
                if (activeTaskId == taskId && _activeTaskFlow.value == null && lastTaskEndedByCancel) {
                    repostTerminalNotificationIfNeeded(appContext)
                }
            }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID_FILE_MANAGER_TASKS) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID_FILE_MANAGER_TASKS,
                    "Dragon Archiver Tasks",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent Material 3 live progress notifications for Dragon Archiver tasks (Copy, Move, Extract, Compress, Auto-Sign, Delete)"
                    setShowBadge(true)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun buildOpenFileManagerPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_FILE_MANAGER_TAB, true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context.applicationContext, 7740, intent, flags)
    }

    private fun buildTaskActionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(context.applicationContext, DownloadNotificationReceiver::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context.applicationContext, requestCode, intent, flags)
    }

    /**
     * Builds the separate persistent File Manager task notification styled identically
     * to the main app's Material 3 notification panel (`NotificationPanelStyle.MATERIAL_3_UI`),
     * complete with live Pause / Resume and Cancel controls.
     */
    fun buildRunningTaskNotification(
        context: Context,
        state: FileManagerRunningTaskState? = _activeTaskFlow.value
    ): Notification {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val current = state ?: FileManagerRunningTaskState(
            taskId = 0,
            title = "Dragon Archiver",
            subtitle = "Processing files in background...",
            currentFileName = "",
            completedItems = 0,
            totalItems = 1,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = true,
            isPaused = false
        )

        val pctClamped = current.progressPercent.coerceIn(0, 100)
        val isPaused = current.isPaused
        val cleanTitle = current.title.removePrefix("File Manager • ").ifBlank { current.title }

        val itemCounterText = if (current.totalItems > 0) {
            "${current.completedItems}/${current.totalItems} item(s)"
        } else ""

        val byteCounterText = if (current.totalBytes > 0L) {
            "${formatBytes(current.processedBytes)} / ${formatBytes(current.totalBytes)}"
        } else if (current.processedBytes > 0L) {
            formatBytes(current.processedBytes)
        } else ""

        val telemetryLine = buildList {
            if (isPaused) add("PAUSED")
            if (itemCounterText.isNotBlank()) add(itemCounterText)
            if (byteCounterText.isNotBlank()) add(byteCounterText)
            val detailName = current.currentFileName.ifBlank { current.subtitle }
            if (detailName.isNotBlank()) add(detailName)
        }.joinToString(" • ").ifBlank { current.subtitle }

        val collapsedSub = buildString {
            if (isPaused) {
                append(if (current.isIndeterminate) "PAUSED" else "PAUSED • $pctClamped%")
            } else if (!current.isIndeterminate) {
                append("$pctClamped%")
            } else {
                append("RUNNING")
            }
            val rightDetail = buildList {
                if (byteCounterText.isNotBlank()) add(byteCounterText)
                else if (itemCounterText.isNotBlank()) add(itemCounterText)
                if (current.currentFileName.isNotBlank()) add(current.currentFileName)
            }.joinToString(" • ")
            if (rightDetail.isNotBlank()) {
                append(" • ").append(rightDetail)
            }
        }

        val badgeText = when {
            isPaused && !current.isIndeterminate -> "PAUSED • $pctClamped%"
            isPaused -> "PAUSED"
            current.isIndeterminate -> "RUNNING"
            else -> "$pctClamped%"
        }

        // Main App Material 3 Notification Panel Palette (matching DownloadNotificationController MATERIAL_3_UI)
        val m3Primary = Color.parseColor("#9BBDF9")
        val m3Secondary = Color.parseColor("#8CD3AC")
        val m3PausedAmber = Color.parseColor("#FBBF24")
        val titleColor = Color.parseColor("#DDE1EB")
        val bodyColor = Color.parseColor("#A8B0C2")

        val cardRes = R.drawable.bg_m3_notif_card
        val badgeRes = R.drawable.bg_m3_notif_badge
        val btnRes = R.drawable.bg_m3_notif_btn

        val primaryActionLabel = if (isPaused) "▶ RESUME" else "⏸ PAUSE"
        val primaryActionIntent = buildTaskActionPendingIntent(
            context = appContext,
            action = if (isPaused) {
                DownloadNotificationReceiver.ACTION_FM_RESUME_TASK
            } else {
                DownloadNotificationReceiver.ACTION_FM_PAUSE_TASK
            },
            requestCode = 7751
        )
        val cancelActionIntent = buildTaskActionPendingIntent(
            context = appContext,
            action = DownloadNotificationReceiver.ACTION_FM_CANCEL_TASK,
            requestCode = 7752
        )

        val collapsedView = RemoteViews(appContext.packageName, R.layout.notification_download_collapsed)
        val expandedView = RemoteViews(appContext.packageName, R.layout.notification_download_expanded)

        // Populate Collapsed Material 3 View
        collapsedView.setInt(R.id.notif_dl_collapsed_root, "setBackgroundResource", cardRes)
        collapsedView.setTextViewText(R.id.tv_dl_collapsed_title, cleanTitle)
        collapsedView.setTextColor(R.id.tv_dl_collapsed_title, titleColor)
        collapsedView.setProgressBar(R.id.pb_dl_collapsed, 100, pctClamped, current.isIndeterminate && !isPaused)
        collapsedView.setTextViewText(R.id.tv_dl_collapsed_sub, collapsedSub)
        collapsedView.setTextColor(R.id.tv_dl_collapsed_sub, bodyColor)

        collapsedView.setInt(R.id.btn_dl_collapsed_primary, "setBackgroundResource", btnRes)
        collapsedView.setTextViewText(R.id.btn_dl_collapsed_primary, primaryActionLabel)
        collapsedView.setTextColor(R.id.btn_dl_collapsed_primary, if (isPaused) m3PausedAmber else m3Secondary)
        collapsedView.setOnClickPendingIntent(R.id.btn_dl_collapsed_primary, primaryActionIntent)

        collapsedView.setInt(R.id.btn_dl_collapsed_cancel, "setBackgroundResource", btnRes)
        collapsedView.setTextViewText(R.id.btn_dl_collapsed_cancel, "✕")
        collapsedView.setTextColor(R.id.btn_dl_collapsed_cancel, m3Primary)
        collapsedView.setOnClickPendingIntent(R.id.btn_dl_collapsed_cancel, cancelActionIntent)

        // Populate Expanded Material 3 View
        expandedView.setInt(R.id.notif_dl_expanded_root, "setBackgroundResource", cardRes)
        expandedView.setTextViewText(R.id.tv_dl_filename, cleanTitle)
        expandedView.setTextColor(R.id.tv_dl_filename, titleColor)
        expandedView.setInt(R.id.tv_dl_hud_badge, "setBackgroundResource", badgeRes)
        expandedView.setTextViewText(R.id.tv_dl_hud_badge, badgeText)
        expandedView.setTextColor(R.id.tv_dl_hud_badge, if (isPaused) m3PausedAmber else m3Secondary)
        expandedView.setProgressBar(R.id.pb_dl_expanded, 100, pctClamped, current.isIndeterminate && !isPaused)
        expandedView.setTextViewText(R.id.tv_dl_telemetry, telemetryLine)
        expandedView.setTextColor(R.id.tv_dl_telemetry, bodyColor)

        expandedView.setInt(R.id.btn_dl_expanded_primary, "setBackgroundResource", btnRes)
        expandedView.setTextViewText(R.id.btn_dl_expanded_primary, primaryActionLabel)
        expandedView.setTextColor(R.id.btn_dl_expanded_primary, if (isPaused) m3PausedAmber else m3Secondary)
        expandedView.setOnClickPendingIntent(R.id.btn_dl_expanded_primary, primaryActionIntent)

        // Hide unused middle secondary button so Pause/Resume & Cancel span cleanly 50/50
        expandedView.setViewVisibility(R.id.btn_dl_expanded_secondary, View.GONE)

        expandedView.setInt(R.id.btn_dl_expanded_cancel, "setBackgroundResource", btnRes)
        expandedView.setTextViewText(R.id.btn_dl_expanded_cancel, "✕ CANCEL")
        expandedView.setTextColor(R.id.btn_dl_expanded_cancel, m3Primary)
        expandedView.setOnClickPendingIntent(R.id.btn_dl_expanded_cancel, cancelActionIntent)

        return NotificationCompat.Builder(appContext, CHANNEL_ID_FILE_MANAGER_TASKS)
            .setSmallIcon(R.drawable.ic_notif_download)
            .setColor(m3Primary)
            .setColorized(false)
            .setGroup(GROUP_FILE_MANAGER_TASK_HUD)
            .setSortKey("03_file_manager_hud")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setContentTitle(cleanTitle)
            .setContentText(telemetryLine)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(buildOpenFileManagerPendingIntent(appContext))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Builds the terminal (Completed 100%, Cancelled 0%, or Failed 0%) state inside the SAME single
     * upper Material 3 notification panel (`NOTIFICATION_ID_FILE_MANAGER_TASK`), hiding Pause/Cancel controls
     * and making it swipe-dismissible.
     */
    private fun buildCompletedTaskNotification(
        context: Context,
        title: String,
        summaryMessage: String,
        status: FileManagerTerminalStatus
    ): Notification {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val cleanTitle = title.removePrefix("File Manager • ").ifBlank { title }
        val pctFinal = if (status == FileManagerTerminalStatus.COMPLETED) 100 else 0
        val badgeText = when (status) {
            FileManagerTerminalStatus.COMPLETED -> "100% ✓"
            FileManagerTerminalStatus.CANCELLED -> "CANCELLED"
            FileManagerTerminalStatus.FAILED -> "FAILED"
        }

        val m3Primary = Color.parseColor("#9BBDF9")
        val m3Success = Color.parseColor("#8CD3AC")
        val m3Cancelled = Color.parseColor("#FBBF24")
        val m3Error = Color.parseColor("#EF4444")
        val titleColor = Color.parseColor("#DDE1EB")
        val bodyColor = Color.parseColor("#A8B0C2")
        val accentColor = when (status) {
            FileManagerTerminalStatus.COMPLETED -> m3Success
            FileManagerTerminalStatus.CANCELLED -> m3Cancelled
            FileManagerTerminalStatus.FAILED -> m3Error
        }
        val formattedSummary = when (status) {
            FileManagerTerminalStatus.COMPLETED -> summaryMessage
            FileManagerTerminalStatus.CANCELLED -> if (
                summaryMessage.startsWith("Cancel", ignoreCase = true)
            ) summaryMessage else "Cancelled • $summaryMessage"
            FileManagerTerminalStatus.FAILED -> if (
                summaryMessage.startsWith("Fail", ignoreCase = true) ||
                summaryMessage.startsWith("Error", ignoreCase = true) ||
                summaryMessage.startsWith("Could not", ignoreCase = true)
            ) summaryMessage else "Failed • $summaryMessage"
        }

        val cardRes = R.drawable.bg_m3_notif_card
        val badgeRes = R.drawable.bg_m3_notif_badge

        val collapsedView = RemoteViews(appContext.packageName, R.layout.notification_download_collapsed)
        val expandedView = RemoteViews(appContext.packageName, R.layout.notification_download_expanded)

        // Collapsed view: 100% only for COMPLETED, 0% for CANCELLED / FAILED + hide action buttons
        collapsedView.setInt(R.id.notif_dl_collapsed_root, "setBackgroundResource", cardRes)
        collapsedView.setTextViewText(R.id.tv_dl_collapsed_title, cleanTitle)
        collapsedView.setTextColor(R.id.tv_dl_collapsed_title, titleColor)
        collapsedView.setProgressBar(R.id.pb_dl_collapsed, 100, pctFinal, false)
        collapsedView.setTextViewText(R.id.tv_dl_collapsed_sub, formattedSummary)
        collapsedView.setTextColor(R.id.tv_dl_collapsed_sub, if (status == FileManagerTerminalStatus.COMPLETED) bodyColor else accentColor)
        collapsedView.setViewVisibility(R.id.btn_dl_collapsed_primary, View.GONE)
        collapsedView.setViewVisibility(R.id.btn_dl_collapsed_cancel, View.GONE)

        // Expanded view: accurate badge + bar + summary + hide action buttons
        expandedView.setInt(R.id.notif_dl_expanded_root, "setBackgroundResource", cardRes)
        expandedView.setTextViewText(R.id.tv_dl_filename, cleanTitle)
        expandedView.setTextColor(R.id.tv_dl_filename, titleColor)
        expandedView.setInt(R.id.tv_dl_hud_badge, "setBackgroundResource", badgeRes)
        expandedView.setTextViewText(R.id.tv_dl_hud_badge, badgeText)
        expandedView.setTextColor(R.id.tv_dl_hud_badge, accentColor)
        expandedView.setProgressBar(R.id.pb_dl_expanded, 100, pctFinal, false)
        expandedView.setTextViewText(R.id.tv_dl_telemetry, formattedSummary)
        expandedView.setTextColor(R.id.tv_dl_telemetry, bodyColor)
        expandedView.setViewVisibility(R.id.btn_dl_expanded_primary, View.GONE)
        expandedView.setViewVisibility(R.id.btn_dl_expanded_secondary, View.GONE)
        expandedView.setViewVisibility(R.id.btn_dl_expanded_cancel, View.GONE)

        val smallIconRes = if (status == FileManagerTerminalStatus.FAILED) {
            android.R.drawable.stat_notify_error
        } else {
            R.drawable.ic_notif_download
        }
        val tintColor = when (status) {
            FileManagerTerminalStatus.COMPLETED -> m3Primary
            FileManagerTerminalStatus.CANCELLED -> m3Cancelled
            FileManagerTerminalStatus.FAILED -> m3Error
        }

        return NotificationCompat.Builder(appContext, CHANNEL_ID_FILE_MANAGER_TASKS)
            .setSmallIcon(smallIconRes)
            .setColor(tintColor)
            .setColorized(false)
            .setGroup(GROUP_FILE_MANAGER_TASK_HUD)
            .setSortKey("03_file_manager_hud")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setContentTitle(cleanTitle)
            .setContentText(formattedSummary)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(buildOpenFileManagerPendingIntent(appContext))
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun postOrUpdateRunningNotification(
        context: Context,
        state: FileManagerRunningTaskState,
        forceUpdate: Boolean = false
    ) {
        if (!forceUpdate && cancelledTaskId == state.taskId) return
        val syncedState = state.copy(isPaused = if (forceUpdate) state.isPaused else isTaskPaused)
        val appContext = context.applicationContext
        // Always update Compose in-app progress flow immediately (smooth 60fps UI)
        _activeTaskFlow.value = syncedState

        // Centralized throttle keeps SystemUI NotificationManager strictly under Android's
        // per-package rate limit so intermediate and final progress updates are never dropped.
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val pct = syncedState.progressPercent.coerceIn(0, 100)
        val isFirstPost = lastSystemNotifPostElapsedMs == 0L
        val elapsedEnough = (nowElapsed - lastSystemNotifPostElapsedMs) >= MIN_SYSTEM_NOTIF_INTERVAL_MS
        val changed = pct != lastSystemNotifPercent || syncedState.completedItems != lastSystemNotifItems
        if (!forceUpdate && !isFirstPost && (!elapsedEnough || !changed)) {
            return
        }

        lastSystemNotifPostElapsedMs = nowElapsed
        lastSystemNotifPercent = pct
        lastSystemNotifItems = syncedState.completedItems

        ensureChannel(appContext)
        if (hasNotificationPermission(appContext)) {
            runCatching {
                NotificationManagerCompat.from(appContext)
                    .notify(NOTIFICATION_ID_FILE_MANAGER_TASK, buildRunningTaskNotification(appContext, syncedState))
            }
        }
    }

    /**
     * Re-posts the terminal notification card (Completed 100%, Cancelled 0%, or Failed 0%) on
     * `NOTIFICATION_ID_FILE_MANAGER_TASK` right after `DragonDownloadService` detaches from foreground
     * so Android SystemUI never freezes on an intermediate progress frame.
     */
    fun repostTerminalNotificationIfNeeded(context: Context) {
        val terminal = lastTerminalTaskState ?: return
        if (_activeTaskFlow.value != null) return
        val appContext = context.applicationContext
        if (hasNotificationPermission(appContext)) {
            runCatching {
                val nm = NotificationManagerCompat.from(appContext)
                nm.cancel(NOTIFICATION_ID_FILE_MANAGER_COMPLETED)
                nm.notify(
                    NOTIFICATION_ID_FILE_MANAGER_TASK,
                    buildCompletedTaskNotification(
                        context = appContext,
                        title = terminal.title,
                        summaryMessage = terminal.summaryMessage,
                        status = terminal.status
                    )
                )
            }
        }
    }

    private fun completeTaskNotification(
        context: Context,
        taskId: Int,
        title: String,
        summaryMessage: String,
        isSuccess: Boolean = true
    ) {
        completeTaskNotificationWithStatus(
            context = context,
            taskId = taskId,
            title = title,
            summaryMessage = summaryMessage,
            status = if (isSuccess) FileManagerTerminalStatus.COMPLETED else FileManagerTerminalStatus.FAILED
        )
    }

    private fun completeTaskNotificationWithStatus(
        context: Context,
        taskId: Int,
        title: String,
        summaryMessage: String,
        status: FileManagerTerminalStatus
    ) {
        if (cancelledTaskId == taskId && status != FileManagerTerminalStatus.CANCELLED) {
            handleTaskCancelled(context, taskId)
            return
        }
        val appContext = context.applicationContext
        lastTaskEndedByCancel = (status == FileManagerTerminalStatus.CANCELLED)
        lastTerminalTaskState = FileManagerTerminalState(taskId, title, summaryMessage, status)

        if (_activeTaskFlow.value?.taskId == taskId) {
            _activeTaskFlow.value = null
        }
        if (activeTaskId == taskId) {
            isTaskPaused = false
        }
        triggerDirectoryRefresh()
        ensureChannel(appContext)

        if (hasNotificationPermission(appContext)) {
            val terminalCard = buildCompletedTaskNotification(appContext, title, summaryMessage, status)
            runCatching {
                val nm = NotificationManagerCompat.from(appContext)
                nm.cancel(NOTIFICATION_ID_FILE_MANAGER_COMPLETED)
                nm.notify(NOTIFICATION_ID_FILE_MANAGER_TASK, terminalCard)
            }
            // Safety re-post after Android's rate-limit & FGS-detach window so the final state ALWAYS sticks
            engineScope.launch {
                kotlinx.coroutines.delay(460L)
                if (activeTaskId == taskId && _activeTaskFlow.value == null) {
                    repostTerminalNotificationIfNeeded(appContext)
                }
            }
        }
    }

    fun allocatePackageInstallTaskId(): Int = taskCounter.getAndIncrement()

    fun beginPackageInstallTask(
        context: Context,
        taskId: Int,
        title: String,
        subtitle: String,
        currentFileName: String,
        totalBytes: Long = 0L,
        job: Job? = null
    ) {
        val appContext = context.applicationContext
        prepareNewTask(taskId)
        activeTaskJob = job
        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = title,
            subtitle = subtitle,
            currentFileName = currentFileName,
            completedItems = 0,
            totalItems = 1,
            processedBytes = 0L,
            totalBytes = totalBytes.coerceAtLeast(0L),
            progressPercent = 12,
            isIndeterminate = false
        )
        postOrUpdateRunningNotification(appContext, initialState, forceUpdate = true)
        DragonDownloadService.startForegroundDownloads(appContext)
    }

    fun attachPackageInstallJob(taskId: Int, job: Job?) {
        if (activeTaskId == taskId) {
            activeTaskJob = job
        }
    }

    fun updatePackageInstallProgress(
        context: Context,
        taskId: Int,
        title: String,
        subtitle: String,
        currentFileName: String,
        progressPercent: Int,
        isIndeterminate: Boolean = false
    ) {
        if (taskId <= 0 || cancelledTaskId == taskId) return
        val appContext = context.applicationContext
        val current = _activeTaskFlow.value
        val runningState = FileManagerRunningTaskState(
            taskId = taskId,
            title = title,
            subtitle = subtitle,
            currentFileName = currentFileName,
            completedItems = 0,
            totalItems = 1,
            processedBytes = 0L,
            totalBytes = current?.totalBytes ?: 0L,
            progressPercent = progressPercent.coerceIn(0, 99),
            isIndeterminate = isIndeterminate,
            isPaused = false,
            startedAtMs = current?.startedAtMs ?: System.currentTimeMillis()
        )
        postOrUpdateRunningNotification(appContext, runningState, forceUpdate = true)
    }

    fun finishPackageInstallTask(
        context: Context,
        taskId: Int,
        title: String,
        summaryMessage: String,
        status: FileManagerTerminalStatus
    ) {
        val resolvedTaskId = if (taskId > 0) taskId else activeTaskId.takeIf { it > 0 } ?: taskCounter.getAndIncrement()
        if (activeTaskId != resolvedTaskId && _activeTaskFlow.value == null) {
            activeTaskId = resolvedTaskId
        }
        completeTaskNotificationWithStatus(
            context = context,
            taskId = resolvedTaskId,
            title = title,
            summaryMessage = summaryMessage,
            status = status
        )
    }

    /**
     * Runs a Copy or Move (Cut) paste task in the background application scope with a persistent live notification.
     * Supports both regular storage files/folders AND direct copy/paste of internal entries from inside
     * compressed archives without decompressing the entire archive.
     */
    fun launchClipboardPasteTask(
        context: Context,
        clip: FileManagerClipboardState,
        targetDir: File,
        onCompleted: (Int) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)

        // Check if this clipboard state holds entries copied directly from inside a compressed archive
        val sourceArchive = clip.sourceArchiveFile
        if (sourceArchive != null && clip.archiveEntryPaths.isNotEmpty()) {
            val totalItems = clip.archiveEntryPaths.size.coerceAtLeast(1)
            val initialState = FileManagerRunningTaskState(
                taskId = taskId,
                title = "File Manager • Copying from ${sourceArchive.name}",
                subtitle = "Direct copy to ${targetDir.name}",
                currentFileName = clip.archiveEntryPaths.firstOrNull()?.substringAfterLast('/').orEmpty(),
                completedItems = 0,
                totalItems = totalItems,
                processedBytes = 0L,
                totalBytes = 0L,
                progressPercent = 0,
                isIndeterminate = true
            )
            postOrUpdateRunningNotification(appContext, initialState)
            DragonDownloadService.startForegroundDownloads(appContext)

            activeTaskJob = engineScope.launch {
                try {
                    checkPauseOrCancel(taskId)
                    val estimatedTotalBytes = UniversalArchiveEngine.estimateSelectedEntriesTotalBytes(
                        context = appContext,
                        archiveFile = sourceArchive,
                        selectedEntryPaths = clip.archiveEntryPaths
                    )
                    var bytesCopied = 0L
                    var skippedCount = 0
                    var lastNotifUpdateMs = 0L
                    val count = UniversalArchiveEngine.copyEntriesFromArchiveDirectToDirectory(
                        context = appContext,
                        archiveFile = sourceArchive,
                        selectedEntryPaths = clip.archiveEntryPaths,
                        targetDir = targetDir,
                        onSkipped = { skippedCount++ }
                    ) { deltaBytes, activeFileName ->
                        checkPauseOrCancel(taskId)
                        bytesCopied += deltaBytes
                        val now = System.currentTimeMillis()
                        if (now - lastNotifUpdateMs >= 200L) {
                            lastNotifUpdateMs = now
                            val effTotal = maxOf(estimatedTotalBytes, bytesCopied)
                            val pct = if (effTotal > 0L) {
                                ((bytesCopied * 100L) / effTotal).toInt().coerceIn(0, 99)
                            } else 50
                            postOrUpdateRunningNotification(
                                appContext,
                                initialState.copy(
                                    currentFileName = activeFileName,
                                    processedBytes = bytesCopied,
                                    totalBytes = effTotal,
                                    progressPercent = pct,
                                    isIndeterminate = effTotal <= 0L
                                )
                            )
                        }
                    }
                    checkPauseOrCancel(taskId)
                    if (count > 0 || skippedCount > 0) {
                        savedClipboard = null
                        val doneMsg = when {
                            count > 0 && skippedCount > 0 ->
                                "Copied $count file(s) (${formatBytes(bytesCopied)}), skipped $skippedCount existing item(s)"
                            count > 0 ->
                                "Copied $count file(s) (${formatBytes(bytesCopied)}) directly from ${sourceArchive.name} to ${targetDir.name}"
                            else ->
                                "Skipped $skippedCount existing item(s) in ${targetDir.name}"
                        }
                        completeTaskNotification(
                            context = appContext,
                            taskId = taskId,
                            title = "File Manager • Direct Archive Copy Complete",
                            summaryMessage = doneMsg,
                            isSuccess = true
                        )
                        ApkPackageInstaller.showToastOnMain(appContext, doneMsg)
                        onCompleted(count)
                    } else {
                        val failMsg = "Could not copy selected items from ${sourceArchive.name} to ${targetDir.name}"
                        completeTaskNotification(
                            context = appContext,
                            taskId = taskId,
                            title = "File Manager • Archive Copy Failed",
                            summaryMessage = failMsg,
                            isSuccess = false
                        )
                        ApkPackageInstaller.showToastOnMain(appContext, failMsg)
                        onCompleted(0)
                    }
                } catch (_: CancellationException) {
                    handleTaskCancelled(appContext, taskId)
                    onCompleted(0)
                }
            }
            return
        }

        val isCopy = clip.operation == ClipboardTransferOperation.COPY
        val opVerb = if (isCopy) "Copying" else "Moving"
        val totalItems = clip.files.size.coerceAtLeast(1)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • $opVerb $totalItems item(s)",
            subtitle = "Destination: ${targetDir.name}",
            currentFileName = clip.files.firstOrNull()?.name.orEmpty(),
            completedItems = 0,
            totalItems = totalItems,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = true
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                checkPauseOrCancel(taskId)
                val totalEstimatedBytes = clip.files.sumOf {
                    checkPauseOrCancel(taskId)
                    AndroidDataObbBridge.estimateTotalBytesUniversal(appContext, it)
                }.coerceAtLeast(0L)

                var bytesCopiedSoFar = 0L
                var lastNotifUpdateMs = 0L
                var completedCount = 0
                var skippedCount = 0

                AndroidDataObbBridge.createDirectory(appContext, targetDir)

                clip.files.forEachIndexed { index, src ->
                    checkPauseOrCancel(taskId)
                    if (!AndroidDataObbBridge.existsUniversal(appContext, src)) return@forEachIndexed
                    val srcIsDir = AndroidDataObbBridge.isDirectoryUniversal(appContext, src)
                    val srcLen = if (srcIsDir) -1L else runCatching { src.length() }.getOrDefault(-1L)
                    val rawDest = File(targetDir, src.name)
                    val dest = resolveConflictTargetFile(
                        context = appContext,
                        candidateDest = rawDest,
                        operationLabel = if (isCopy) "Copy" else "Move",
                        isDirectory = srcIsDir,
                        incomingSizeBytes = srcLen,
                        allowApplyToAll = totalItems > 1,
                        taskId = taskId
                    )
                    if (dest == null) {
                        skippedCount++
                        return@forEachIndexed
                    }

                    val startPct = if (totalEstimatedBytes > 0L) {
                        ((bytesCopiedSoFar * 100L) / totalEstimatedBytes).toInt().coerceIn(0, 99)
                    } else {
                        ((index * 100) / totalItems).coerceIn(0, 99)
                    }
                    postOrUpdateRunningNotification(
                        appContext,
                        initialState.copy(
                            title = "File Manager • $opVerb (${index + 1}/$totalItems)",
                            subtitle = "To: ${targetDir.name}",
                            currentFileName = src.name,
                            completedItems = index,
                            totalItems = totalItems,
                            processedBytes = bytesCopiedSoFar,
                            totalBytes = totalEstimatedBytes,
                            progressPercent = startPct,
                            isIndeterminate = totalEstimatedBytes <= 0L && totalItems <= 1
                        )
                    )

                    val ok = try {
                        val isSameExactPath = runCatching { src.canonicalPath == dest.canonicalPath }
                            .getOrDefault(src.absolutePath == dest.absolutePath)
                        if (isSameExactPath) {
                            // User chose "Copy and replace" onto the exact same file path; preserve intact without truncating
                            if (srcLen > 0L) bytesCopiedSoFar += srcLen
                            true
                        } else {
                            // If replacing an entry of opposite type (file vs directory), remove old entry first
                            if (AndroidDataObbBridge.existsUniversal(appContext, dest)) {
                                val destIsDir = AndroidDataObbBridge.isDirectoryUniversal(appContext, dest)
                                if (destIsDir != srcIsDir) {
                                    AndroidDataObbBridge.deleteFileOrDirectory(appContext, dest)
                                }
                            }
                            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                                context = appContext,
                                source = src,
                                dest = dest,
                                isDirectoryHint = srcIsDir
                            ) { deltaBytes, activeFileName ->
                                checkPauseOrCancel(taskId)
                                bytesCopiedSoFar += deltaBytes
                                val now = System.currentTimeMillis()
                                if (now - lastNotifUpdateMs >= 220L) {
                                    lastNotifUpdateMs = now
                                    val pct = if (totalEstimatedBytes > 0L) {
                                        ((bytesCopiedSoFar * 100L) / totalEstimatedBytes).toInt().coerceIn(0, 99)
                                    } else {
                                        (((index * 100) + 50) / totalItems).coerceIn(0, 99)
                                    }
                                    postOrUpdateRunningNotification(
                                        appContext,
                                        initialState.copy(
                                            title = "File Manager • $opVerb (${index + 1}/$totalItems)",
                                            subtitle = "To: ${targetDir.name}",
                                            currentFileName = activeFileName,
                                            completedItems = index,
                                            totalItems = totalItems,
                                            processedBytes = bytesCopiedSoFar,
                                            totalBytes = totalEstimatedBytes,
                                            progressPercent = pct,
                                            isIndeterminate = false
                                        )
                                    )
                                }
                            }
                            checkPauseOrCancel(taskId)
                            if (copied && clip.operation == ClipboardTransferOperation.CUT && src.absolutePath != dest.absolutePath) {
                                AndroidDataObbBridge.deleteFileOrDirectory(appContext, src)
                            }
                            copied
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        false
                    }

                    if (ok) {
                        completedCount++
                        triggerDirectoryRefresh()
                    }
                }

                checkPauseOrCancel(taskId)
                if (completedCount > 0 || skippedCount > 0) {
                    savedClipboard = null
                    val verbPast = if (isCopy) "Copied" else "Moved"
                    val doneMsg = when {
                        completedCount > 0 && skippedCount > 0 ->
                            "$verbPast $completedCount item(s) (${formatBytes(bytesCopiedSoFar)}), skipped $skippedCount"
                        completedCount > 0 ->
                            "$verbPast $completedCount item(s) (${formatBytes(bytesCopiedSoFar)}) to ${targetDir.name}"
                        else ->
                            "Skipped $skippedCount existing item(s) in ${targetDir.name}"
                    }
                    completeTaskNotification(
                        context = appContext,
                        taskId = taskId,
                        title = "File Manager • ${if (isCopy) "Copy" else "Move"} Completed",
                        summaryMessage = doneMsg,
                        isSuccess = true
                    )
                    ApkPackageInstaller.showToastOnMain(appContext, doneMsg)
                    onCompleted(completedCount)
                } else {
                    completeTaskNotification(
                        context = appContext,
                        taskId = taskId,
                        title = "File Manager • ${if (isCopy) "Copy" else "Move"} Failed",
                        summaryMessage = "Could not write to ${targetDir.name}. Check folder or Root permissions.",
                        isSuccess = false
                    )
                    if (AndroidDataObbBridge.isAndroidDataOrObbPath(targetDir.absolutePath)) {
                        ApkPackageInstaller.showToastOnMain(
                            appContext,
                            "Enable Root option in 3-dot menu or grant Root permission via Root Manager for Android/data & obb"
                        )
                    } else {
                        ApkPackageInstaller.showToastOnMain(appContext, "Could not paste into ${targetDir.name}")
                    }
                    onCompleted(0)
                }
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
                onCompleted(0)
            } catch (e: Exception) {
                completeTaskNotification(
                    context = appContext,
                    taskId = taskId,
                    title = "File Manager • Task Error",
                    summaryMessage = e.message ?: "Operation failed",
                    isSuccess = false
                )
            }
        }
    }

    /**
     * Runs a File/Folder Duplicate task in the background application scope with a persistent live notification.
     */
    fun launchDuplicateTask(
        context: Context,
        sourceFile: File
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Duplicating",
            subtitle = sourceFile.name,
            currentFileName = sourceFile.name,
            completedItems = 0,
            totalItems = 1,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = true
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                checkPauseOrCancel(taskId)
                val totalBytes = AndroidDataObbBridge.estimateTotalBytesUniversal(appContext, sourceFile)
                var copiedBytes = 0L
                var lastUpdateMs = 0L

                val dup = try {
                    if (!AndroidDataObbBridge.existsUniversal(appContext, sourceFile)) {
                        null
                    } else {
                        val parent = sourceFile.parentFile
                        if (parent == null) {
                            null
                        } else {
                            val isDir = AndroidDataObbBridge.isDirectoryUniversal(appContext, sourceFile)
                            val base = sourceFile.nameWithoutExtension.ifBlank { sourceFile.name }
                            val ext = sourceFile.extension.takeIf { it.isNotBlank() && !isDir }?.let { ".$it" }.orEmpty()
                            val dest = resolveUniqueDestinationFile(appContext, parent, "${base}_copy$ext")

                            val ok = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                                context = appContext,
                                source = sourceFile,
                                dest = dest,
                                isDirectoryHint = isDir
                            ) { delta, activeName ->
                                checkPauseOrCancel(taskId)
                                copiedBytes += delta
                                val now = System.currentTimeMillis()
                                if (now - lastUpdateMs >= 220L) {
                                    lastUpdateMs = now
                                    val pct = if (totalBytes > 0L) ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99) else 50
                                    postOrUpdateRunningNotification(
                                        appContext,
                                        initialState.copy(
                                            currentFileName = activeName,
                                            processedBytes = copiedBytes,
                                            totalBytes = totalBytes,
                                            progressPercent = pct,
                                            isIndeterminate = totalBytes <= 0L
                                        )
                                    )
                                }
                            }
                            if (ok) dest else null
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }

                checkPauseOrCancel(taskId)
                if (dup != null) {
                    val msg = "Duplicated as ${dup.name}"
                    completeTaskNotification(
                        context = appContext,
                        taskId = taskId,
                        title = "File Manager • Duplicate Completed",
                        summaryMessage = msg,
                        isSuccess = true
                    )
                    ApkPackageInstaller.showToastOnMain(appContext, msg)
                } else {
                    val msg = "Failed to duplicate ${sourceFile.name}"
                    completeTaskNotification(
                        context = appContext,
                        taskId = taskId,
                        title = "File Manager • Duplicate Failed",
                        summaryMessage = msg,
                        isSuccess = false
                    )
                    ApkPackageInstaller.showToastOnMain(appContext, msg)
                }
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
            }
        }
    }

    /**
     * Runs a Delete from Storage task in the background application scope with a persistent live notification.
     */
    fun launchDeleteFromStorageTask(
        context: Context,
        itemsToDelete: List<FileItemEntry>,
        downloadManager: DragonDownloadManager
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)
        val total = itemsToDelete.size.coerceAtLeast(1)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Deleting $total item(s)",
            subtitle = "Removing from device storage...",
            currentFileName = itemsToDelete.firstOrNull()?.name.orEmpty(),
            completedItems = 0,
            totalItems = total,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = total <= 1
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                var deletedCount = 0
                itemsToDelete.forEachIndexed { idx, entry ->
                    checkPauseOrCancel(taskId)
                    val pct = ((idx * 100) / total).coerceIn(0, 99)
                    postOrUpdateRunningNotification(
                        appContext,
                        initialState.copy(
                            title = "File Manager • Deleting (${idx + 1}/$total)",
                            currentFileName = entry.name,
                            completedItems = idx,
                            progressPercent = pct,
                            isIndeterminate = false
                        )
                    )
                    entry.linkedTask?.let { linked ->
                        downloadManager.deleteTaskFromStorage(linked.id)
                    }
                    val ok = AndroidDataObbBridge.deleteFileOrDirectory(appContext, entry.file)
                    if (ok || !AndroidDataObbBridge.existsUniversal(appContext, entry.file)) {
                        deletedCount++
                    }
                }
                checkPauseOrCancel(taskId)
                val msg = "Deleted $deletedCount item(s) from storage"
                completeTaskNotification(
                    context = appContext,
                    taskId = taskId,
                    title = "File Manager • Delete Completed",
                    summaryMessage = msg,
                    isSuccess = deletedCount > 0
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
            }
        }
    }

    /**
     * Moves one or more files/directories into Dragon Archiver's Trash Bin so they can be
     * restored later to their original locations or permanently deleted.
     */
    fun launchMoveToTrashTask(
        context: Context,
        itemsToDelete: List<FileItemEntry>,
        downloadManager: DragonDownloadManager
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)
        val total = itemsToDelete.size.coerceAtLeast(1)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Moving $total item(s) to Trash",
            subtitle = "Storing safely in Trash Bin...",
            currentFileName = itemsToDelete.firstOrNull()?.name.orEmpty(),
            completedItems = 0,
            totalItems = total,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = total <= 1
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                var trashedCount = 0
                val touchedParents = HashSet<String>()
                itemsToDelete.forEachIndexed { idx, entry ->
                    checkPauseOrCancel(taskId)
                    val pct = ((idx * 100) / total).coerceIn(0, 99)
                    postOrUpdateRunningNotification(
                        appContext,
                        initialState.copy(
                            title = "File Manager • Moving to Trash (${idx + 1}/$total)",
                            currentFileName = entry.name,
                            completedItems = idx,
                            progressPercent = pct,
                            isIndeterminate = false
                        )
                    )
                    val trashed = DragonTrashBinManager.moveToTrash(appContext, entry.file)
                    if (trashed != null) {
                        entry.linkedTask?.let { linked ->
                            downloadManager.deleteTaskFromListOnly(linked.id)
                        }
                        entry.file.parentFile?.absolutePath?.let { touchedParents.add(it) }
                        trashedCount++
                    }
                }
                checkPauseOrCancel(taskId)
                touchedParents.forEach { invalidateDirectorySnapshotCache(it) }
                val msg = if (trashedCount == 1) {
                    "Moved 1 item to Trash Bin"
                } else {
                    "Moved $trashedCount item(s) to Trash Bin"
                }
                completeTaskNotification(
                    context = appContext,
                    taskId = taskId,
                    title = "File Manager • Moved to Trash Bin",
                    summaryMessage = msg,
                    isSuccess = trashedCount > 0
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
            }
        }
    }

    /**
     * Encrypts and moves one or more files/directories into Dragon Archiver's password-protected Private Vault.
     */
    fun launchMoveToPrivateVaultTask(
        context: Context,
        itemsToVault: List<FileItemEntry>,
        downloadManager: DragonDownloadManager,
        onCompleted: ((Int) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)
        val total = itemsToVault.size.coerceAtLeast(1)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Locking $total item(s) in Private Vault",
            subtitle = "Encrypting with AES-256 into Private Vault...",
            currentFileName = itemsToVault.firstOrNull()?.name.orEmpty(),
            completedItems = 0,
            totalItems = total,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = total <= 1
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                var vaultedCount = 0
                var processedBytes = 0L
                val totalEstimatedBytes = itemsToVault.sumOf {
                    checkPauseOrCancel(taskId)
                    AndroidDataObbBridge.estimateTotalBytesUniversal(appContext, it.file)
                }.coerceAtLeast(0L)
                var lastUpdateMs = 0L
                val touchedParents = HashSet<String>()

                itemsToVault.forEachIndexed { idx, entry ->
                    checkPauseOrCancel(taskId)
                    val pct = if (totalEstimatedBytes > 0L) {
                        ((processedBytes * 100L) / totalEstimatedBytes).toInt().coerceIn(0, 99)
                    } else {
                        ((idx * 100) / total).coerceIn(0, 99)
                    }
                    postOrUpdateRunningNotification(
                        appContext,
                        initialState.copy(
                            title = "File Manager • Locking in Private Vault (${idx + 1}/$total)",
                            currentFileName = entry.name,
                            completedItems = idx,
                            processedBytes = processedBytes,
                            totalBytes = totalEstimatedBytes,
                            progressPercent = pct,
                            isIndeterminate = false
                        )
                    )
                    val vaulted = DragonPrivateVaultManager.moveToVault(appContext, entry.file) { delta ->
                        checkPauseOrCancel(taskId)
                        processedBytes += delta
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 220L) {
                            lastUpdateMs = now
                            val livePct = if (totalEstimatedBytes > 0L) {
                                ((processedBytes * 100L) / totalEstimatedBytes).toInt().coerceIn(0, 99)
                            } else {
                                (((idx * 100) + 50) / total).coerceIn(0, 99)
                            }
                            postOrUpdateRunningNotification(
                                appContext,
                                initialState.copy(
                                    title = "File Manager • Locking in Private Vault (${idx + 1}/$total)",
                                    currentFileName = entry.name,
                                    completedItems = idx,
                                    processedBytes = processedBytes,
                                    totalBytes = totalEstimatedBytes,
                                    progressPercent = livePct,
                                    isIndeterminate = false
                                )
                            )
                        }
                    }
                    if (vaulted != null) {
                        entry.linkedTask?.let { linked ->
                            downloadManager.deleteTaskFromListOnly(linked.id)
                        }
                        entry.file.parentFile?.absolutePath?.let { touchedParents.add(it) }
                        vaultedCount++
                    }
                }
                checkPauseOrCancel(taskId)
                touchedParents.forEach { invalidateDirectorySnapshotCache(it) }
                val msg = if (vaultedCount == 1) {
                    "Locked 1 item in Private Vault"
                } else {
                    "Locked $vaultedCount item(s) in Private Vault"
                }
                completeTaskNotification(
                    context = appContext,
                    taskId = taskId,
                    title = "File Manager • Locked in Private Vault",
                    summaryMessage = msg,
                    isSuccess = vaultedCount > 0
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
                onCompleted?.invoke(vaultedCount)
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
                onCompleted?.invoke(0)
            }
        }
    }

    fun notifyExternalDirectoryMutation() {
        _refreshTickFlow.value = _refreshTickFlow.value + 1
    }

    /**
     * Runs a Detailed Multi-Format Compression task (ZIP, 7Z, TAR, TAR.GZ, TAR.BZ2, TAR.XZ, TAR.ZST, TAR.LZ4,
     * JAR, APK, XAPK, APKS, CPIO, GZIP, BZIP2, XZ, ZSTD, LZ4, LZMA) in the background application scope
     * with a persistent live notification.
     */
    fun launchUniversalCompressTask(
        context: Context,
        sources: List<File>,
        outputArchive: File,
        config: CompressionJobConfig
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)
        val totalItems = sources.size.coerceAtLeast(1)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Compressing (${config.format.label})",
            subtitle = "${outputArchive.name} • ${config.levelPreset.shortTag}",
            currentFileName = sources.firstOrNull()?.name.orEmpty(),
            completedItems = 0,
            totalItems = totalItems,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = true
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                checkPauseOrCancel(taskId)
                val finalOutputArchive = resolveConflictTargetFile(
                    context = appContext,
                    candidateDest = outputArchive,
                    operationLabel = "Compress",
                    isDirectory = false,
                    incomingSizeBytes = -1L,
                    allowApplyToAll = false,
                    taskId = taskId
                )
                if (finalOutputArchive == null) {
                    val skipMsg = "Skipped: ${outputArchive.name} already exists"
                    completeTaskNotification(
                        context = appContext,
                        taskId = taskId,
                        title = "File Manager • Compression Skipped",
                        summaryMessage = skipMsg,
                        isSuccess = true
                    )
                    ApkPackageInstaller.showToastOnMain(appContext, skipMsg)
                    return@launch
                }
                if (AndroidDataObbBridge.existsUniversal(appContext, finalOutputArchive)) {
                    AndroidDataObbBridge.deleteFileOrDirectory(appContext, finalOutputArchive)
                }
                var lastUpdateMs = 0L
                val success = UniversalArchiveEngine.compressUniversalArchive(
                    context = appContext,
                    sources = sources,
                    outputArchive = finalOutputArchive,
                    config = config
                ) { doneFiles, totalFiles, doneBytes, totalBytes, activeName ->
                    checkPauseOrCancel(taskId)
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= 200L) {
                        lastUpdateMs = now
                        val pct = if (totalBytes > 0L) {
                            ((doneBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        } else {
                            ((doneFiles * 100) / totalFiles.coerceAtLeast(1)).coerceIn(0, 99)
                        }
                        postOrUpdateRunningNotification(
                            appContext,
                            initialState.copy(
                                title = "File Manager • Compressing ${config.format.extensionSuffix.uppercase(Locale.US)} ($doneFiles/$totalFiles)",
                                subtitle = "${finalOutputArchive.name} • ${config.levelPreset.shortTag}",
                                currentFileName = activeName,
                                completedItems = doneFiles,
                                totalItems = totalFiles,
                                processedBytes = doneBytes,
                                totalBytes = totalBytes,
                                progressPercent = pct,
                                isIndeterminate = false
                            )
                        )
                    }
                }
                checkPauseOrCancel(taskId)
                val outLen = AndroidDataObbBridge.estimateTotalBytesUniversal(appContext, finalOutputArchive)
                val msg = if (success) {
                    "Compressed to ${finalOutputArchive.name} (${formatBytes(outLen)} • ${config.levelPreset.shortTag})"
                } else {
                    "Failed to create archive: ${finalOutputArchive.name}"
                }
                completeTaskNotification(
                    context = appContext,
                    taskId = taskId,
                    title = if (success) "File Manager • Archive Created" else "File Manager • Compression Failed",
                    summaryMessage = msg,
                    isSuccess = success
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
            }
        }
    }

    /**
     * Runs a Universal Multi-Format Archive Extraction task (.zip, .7z, .rar, .tar, .tar.gz, .tgz, .tar.bz2,
     * .tar.xz, .tar.zst, .tar.lz4, .cpio, .deb, .ar, .gz, .bz2, .xz, .zst, .lz4, .lzma, .xapk, .apks, .apkm, .jar)
     * in the background application scope with a persistent live notification.
     */
    fun launchExtractArchiveTask(
        context: Context,
        archiveFile: File,
        targetDir: File,
        selectedEntryPaths: List<String>? = null
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)
        val fmtLabel = UniversalArchiveEngine.detectFormatLabel(archiveFile)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Decompressing $fmtLabel",
            subtitle = "${archiveFile.name} → ${targetDir.name}",
            currentFileName = archiveFile.name,
            completedItems = 0,
            totalItems = selectedEntryPaths?.size ?: 0,
            processedBytes = 0L,
            totalBytes = archiveFile.length().coerceAtLeast(0L),
            progressPercent = 0,
            isIndeterminate = true
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                checkPauseOrCancel(taskId)
                var lastUpdateMs = 0L
                var finalBytes = 0L
                var skippedCount = 0
                val count = if (!selectedEntryPaths.isNullOrEmpty()) {
                    val totalFiles = selectedEntryPaths.size.coerceAtLeast(1)
                    val estimatedBytes = UniversalArchiveEngine.estimateSelectedEntriesTotalBytes(
                        context = appContext,
                        archiveFile = archiveFile,
                        selectedEntryPaths = selectedEntryPaths
                    )
                    UniversalArchiveEngine.copyEntriesFromArchiveDirectToDirectory(
                        context = appContext,
                        archiveFile = archiveFile,
                        selectedEntryPaths = selectedEntryPaths,
                        targetDir = targetDir,
                        onSkipped = { skippedCount++ }
                    ) { deltaBytes, activeName ->
                        checkPauseOrCancel(taskId)
                        finalBytes += deltaBytes
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 200L) {
                            lastUpdateMs = now
                            val effTotal = maxOf(estimatedBytes, finalBytes)
                            val pct = if (effTotal > 0L) {
                                ((finalBytes * 100L) / effTotal).toInt().coerceIn(0, 99)
                            } else 50
                            postOrUpdateRunningNotification(
                                appContext,
                                initialState.copy(
                                    title = "File Manager • Extracting $fmtLabel ($totalFiles selected)",
                                    currentFileName = activeName,
                                    totalItems = totalFiles,
                                    processedBytes = finalBytes,
                                    totalBytes = effTotal,
                                    progressPercent = pct,
                                    isIndeterminate = effTotal <= 0L
                                )
                            )
                        }
                    }
                } else {
                    UniversalArchiveEngine.extractEntireArchiveUniversal(
                        context = appContext,
                        archiveFile = archiveFile,
                        targetDir = targetDir,
                        onSkipped = { skippedCount++ }
                    ) { doneFiles, totalFiles, doneBytes, totalBytes, activeName ->
                        checkPauseOrCancel(taskId)
                        finalBytes = doneBytes
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 200L) {
                            lastUpdateMs = now
                            val pct = if (totalBytes > 0L) {
                                ((doneBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            } else {
                                ((doneFiles * 100) / totalFiles.coerceAtLeast(1)).coerceIn(0, 99)
                            }
                            postOrUpdateRunningNotification(
                                appContext,
                                initialState.copy(
                                    title = "File Manager • Decompressing $fmtLabel ($doneFiles/$totalFiles)",
                                    currentFileName = activeName,
                                    completedItems = doneFiles,
                                    totalItems = totalFiles,
                                    processedBytes = doneBytes,
                                    totalBytes = totalBytes,
                                    progressPercent = pct,
                                    isIndeterminate = false
                                )
                            )
                        }
                    }
                }
                checkPauseOrCancel(taskId)
                val isOk = count > 0 || skippedCount > 0
                val msg = when {
                    count > 0 && skippedCount > 0 ->
                        "Decompressed $count file(s) (${formatBytes(finalBytes)}), skipped $skippedCount existing"
                    count > 0 ->
                        "Decompressed $count file(s) (${formatBytes(finalBytes)}) to ${targetDir.name}"
                    skippedCount > 0 ->
                        "Skipped $skippedCount existing file(s) in ${targetDir.name}"
                    else ->
                        "Failed to decompress ${archiveFile.name}"
                }
                completeTaskNotification(
                    context = appContext,
                    taskId = taskId,
                    title = if (isOk) "File Manager • Decompression Complete" else "File Manager • Decompression Failed",
                    summaryMessage = msg,
                    isSuccess = isOk
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
            }
        }
    }

    /**
     * Runs Standalone V1+V2 APK Auto-Signing & Zipalign in the background application scope
     * with a persistent live notification.
     */
    fun launchStandaloneApkSignTask(
        context: Context,
        itemFile: File,
        extension: String,
        fallbackDir: File
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Auto-Signing APK (V1+V2)",
            subtitle = "Zipalign + V1 JAR + V2 APK Sig Block 42",
            currentFileName = itemFile.name,
            completedItems = 0,
            totalItems = 1,
            processedBytes = 0L,
            totalBytes = itemFile.length().coerceAtLeast(0L),
            progressPercent = 25,
            isIndeterminate = true
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                checkPauseOrCancel(taskId)
                val signedOutput = try {
                    val (tempSigned, _) = ApkAutoSigner.ensureApkSigned(
                        context = appContext,
                        apkFile = itemFile,
                        forceSign = true
                    )
                    checkPauseOrCancel(taskId)
                    val outName = itemFile.nameWithoutExtension + "_signed." + extension.ifBlank { "apk" }
                    val destFile = File(itemFile.parentFile ?: fallbackDir, outName)
                    AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                        context = appContext,
                        source = tempSigned,
                        dest = destFile,
                        isDirectoryHint = false
                    )
                    destFile
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }

                checkPauseOrCancel(taskId)
                if (signedOutput != null) {
                    val msg = "Saved V1+V2 signed package: ${signedOutput.name}"
                    completeTaskNotification(
                        context = appContext,
                        taskId = taskId,
                        title = "File Manager • APK Signed (V1+V2)",
                        summaryMessage = msg,
                        isSuccess = true
                    )
                    ApkPackageInstaller.showToastOnMain(appContext, msg)
                } else {
                    val msg = "Failed to sign ${itemFile.name}"
                    completeTaskNotification(
                        context = appContext,
                        taskId = taskId,
                        title = "File Manager • APK Sign Failed",
                        summaryMessage = msg,
                        isSuccess = false
                    )
                    ApkPackageInstaller.showToastOnMain(appContext, msg)
                }
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
            }
        }
    }

    /**
     * Runs Batch Rename in the background application scope with a persistent live notification.
     */
    fun launchBatchRenameTask(
        context: Context,
        filesToRename: List<File>,
        prefix: String,
        startNumber: Int
    ) {
        val appContext = context.applicationContext
        val taskId = taskCounter.getAndIncrement()
        prepareNewTask(taskId)
        val total = filesToRename.size.coerceAtLeast(1)

        val initialState = FileManagerRunningTaskState(
            taskId = taskId,
            title = "File Manager • Batch Renaming $total item(s)",
            subtitle = "Prefix: $prefix",
            currentFileName = filesToRename.firstOrNull()?.name.orEmpty(),
            completedItems = 0,
            totalItems = total,
            processedBytes = 0L,
            totalBytes = 0L,
            progressPercent = 0,
            isIndeterminate = false
        )
        postOrUpdateRunningNotification(appContext, initialState)
        DragonDownloadService.startForegroundDownloads(appContext)

        activeTaskJob = engineScope.launch {
            try {
                var count = 0
                filesToRename.forEachIndexed { idx, file ->
                    checkPauseOrCancel(taskId)
                    val num = startNumber + idx
                    val ext = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
                    val newName = "${prefix}_$num$ext"
                    val pct = ((idx * 100) / total).coerceIn(0, 99)
                    postOrUpdateRunningNotification(
                        appContext,
                        initialState.copy(
                            currentFileName = newName,
                            completedItems = idx,
                            progressPercent = pct
                        )
                    )
                    if (AndroidDataObbBridge.renameFileOrDirectory(appContext, file, newName)) {
                        count++
                    }
                }
                checkPauseOrCancel(taskId)
                val msg = "Batch renamed $count item(s)"
                completeTaskNotification(
                    context = appContext,
                    taskId = taskId,
                    title = "File Manager • Batch Rename Complete",
                    summaryMessage = msg,
                    isSuccess = count > 0
                )
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            } catch (_: CancellationException) {
                handleTaskCancelled(appContext, taskId)
            }
        }
    }

    /**
     * Runs Standalone APK/APKS/XAPK/APKM installation in the background application scope so switching
     * back to DDM or exiting File Manager never interrupts package extraction, V1+V2 auto-signing,
     * session commit, or OS install/cancel/failure terminal status tracking.
     */
    fun launchStandalonePackageInstallTask(
        context: Context,
        file: File,
        forceSign: Boolean
    ) {
        val appContext = context.applicationContext
        val taskId = allocatePackageInstallTaskId()
        beginPackageInstallTask(
            context = appContext,
            taskId = taskId,
            title = if (forceSign) "File Manager • Auto-Signing & Installing" else "File Manager • Preparing Package",
            subtitle = file.name,
            currentFileName = file.name,
            totalBytes = file.length().coerceAtLeast(0L)
        )

        val job = ApkPackageInstaller.installOrUpdateStandaloneFile(
            context = appContext,
            file = file,
            scope = engineScope,
            forceSign = forceSign,
            existingFmTaskId = taskId,
            onFeedback = { msg ->
                ApkPackageInstaller.showToastOnMain(appContext, msg)
            }
        )
        attachPackageInstallJob(taskId, job)
    }

    private val COMPOUND_ARCHIVE_EXTENSIONS = listOf(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.lzma"
    )

    fun resolveUniqueDestinationFile(context: Context, dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!AndroidDataObbBridge.existsUniversal(context, candidate)) return candidate
        val lower = desiredName.lowercase(Locale.US)
        val compoundExt = COMPOUND_ARCHIVE_EXTENSIONS.firstOrNull { lower.endsWith(it) }
        val base: String
        val ext: String
        if (compoundExt != null && desiredName.length > compoundExt.length) {
            base = desiredName.dropLast(compoundExt.length)
            ext = desiredName.takeLast(compoundExt.length)
        } else {
            val dotIdx = desiredName.lastIndexOf('.')
            if (dotIdx > 0) {
                base = desiredName.substring(0, dotIdx)
                ext = desiredName.substring(dotIdx)
            } else {
                base = desiredName
                ext = ""
            }
        }
        var index = 1
        while (AndroidDataObbBridge.existsUniversal(context, candidate)) {
            candidate = File(dir, "${base}_($index)$ext")
            index++
        }
        return candidate
    }
}
