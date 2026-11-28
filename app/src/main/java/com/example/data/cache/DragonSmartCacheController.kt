package com.example.data.cache

import android.content.Context
import android.os.Environment
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.data.model.DownloadStateStage
import com.example.data.model.DownloaderTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class SmartCacheBreakdown(
    val totalCacheBytes: Long = 0L,
    val maxBudgetBytes: Long = DragonSmartCacheController.DEFAULT_MAX_CACHE_BUDGET_BYTES,
    val apkStagingBytes: Long = 0L,
    val archiveAndEditorTempBytes: Long = 0L,
    val downloaderWorkBytes: Long = 0L,
    val imageAndThumbnailCacheBytes: Long = 0L,
    val webAndOtherCacheBytes: Long = 0L,
    val protectedExtensionBytes: Long = 0L,
    val autoControlEnabled: Boolean = true,
    val lastFreedBytes: Long = 0L,
    val lastTrimTimestampMs: Long = 0L
) {
    val usageFraction: Float
        get() = if (maxBudgetBytes > 0L) {
            (totalCacheBytes.toFloat() / maxBudgetBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
}

/**
 * Smart Cache Size Controller & Quota Enforcer for Dragon Download Manager, Dragon Browser,
 * and Dragon Archiver.
 *
 * Prevents `context.cacheDir`, `context.externalCacheDir`, Coil disk cache, and WebView HTTP cache
 * from growing unbounded by:
 * 1. Configuring a strict bounded Coil [ImageLoader] (16 MB disk cache cap, 15% memory cap).
 * 2. Purging duplicate APK installer staging copies once already exported to public Downloads,
 *    and trimming temporary archive preview, APK editor build/stage, document edit, and blob stream folders.
 * 3. Enforcing a configurable Least-Recently-Used (LRU) cache budget (default 48 MB) while never
 *    touching active download task work folders (`downloader_work/<activeId>`) or installed
 *    browser extensions (`browser_extensions_store`).
 */
object DragonSmartCacheController {

    private const val PREFS_NAME = "dragon_smart_cache_prefs"
    private const val KEY_AUTO_CONTROL_ENABLED = "auto_control_enabled"
    private const val KEY_MAX_BUDGET_MB = "max_budget_mb"

    const val DEFAULT_MAX_CACHE_BUDGET_MB = 48
    const val DEFAULT_MAX_CACHE_BUDGET_BYTES = DEFAULT_MAX_CACHE_BUDGET_MB * 1024L * 1024L
    private const val COIL_MAX_DISK_CACHE_BYTES = 16L * 1024L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coilConfigured = AtomicBoolean(false)
    private val maintenanceInFlight = AtomicBoolean(false)
    @Volatile private var lastMaintenanceRunMs: Long = 0L
    private val activeProtectedWorkTaskIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun markDownloadWorkActive(taskId: String) {
        if (taskId.isNotBlank()) activeProtectedWorkTaskIds.add(taskId)
    }

    fun unmarkDownloadWorkActive(taskId: String) {
        if (taskId.isNotBlank()) activeProtectedWorkTaskIds.remove(taskId)
    }

    private val _cacheStateFlow = MutableStateFlow(SmartCacheBreakdown())
    val cacheStateFlow: StateFlow<SmartCacheBreakdown> = _cacheStateFlow.asStateFlow()

    private val PROTECTED_DIR_NAMES = setOf(
        "browser_extensions_store"
    )

    internal fun isChromiumInternalCacheDir(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower == "webview" ||
            lower.startsWith("webview_") ||
            lower.contains("chromium") ||
            lower == "shadercache" ||
            lower == "grshadercache" ||
            lower == "code cache" ||
            lower == "gpucache" ||
            lower == "vulkanshader" ||
            lower == "dawnshader"
    }

    private val EPHEMERAL_FILE_PREFIXES = listOf(
        "compress_stage_",
        "archive_read_",
        "root_stage_",
        "docx_edit_",
        "odt_edit_",
        "doc_stage_",
        "pdf_export_",
        "pdf_create_",
        "ota_part_",
        "signed_"
    )

    fun ensureInitialized(context: Context) {
        val appContext = context.applicationContext
        configureBoundedCoilImageLoader(appContext)
        refreshSnapshotAsync(appContext)
    }

    private fun configureBoundedCoilImageLoader(appContext: Context) {
        if (!coilConfigured.compareAndSet(false, true)) return
        runCatching {
            val loader = ImageLoader.Builder(appContext)
                .memoryCache {
                    MemoryCache.Builder(appContext)
                        .maxSizePercent(0.15)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(appContext.cacheDir, "image_cache"))
                        .maxSizeBytes(COIL_MAX_DISK_CACHE_BYTES)
                        .build()
                }
                .respectCacheHeaders(false)
                .build()
            Coil.setImageLoader(loader)
        }
    }

    fun isAutoControlEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_CONTROL_ENABLED, true)
    }

    fun setAutoControlEnabled(context: Context, enabled: Boolean, activeTasks: List<DownloaderTask> = emptyList()) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CONTROL_ENABLED, enabled)
            .apply()
        if (enabled) {
            runSmartMaintenanceAsync(appContext, activeTasks, aggressive = false, force = true)
        } else {
            refreshSnapshotAsync(appContext)
        }
    }

    fun getMaxBudgetMb(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_BUDGET_MB, DEFAULT_MAX_CACHE_BUDGET_MB).coerceIn(16, 256)
    }

    fun getMaxBudgetBytes(context: Context): Long {
        return getMaxBudgetMb(context).toLong() * 1024L * 1024L
    }

    fun setMaxBudgetMb(context: Context, budgetMb: Int, activeTasks: List<DownloaderTask> = emptyList()) {
        val appContext = context.applicationContext
        val clamped = budgetMb.coerceIn(16, 256)
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_BUDGET_MB, clamped)
            .apply()
        runSmartMaintenanceAsync(appContext, activeTasks, aggressive = false, force = true)
    }

    fun refreshSnapshotAsync(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            _cacheStateFlow.value = inspectCacheBreakdown(appContext)
        }
    }

    fun inspectCacheBreakdown(context: Context): SmartCacheBreakdown {
        val appContext = context.applicationContext
        val cacheDir = appContext.cacheDir
        val extCacheDir = runCatching { appContext.externalCacheDir }.getOrNull()

        var apkStaging = 0L
        var archiveAndEditor = 0L
        var downloaderWork = 0L
        var imageCache = 0L
        var webAndOther = 0L
        var protectedExt = 0L

        fun classifyChildren(root: File?) {
            if (root == null || !root.exists()) return
            val children = root.listFiles() ?: return
            for (child in children) {
                val name = child.name
                val size = computeBytes(child)
                when {
                    name in PROTECTED_DIR_NAMES -> {
                        protectedExt += size
                    }
                    name == "apk_installer_staging" -> {
                        apkStaging += size
                    }
                    name in setOf(
                        "archive_preview_cache",
                        "apk_editor_stage",
                        "apk_editor_build",
                        "exports",
                        "ext_temp_staging"
                    ) || EPHEMERAL_FILE_PREFIXES.any { name.startsWith(it, ignoreCase = true) } ||
                        name.endsWith(".v1tmp", ignoreCase = true) -> {
                        archiveAndEditor += size
                    }
                    name in setOf("downloader_work", "ddm_blob_streams", "ddm_exports") -> {
                        downloaderWork += size
                    }
                    name.contains("image_cache", ignoreCase = true) ||
                        name.contains("coil", ignoreCase = true) -> {
                        imageCache += size
                    }
                    else -> {
                        webAndOther += size
                    }
                }
            }
        }

        classifyChildren(cacheDir)
        classifyChildren(extCacheDir)

        val controllableTotal = apkStaging + archiveAndEditor + downloaderWork + imageCache + webAndOther
        val prev = _cacheStateFlow.value
        return SmartCacheBreakdown(
            totalCacheBytes = controllableTotal,
            maxBudgetBytes = getMaxBudgetBytes(appContext),
            apkStagingBytes = apkStaging,
            archiveAndEditorTempBytes = archiveAndEditor,
            downloaderWorkBytes = downloaderWork,
            imageAndThumbnailCacheBytes = imageCache,
            webAndOtherCacheBytes = webAndOther,
            protectedExtensionBytes = protectedExt,
            autoControlEnabled = isAutoControlEnabled(appContext),
            lastFreedBytes = prev.lastFreedBytes,
            lastTrimTimestampMs = prev.lastTrimTimestampMs
        )
    }

    /**
     * Runs smart background cache maintenance with throttling unless `force = true`.
     */
    fun runSmartMaintenanceAsync(
        context: Context,
        tasks: List<DownloaderTask> = emptyList(),
        aggressive: Boolean = false,
        force: Boolean = false,
        preserveDownloaderWork: Boolean = false,
        onComplete: ((Long) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        val now = System.currentTimeMillis()
        if (!force && !aggressive && (now - lastMaintenanceRunMs) < 20_000L) {
            onComplete?.invoke(0L)
            return
        }
        if (!maintenanceInFlight.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                lastMaintenanceRunMs = System.currentTimeMillis()
                val freed = performSmartCacheMaintenanceSync(
                    context = appContext,
                    tasks = tasks,
                    aggressive = aggressive,
                    preserveDownloaderWork = preserveDownloaderWork
                )
                onComplete?.invoke(freed)
            } finally {
                maintenanceInFlight.set(false)
            }
        }
    }

    /**
     * Performs synchronous smart cache pruning and LRU quota enforcement.
     *
     * - When `aggressive = false` (automatic mode): cleans expired/completed temporary files,
     *   orphaned download segments, and trims oldest cache files if total cache exceeds budget.
     * - When `aggressive = true` (user clicked "Smart Clean Cache Now" or OS low-memory signal):
     *   immediately wipes all non-active temporary staging, archive previews, APK build workspace,
     *   stale WebView HTTP cache, and Coil disk cache while preserving active downloads and
     *   installed browser extensions.
     */
    fun performSmartCacheMaintenanceSync(
        context: Context,
        tasks: List<DownloaderTask> = emptyList(),
        aggressive: Boolean = false,
        preserveDownloaderWork: Boolean = false
    ): Long {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        if (!aggressive && !isAutoControlEnabled(appContext)) {
            _cacheStateFlow.value = inspectCacheBreakdown(appContext)
            return 0L
        }

        val now = System.currentTimeMillis()
        var bytesFreed = 0L

        val activeTaskIds = buildSet {
            addAll(activeProtectedWorkTaskIds)
            tasks.forEach { task ->
                if (task.stage.isActive || task.stage == DownloadStateStage.PAUSED || task.isSmartRetrying) {
                    add(task.id)
                }
            }
        }

        // Paths actively referenced by tasks that do NOT have a public MediaStore URI fallback
        val protectedTaskFilePaths = tasks
            .mapNotNull { task ->
                val path = task.filePath?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!task.publicUriString.isNullOrBlank()) {
                    // Task is safely backed by public MediaStore Downloads; cache copy is redundant
                    null
                } else {
                    path
                }
            }
            .toSet()

        val cacheRoots = listOfNotNull(
            appContext.cacheDir,
            runCatching { appContext.externalCacheDir }.getOrNull()
        )

        for (root in cacheRoots) {
            if (!root.exists()) continue

            // 1. Orphaned downloader_work task directories
            // Never delete active/paused/recently-written work dirs, and never sweep when caller did not supply tasks.
            val workRoot = File(root, "downloader_work")
            if (!preserveDownloaderWork && workRoot.exists() && tasks.isNotEmpty()) {
                workRoot.listFiles()?.forEach { dir ->
                    val recentlyTouched = (now - dir.lastModified()) < 10 * 60_000L
                    if (dir.name !in activeTaskIds && !recentlyTouched) {
                        bytesFreed += deleteFileOrDirCountingBytes(dir)
                    }
                }
            }

            // 2. Completed or stale ddm_blob_streams (.part files older than 10 min or aggressive)
            val blobStreamsDir = File(root, "ddm_blob_streams")
            if (blobStreamsDir.exists()) {
                val maxAge = if (aggressive && activeTaskIds.isEmpty()) 0L else 10 * 60_000L
                blobStreamsDir.listFiles()?.forEach { f ->
                    if (now - f.lastModified() >= maxAge) {
                        bytesFreed += deleteFileOrDirCountingBytes(f)
                    }
                }
            }

            // 3. APK Installer Staging (`apk_installer_staging`)
            // Previously held permanent 100MB-2GB copies of every downloaded APK/XAPK!
            val apkStagingDir = File(root, "apk_installer_staging")
            if (apkStagingDir.exists()) {
                val maxAge = if (aggressive) 0L else 2 * 60_000L
                apkStagingDir.listFiles()?.forEach { child ->
                    val isProbeOrSigCheck = child.name.startsWith("probe_") ||
                        child.name.startsWith("sigcheck_") ||
                        child.name.startsWith("inspect_") ||
                        child.name.endsWith(".v1tmp")
                    if (isProbeOrSigCheck ||
                        (child.absolutePath !in protectedTaskFilePaths && (now - child.lastModified() >= maxAge))
                    ) {
                        bytesFreed += deleteFileOrDirCountingBytes(child)
                    }
                }
            }

            // 4. Archive Preview Cache (`archive_preview_cache`)
            val archivePreviewDir = File(root, "archive_preview_cache")
            if (archivePreviewDir.exists()) {
                val maxAge = if (aggressive) 0L else 5 * 60_000L
                archivePreviewDir.listFiles()?.forEach { child ->
                    if (now - child.lastModified() >= maxAge) {
                        bytesFreed += deleteFileOrDirCountingBytes(child)
                    }
                }
                if (!aggressive) {
                    bytesFreed += trimFolderToMaxBytesLru(archivePreviewDir, 12L * 1024L * 1024L)
                }
            }

            // 5. APK Editor Build & Stage workspaces (`apk_editor_build`, `apk_editor_stage`)
            val apkBuildDir = File(root, "apk_editor_build")
            if (apkBuildDir.exists()) {
                val maxAge = if (aggressive) 0L else 2 * 60_000L
                apkBuildDir.listFiles()?.forEach { child ->
                    if (now - child.lastModified() >= maxAge) {
                        bytesFreed += deleteFileOrDirCountingBytes(child)
                    }
                }
            }
            val apkStageDir = File(root, "apk_editor_stage")
            if (apkStageDir.exists()) {
                val maxAge = if (aggressive) 0L else 10 * 60_000L
                apkStageDir.listFiles()?.forEach { child ->
                    if (now - child.lastModified() >= maxAge) {
                        bytesFreed += deleteFileOrDirCountingBytes(child)
                    }
                }
                if (!aggressive) {
                    bytesFreed += trimFolderToMaxBytesLru(apkStageDir, 20L * 1024L * 1024L)
                }
            }

            // 6. Extension temporary staging (`ext_temp_staging`) and Log Exports (`exports`)
            val extStagingDir = File(root, "ext_temp_staging")
            if (extStagingDir.exists()) {
                val maxAge = if (aggressive) 0L else 60_000L
                extStagingDir.listFiles()?.forEach { child ->
                    if (now - child.lastModified() >= maxAge) {
                        bytesFreed += deleteFileOrDirCountingBytes(child)
                    }
                }
            }
            val exportsDir = File(root, "exports")
            if (exportsDir.exists()) {
                val maxAge = if (aggressive) 0L else 10 * 60_000L
                exportsDir.listFiles()?.forEach { child ->
                    if (now - child.lastModified() >= maxAge) {
                        bytesFreed += deleteFileOrDirCountingBytes(child)
                    }
                }
            }

            // 7. Root-level ephemeral staging files (`compress_stage_*`, `archive_read_*`, `docx_edit_*`, `pdf_export_*`, `ota_part_*`, etc.)
            root.listFiles()?.forEach { child ->
                if (child.isFile) {
                    val name = child.name
                    val isEphemeral = EPHEMERAL_FILE_PREFIXES.any { name.startsWith(it, ignoreCase = true) } ||
                        name.endsWith(".v1tmp", ignoreCase = true) ||
                        name.endsWith(".tmp", ignoreCase = true)
                    val maxAge = if (aggressive) 0L else 3 * 60_000L
                    if (isEphemeral && (now - child.lastModified() >= maxAge)) {
                        bytesFreed += deleteFileOrDirCountingBytes(child)
                    }
                }
            }

            // 8. Redundant ddm_exports copies in cacheDir that already exist in public Downloads or are unreferenced
            val ddmExportsDir = File(root, "ddm_exports")
            if (ddmExportsDir.exists()) {
                bytesFreed += sweepRedundantDdmCacheExports(ddmExportsDir, protectedTaskFilePaths)
            }
        }

        // 9. Enforce global cache quota via LRU across all non-protected cache directories
        val budgetBytes = if (aggressive) {
            8L * 1024L * 1024L
        } else {
            getMaxBudgetBytes(appContext)
        }
        bytesFreed += enforceGlobalCacheBudgetLru(
            cacheRoots = cacheRoots,
            maxBudgetBytes = budgetBytes,
            activeTaskIds = activeTaskIds,
            protectedFilePaths = protectedTaskFilePaths
        )

        if (aggressive) {
            runCatching {
                Coil.imageLoader(appContext).memoryCache?.clear()
            }
        }

        val updatedSnapshot = inspectCacheBreakdown(appContext).copy(
            lastFreedBytes = bytesFreed,
            lastTrimTimestampMs = System.currentTimeMillis()
        )
        _cacheStateFlow.value = updatedSnapshot
        return bytesFreed
    }

    private fun sweepRedundantDdmCacheExports(
        dir: File,
        protectedPaths: Set<String>
    ): Long {
        var freed = 0L
        val children = dir.listFiles() ?: return 0L
        for (child in children) {
            if (child.isDirectory) {
                freed += sweepRedundantDdmCacheExports(child, protectedPaths)
                if (child.listFiles().isNullOrEmpty()) {
                    runCatching { child.delete() }
                }
            } else {
                @Suppress("DEPRECATION")
                val pubDownloads = runCatching {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                }.getOrNull()
                val existsInPublic = pubDownloads != null &&
                    File(pubDownloads, "DDM/${dir.name}/${child.name}").exists()
                if (child.absolutePath !in protectedPaths || existsInPublic) {
                    freed += deleteFileOrDirCountingBytes(child)
                }
            }
        }
        return freed
    }

    private fun enforceGlobalCacheBudgetLru(
        cacheRoots: List<File>,
        maxBudgetBytes: Long,
        activeTaskIds: Set<String>,
        protectedFilePaths: Set<String>
    ): Long {
        val candidateFiles = mutableListOf<File>()
        var totalControllableBytes = 0L

        fun collectCandidates(dir: File, isInsideDownloaderWork: Boolean) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.name in PROTECTED_DIR_NAMES) continue
                if (child.isDirectory && isChromiumInternalCacheDir(child.name)) continue
                // Never evict active/paused segmental downloads via the 48 MB cache LRU budget
                if (isInsideDownloaderWork || child.name == "downloader_work") continue
                if (child.isDirectory) {
                    collectCandidates(child, false)
                } else if (child.isFile) {
                    val len = child.length().coerceAtLeast(0L)
                    totalControllableBytes += len
                    if (child.absolutePath !in protectedFilePaths) {
                        candidateFiles.add(child)
                    }
                }
            }
        }

        cacheRoots.forEach { root ->
            if (root.exists()) collectCandidates(root, false)
        }

        if (totalControllableBytes <= maxBudgetBytes) return 0L

        var freed = 0L
        // Sort oldest-modified first (LRU eviction)
        val sortedOldestFirst = candidateFiles.sortedBy { it.lastModified() }
        for (file in sortedOldestFirst) {
            if (totalControllableBytes - freed <= maxBudgetBytes) break
            val len = file.length().coerceAtLeast(0L)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                freed += len
            }
        }
        return freed
    }

    private fun trimFolderToMaxBytesLru(folder: File, maxBytes: Long): Long {
        if (!folder.exists() || !folder.isDirectory) return 0L
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length().coerceAtLeast(0L) }
        if (total <= maxBytes) return 0L
        var freed = 0L
        for (f in files.sortedBy { it.lastModified() }) {
            if (total - freed <= maxBytes) break
            val len = f.length().coerceAtLeast(0L)
            if (runCatching { f.delete() }.getOrDefault(false)) {
                freed += len
            }
        }
        return freed
    }

    fun deleteFileOrDirCountingBytes(target: File?): Long {
        if (target == null || !target.exists()) return 0L
        val size = computeBytes(target)
        return if (runCatching { target.deleteRecursively() }.getOrDefault(false)) size else 0L
    }

    fun computeBytes(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length().coerceAtLeast(0L)
        var sum = 0L
        file.listFiles()?.forEach { child ->
            sum += computeBytes(child)
        }
        return sum
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
            safe >= 1024L * 1024L ->
                String.format(Locale.US, "%.1f MB", safe / (1024.0 * 1024.0))
            safe >= 1024L ->
                String.format(Locale.US, "%.0f KB", safe / 1024.0)
            else -> "$safe B"
        }
    }
}
