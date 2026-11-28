package com.example.data.downloader

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.filemanager.DragonFileManagerTaskEngine
import com.example.data.filemanager.FileManagerTerminalStatus
import com.example.data.model.DownloaderTask
import com.example.receiver.ApkInstallerReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Full-featured Android Package Installer & Updater supporting monolithic `.apk` files
 * as well as split APK archives (`.apks`, `.xapk`, `.apkm`).
 *
 * Features:
 * - Detects whether a downloaded package is a new installation or an update to an already-installed app.
 * - Handles Android 8.0+ `REQUEST_INSTALL_PACKAGES` ("Install unknown apps") permission flow with
 *   automatic resume when the user returns from system settings.
 * - Installs/updates single `.apk` packages via `FileProvider` system installer prompt with automatic
 *   `PackageInstaller.Session` fallback.
 * - Installs/updates multi-split `.apks`, `.xapk`, and `.apkm` bundles via `PackageInstaller.Session`,
 *   including automatic CPU ABI split filtering (`Build.SUPPORTED_ABIS`) and `.obb` expansion deployment.
 */
object ApkPackageInstaller {

    val INSTALLABLE_EXTENSIONS = setOf("apk", "apks", "xapk", "apkm")
    const val MIME_ANDROID_PACKAGE = "application/vnd.android.package-archive"

    private val KNOWN_ABI_TOKENS = mapOf(
        "arm64-v8a" to listOf("arm64_v8a", "arm64-v8a", "arm64"),
        "armeabi-v7a" to listOf("armeabi_v7a", "armeabi-v7a", "armv7a", "armeabi"),
        "x86_64" to listOf("x86_64", "x86-64"),
        "x86" to listOf("x86")
    )

    data class ApkPackageSummary(
        val taskId: String,
        val formatBadge: String,
        val isSplitBundle: Boolean,
        val packageName: String?,
        val appLabel: String?,
        val versionName: String?,
        val versionCode: Long,
        val splitCount: Int = 1,
        val hasObbExpansion: Boolean = false,
        val isUnsigned: Boolean = false,
        val isAlreadyInstalled: Boolean = false,
        val installedVersionName: String? = null,
        val installedVersionCode: Long = -1L
    ) {
        val isDowngrade: Boolean
            get() = isAlreadyInstalled &&
                versionCode > 0L &&
                installedVersionCode > 0L &&
                versionCode < installedVersionCode

        val isSameVersion: Boolean
            get() = isAlreadyInstalled &&
                versionCode > 0L &&
                installedVersionCode > 0L &&
                versionCode == installedVersionCode

        val isUpdate: Boolean
            get() = isAlreadyInstalled && !isDowngrade

        val shortActionText: String
            get() = when {
                isDowngrade -> "Downgrade"
                isAlreadyInstalled -> "Update"
                else -> "Install"
            }

        val fullActionText: String
            get() = "$shortActionText $formatBadge"

        val statusSummary: String
            get() = buildList {
                add("$formatBadge Package")
                if (!versionName.isNullOrBlank()) {
                    add("v$versionName")
                }
                if (splitCount > 1) {
                    add("$splitCount splits")
                }
                if (hasObbExpansion) {
                    add("+OBB")
                }
                add(if (isUnsigned) "Unsigned (Auto-Sign Ready)" else "Signed")
                when {
                    isDowngrade -> {
                        val instVer = installedVersionName?.takeIf { it.isNotBlank() }?.let { "v$it" }
                            ?: "code $installedVersionCode"
                        add("Downgrade ($instVer → v${versionName ?: versionCode})")
                    }
                    isAlreadyInstalled -> {
                        val instVer = installedVersionName?.takeIf { it.isNotBlank() }?.let { " (Installed: v$it)" }.orEmpty()
                        add("Installed$instVer")
                    }
                    else -> add("Ready to Install")
                }
            }.joinToString(" • ")
    }

    data class PackageResolutionPrompt(
        val task: DownloaderTask,
        val packageName: String,
        val appLabel: String,
        val installedVersionName: String?,
        val installedVersionCode: Long,
        val incomingVersionName: String?,
        val incomingVersionCode: Long,
        val isDowngrade: Boolean,
        val isSignatureConflict: Boolean,
        val wasAutoSigned: Boolean,
        val fmTaskId: Int,
        val reasonMessage: String,
        val rootAvailable: Boolean
    )

    private data class PendingReinstallTarget(
        val task: DownloaderTask,
        val packageName: String,
        val forceSign: Boolean
    )

    private val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val summaryCache = ConcurrentHashMap<String, ApkPackageSummary>()
    private val pendingTasksById = ConcurrentHashMap<String, DownloaderTask>()
    private val fmTaskIdByInstallId = ConcurrentHashMap<String, Int>()

    private val _activeInstallStatusFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeInstallStatusFlow: StateFlow<Map<String, String>> = _activeInstallStatusFlow.asStateFlow()

    private val _installStateVersionFlow = MutableStateFlow(0)
    val installStateVersionFlow: StateFlow<Int> = _installStateVersionFlow.asStateFlow()

    private val _pendingResolutionFlow = MutableStateFlow<PackageResolutionPrompt?>(null)
    val pendingResolutionFlow: StateFlow<PackageResolutionPrompt?> = _pendingResolutionFlow.asStateFlow()

    @Volatile
    private var pendingPermissionTaskId: String? = null

    @Volatile
    private var pendingPostUninstallTarget: PendingReinstallTarget? = null

    fun isInstallableExtension(ext: String?): Boolean {
        if (ext.isNullOrBlank()) return false
        return ext.trim().removePrefix(".").lowercase(Locale.US) in INSTALLABLE_EXTENSIONS
    }

    fun resolveTaskPackageExtension(task: DownloaderTask): String {
        val fromFilename = task.filename.substringAfterLast('.', "").lowercase(Locale.US)
        if (fromFilename in INSTALLABLE_EXTENSIONS) return fromFilename
        val fromContainer = task.container.trim().removePrefix(".").lowercase(Locale.US)
        if (fromContainer in INSTALLABLE_EXTENSIONS) return fromContainer
        val fromFilePath = task.filePath?.substringAfterLast('.', "")?.lowercase(Locale.US).orEmpty()
        if (fromFilePath in INSTALLABLE_EXTENSIONS) return fromFilePath
        if (task.mimeType.equals(MIME_ANDROID_PACKAGE, ignoreCase = true)) return "apk"
        return ""
    }

    fun isInstallablePackageTask(task: DownloaderTask): Boolean {
        return resolveTaskPackageExtension(task).isNotEmpty()
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        } else {
            true
        }
    }

    fun hasPendingPermissionInstall(): Boolean = !pendingPermissionTaskId.isNullOrBlank()

    fun consumePendingPermissionTaskId(): String? {
        val id = pendingPermissionTaskId
        pendingPermissionTaskId = null
        return id
    }

    fun getRegisteredTask(taskId: String): DownloaderTask? = pendingTasksById[taskId]

    fun getFmTaskIdForInstall(taskId: String): Int = fmTaskIdByInstallId[taskId] ?: 0

    fun requestPackageResolution(prompt: PackageResolutionPrompt) {
        _pendingResolutionFlow.value = prompt
    }

    fun dismissPackageResolution() {
        _pendingResolutionFlow.value = null
    }

    fun dispatchResolutionPromptFromReceiver(
        context: Context,
        taskId: String,
        packageNameHint: String,
        sourcePath: String,
        publicUri: String,
        filename: String,
        isDowngrade: Boolean,
        isSignatureConflict: Boolean,
        wasAutoSigned: Boolean,
        fmTaskId: Int,
        reasonMessage: String
    ) {
        val appContext = context.applicationContext
        installScope.launch {
            val resolvedTask = taskId.takeIf { it.isNotBlank() }?.let { getRegisteredTask(it) }
                ?: sourcePath.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
                    ?.let { buildSyntheticTaskForFile(it) }
                ?: publicUri.takeIf { it.isNotBlank() }?.let { uriStr ->
                    val ext = filename.substringAfterLast('.', "apk").lowercase(Locale.US)
                    DownloaderTask(
                        id = taskId.ifBlank { "rx_${filename.hashCode().toUInt().toString(16)}" },
                        url = uriStr,
                        title = filename.substringBeforeLast('.', filename),
                        creator = "Package Installer",
                        thumbnailUrl = "",
                        platform = com.example.data.model.MediaPlatform.DIRECT,
                        formatOption = com.example.data.model.MediaFormatOption(
                            id = "rx_$ext",
                            qualityLabel = "Package",
                            container = ext,
                            mimeType = MIME_ANDROID_PACKAGE,
                            contentLengthBytes = 0L,
                            streamUrl = uriStr,
                            isGenericFile = true
                        ),
                        filename = filename,
                        mimeType = MIME_ANDROID_PACKAGE,
                        container = ext,
                        qualityLabel = "Package",
                        isAudioOnly = false,
                        isGenericFile = true,
                        stage = com.example.data.model.DownloadStateStage.COMPLETED,
                        progress = 1f,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        speedBytesPerSec = 0L,
                        etaSeconds = 0L,
                        filePath = null,
                        publicUriString = uriStr
                    )
                }
                ?: return@launch

            val summary = inspectTaskPackage(appContext, resolvedTask, forceRefresh = false)
            val pkgName = (summary?.packageName ?: packageNameHint).trim()
            if (pkgName.isBlank()) return@launch

            val installedInfo = getInstalledPackageInfo(appContext, pkgName)
            val installedVerName = installedInfo?.versionName ?: summary?.installedVersionName
            val installedVerCode = installedInfo?.let { getPackageVersionCode(it) }
                ?: summary?.installedVersionCode
                ?: -1L
            val incomingVerName = summary?.versionName
            val incomingVerCode = summary?.versionCode ?: -1L
            val appLabel = summary?.appLabel?.takeIf { it.isNotBlank() }
                ?: installedInfo?.applicationInfo?.let { appInfo ->
                    runCatching { appContext.packageManager.getApplicationLabel(appInfo)?.toString() }.getOrNull()
                }
                ?: filename
            val effectiveDowngrade = isDowngrade ||
                (installedVerCode > 0L && incomingVerCode > 0L && incomingVerCode < installedVerCode)

            requestPackageResolution(
                PackageResolutionPrompt(
                    task = resolvedTask,
                    packageName = pkgName,
                    appLabel = appLabel,
                    installedVersionName = installedVerName,
                    installedVersionCode = installedVerCode,
                    incomingVersionName = incomingVerName,
                    incomingVersionCode = incomingVerCode,
                    isDowngrade = effectiveDowngrade,
                    isSignatureConflict = isSignatureConflict,
                    wasAutoSigned = wasAutoSigned,
                    fmTaskId = fmTaskId,
                    reasonMessage = reasonMessage,
                    rootAvailable = isRootAvailable()
                )
            )
        }
    }

    fun launchUninstallAndQueueReinstall(context: Context, prompt: PackageResolutionPrompt) {
        val appContext = context.applicationContext
        _pendingResolutionFlow.value = null
        pendingPostUninstallTarget = PendingReinstallTarget(
            task = prompt.task,
            packageName = prompt.packageName,
            forceSign = prompt.wasAutoSigned
        )
        try {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${prompt.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
        } catch (e: Exception) {
            pendingPostUninstallTarget = null
            showToastOnMain(appContext, "Unable to open uninstall prompt: ${e.message}")
        }
    }

    fun resumePendingPostUninstallIfReady(context: Context) {
        val pending = pendingPostUninstallTarget ?: return
        val appContext = context.applicationContext
        val stillInstalled = getInstalledPackageInfo(appContext, pending.packageName) != null
        if (!stillInstalled) {
            pendingPostUninstallTarget = null
            notifyPackageInstallChanged(pending.task.id)
            installOrUpdatePackage(
                context = appContext,
                task = pending.task,
                scope = installScope,
                forceSign = pending.forceSign,
                onFeedback = { msg -> showToastOnMain(appContext, msg) }
            )
        }
    }

    fun retryResolutionWithAutoSign(context: Context, prompt: PackageResolutionPrompt) {
        _pendingResolutionFlow.value = null
        val appContext = context.applicationContext
        installOrUpdatePackage(
            context = appContext,
            task = prompt.task,
            scope = installScope,
            forceSign = true,
            onFeedback = { msg -> showToastOnMain(appContext, msg) }
        )
    }

    fun isRootAvailable(): Boolean {
        val paths = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/vendor/bin/su"
        )
        return paths.any { runCatching { File(it).exists() }.getOrDefault(false) }
    }

    fun tryRootInstallFromPrompt(context: Context, prompt: PackageResolutionPrompt) {
        _pendingResolutionFlow.value = null
        val appContext = context.applicationContext
        val fmTaskId = DragonFileManagerTaskEngine.allocatePackageInstallTaskId()
        DragonFileManagerTaskEngine.beginPackageInstallTask(
            context = appContext,
            taskId = fmTaskId,
            title = if (prompt.isDowngrade) "Package Downgrade (Root)" else "Package Install (Root)",
            subtitle = "Running pm install -r -d for ${prompt.appLabel}",
            currentFileName = prompt.task.filename,
            totalBytes = prompt.task.totalBytes
        )
        val job = installScope.launch {
            executeRootInstall(
                context = appContext,
                task = prompt.task,
                forceSign = prompt.wasAutoSigned,
                isDowngrade = prompt.isDowngrade,
                fmTaskId = fmTaskId
            )
        }
        DragonFileManagerTaskEngine.attachPackageInstallJob(fmTaskId, job)
    }

    fun inspectStandaloneFilePackage(
        context: Context,
        file: File,
        forceRefresh: Boolean = false
    ): ApkPackageSummary? {
        if (!file.exists() || !file.isFile) return null
        val ext = file.extension.lowercase(Locale.US).ifBlank { "apk" }
        if (ext !in INSTALLABLE_EXTENSIONS) return null
        val syntheticTask = buildSyntheticTaskForFile(file, ext)
        return inspectTaskPackage(context, syntheticTask, forceRefresh = forceRefresh)
    }

    fun buildSyntheticTaskForFile(file: File, extOverride: String? = null): DownloaderTask {
        val ext = (extOverride ?: file.extension.lowercase(Locale.US)).ifBlank { "apk" }
        return DownloaderTask(
            id = "fm_pkg_${file.absolutePath.hashCode().toUInt().toString(16)}",
            url = file.toURI().toString(),
            title = file.nameWithoutExtension.ifBlank { file.name },
            creator = "Dragon File Manager",
            thumbnailUrl = "",
            platform = com.example.data.model.MediaPlatform.DIRECT,
            formatOption = com.example.data.model.MediaFormatOption(
                id = "local_$ext",
                qualityLabel = "Local Package",
                container = ext,
                mimeType = MIME_ANDROID_PACKAGE,
                contentLengthBytes = file.length(),
                streamUrl = file.toURI().toString(),
                isGenericFile = true
            ),
            filename = file.name,
            mimeType = MIME_ANDROID_PACKAGE,
            container = ext,
            qualityLabel = "Local Package",
            isAudioOnly = false,
            isGenericFile = true,
            stage = com.example.data.model.DownloadStateStage.COMPLETED,
            progress = 1f,
            downloadedBytes = file.length(),
            totalBytes = file.length(),
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            filePath = file.absolutePath
        )
    }

    fun notifyPackageInstallChanged(taskId: String? = null) {
        if (taskId != null) {
            summaryCache.remove(taskId)
            setTaskInstallProgress(taskId, null)
        } else {
            summaryCache.clear()
        }
        _installStateVersionFlow.update { it + 1 }
    }

    private fun setTaskInstallProgress(taskId: String, status: String?) {
        _activeInstallStatusFlow.update { current ->
            val mutable = current.toMutableMap()
            if (status.isNullOrBlank()) {
                mutable.remove(taskId)
            } else {
                mutable[taskId] = status
            }
            mutable
        }
    }

    /**
     * Inspects a newly downloaded ZIP-based file (`PK\x03\x04`) to determine if it is actually
     * an `.apk`, `.xapk`, or `.apks` package when downloaded from a generic stream or URL without extension.
     */
    fun sniffZipArchivePackageType(file: File): Pair<String, String>? {
        if (!file.exists() || file.length() < 32L) return null
        return try {
            ZipFile(file).use { zip ->
                var hasAndroidManifest = false
                var hasClassesDexOrRes = false
                var hasXapkManifestJson = false
                var hasTocPb = false
                var apkEntryCount = 0
                var obbEntryCount = 0

                val entries = zip.entries()
                var scanned = 0
                while (entries.hasMoreElements() && scanned < 120) {
                    val entry = entries.nextElement()
                    scanned++
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    val lower = name.lowercase(Locale.US)
                    when {
                        name == "AndroidManifest.xml" -> hasAndroidManifest = true
                        name == "classes.dex" || name == "resources.arsc" -> hasClassesDexOrRes = true
                        lower == "manifest.json" -> hasXapkManifestJson = true
                        lower == "toc.pb" -> hasTocPb = true
                    }
                    if (lower.endsWith(".apk")) apkEntryCount++
                    if (lower.endsWith(".obb")) obbEntryCount++
                }

                when {
                    hasAndroidManifest && hasClassesDexOrRes ->
                        "apk" to MIME_ANDROID_PACKAGE
                    hasXapkManifestJson && (apkEntryCount > 0 || obbEntryCount > 0) ->
                        "xapk" to MIME_ANDROID_PACKAGE
                    (hasTocPb && apkEntryCount > 0) || apkEntryCount > 1 || (apkEntryCount == 1 && !hasAndroidManifest) ->
                        "apks" to MIME_ANDROID_PACKAGE
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Inspects a completed `.apk`, `.apks`, `.xapk`, or `.apkm` task and determines its package name,
     * version, split count, signature presence, and whether it is already installed on the device (Install vs Update).
     */
    fun inspectTaskPackage(
        context: Context,
        task: DownloaderTask,
        forceRefresh: Boolean = false
    ): ApkPackageSummary? {
        if (!isInstallablePackageTask(task)) return null
        pendingTasksById[task.id] = task
        if (!forceRefresh) {
            summaryCache[task.id]?.let { cached ->
                return refreshInstalledState(context, cached)
            }
        }

        val ext = resolveTaskPackageExtension(task).ifBlank { "apk" }
        val formatBadge = ext.uppercase(Locale.US)
        val isSplitExt = ext in setOf("apks", "xapk", "apkm")

        var packageName: String? = null
        var appLabel: String? = null
        var versionName: String? = null
        var versionCode = -1L
        var splitCount = 1
        var hasObb = false
        var actuallySplitBundle = isSplitExt
        var isUnsigned = false

        try {
            val localFile = task.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?.takeIf { it.exists() && it.canRead() && it.length() > 0L }

            if (!isSplitExt) {
                // Single APK inspection: try local file directly, or stage header/archive briefly if only in MediaStore
                var tempInspectFile: File? = null
                val inspectableApkFile = localFile ?: stageMediaStoreToTempFile(
                    context = context,
                    publicUriString = task.publicUriString,
                    tempName = "inspect_${task.id.hashCode().toUInt().toString(16)}.apk"
                ).also { tempInspectFile = it }
                try {
                    if (inspectableApkFile != null && inspectableApkFile.exists()) {
                        // Check if a file named .apk is actually a split bundle ZIP containing sub-APKs
                        val sniffedType = sniffZipArchivePackageType(inspectableApkFile)?.first
                        if (sniffedType == "xapk" || sniffedType == "apks") {
                            actuallySplitBundle = true
                            val bundleMeta = inspectSplitArchiveFile(context, inspectableApkFile)
                            packageName = bundleMeta.packageName
                            appLabel = bundleMeta.appLabel
                            versionName = bundleMeta.versionName
                            versionCode = bundleMeta.versionCode
                            splitCount = bundleMeta.splitCount
                            hasObb = bundleMeta.hasObb
                            isUnsigned = ApkAutoSigner.isFileOrBundleUnsigned(context, inspectableApkFile, true)
                        } else {
                            isUnsigned = ApkAutoSigner.isFileOrBundleUnsigned(context, inspectableApkFile, false)
                            val pkgInfo = getArchivePackageInfo(context, inspectableApkFile.absolutePath)
                            if (pkgInfo != null) {
                                packageName = pkgInfo.packageName
                                versionName = pkgInfo.versionName
                                versionCode = getPackageVersionCode(pkgInfo)
                                appLabel = runCatching {
                                    pkgInfo.applicationInfo?.let { appInfo ->
                                        appInfo.sourceDir = inspectableApkFile.absolutePath
                                        appInfo.publicSourceDir = inspectableApkFile.absolutePath
                                        context.packageManager.getApplicationLabel(appInfo)?.toString()
                                    }
                                }.getOrNull()
                            }
                        }
                    }
                } finally {
                    tempInspectFile?.let { runCatching { it.delete() } }
                }
            } else {
                // Split bundle (.apks, .xapk, .apkm)
                if (localFile != null) {
                    val bundleMeta = inspectSplitArchiveFile(context, localFile)
                    packageName = bundleMeta.packageName
                    appLabel = bundleMeta.appLabel
                    versionName = bundleMeta.versionName
                    versionCode = bundleMeta.versionCode
                    splitCount = bundleMeta.splitCount
                    hasObb = bundleMeta.hasObb
                    isUnsigned = ApkAutoSigner.isFileOrBundleUnsigned(context, localFile, true)
                } else if (!task.publicUriString.isNullOrBlank()) {
                    val uri = runCatching { Uri.parse(task.publicUriString) }.getOrNull()
                    if (uri != null) {
                        context.contentResolver.openInputStream(uri)?.use { rawIn ->
                            val bundleMeta = inspectSplitArchiveStream(rawIn)
                            packageName = bundleMeta.packageName
                            appLabel = bundleMeta.appLabel
                            versionName = bundleMeta.versionName
                            versionCode = bundleMeta.versionCode
                            splitCount = bundleMeta.splitCount
                            hasObb = bundleMeta.hasObb
                            isUnsigned = bundleMeta.hasUnsignedSplit
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val initialSummary = ApkPackageSummary(
            taskId = task.id,
            formatBadge = formatBadge,
            isSplitBundle = actuallySplitBundle,
            packageName = packageName,
            appLabel = appLabel,
            versionName = versionName,
            versionCode = versionCode,
            splitCount = splitCount.coerceAtLeast(1),
            hasObbExpansion = hasObb,
            isUnsigned = isUnsigned
        )
        val finalSummary = refreshInstalledState(context, initialSummary)
        summaryCache[task.id] = finalSummary
        return finalSummary
    }

    private fun refreshInstalledState(context: Context, summary: ApkPackageSummary): ApkPackageSummary {
        val pkgName = summary.packageName?.takeIf { it.isNotBlank() } ?: return summary
        val installedInfo = getInstalledPackageInfo(context, pkgName)
        return if (installedInfo != null) {
            summary.copy(
                isAlreadyInstalled = true,
                installedVersionName = installedInfo.versionName,
                installedVersionCode = getPackageVersionCode(installedInfo)
            )
        } else {
            summary.copy(
                isAlreadyInstalled = false,
                installedVersionName = null,
                installedVersionCode = -1L
            )
        }
    }

    private data class SplitArchiveMeta(
        val packageName: String? = null,
        val appLabel: String? = null,
        val versionName: String? = null,
        val versionCode: Long = -1L,
        val splitCount: Int = 1,
        val hasObb: Boolean = false,
        val hasUnsignedSplit: Boolean = false
    )

    private fun inspectSplitArchiveFile(context: Context, archiveFile: File): SplitArchiveMeta {
        return try {
            ZipFile(archiveFile).use { zip ->
                var pkgName: String? = null
                var appLabel: String? = null
                var verName: String? = null
                var verCode = -1L
                var apkCount = 0
                var hasObb = false
                var baseApkEntry: ZipEntry? = null

                var baseApkPriority = Int.MAX_VALUE
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    val lower = name.lowercase(Locale.US)
                    val fileOnly = lower.substringAfterLast('/')
                    if (lower.endsWith(".apk")) {
                        apkCount++
                        val priority = when {
                            fileOnly == "base.apk" || fileOnly == "base-master.apk" -> 0
                            !fileOnly.startsWith("config.") && !fileOnly.startsWith("split_config.") && !fileOnly.contains(".config.") -> 1
                            else -> 2
                        }
                        if (priority < baseApkPriority) {
                            baseApkPriority = priority
                            baseApkEntry = entry
                        }
                    } else if (lower.endsWith(".obb")) {
                        hasObb = true
                    } else if (lower == "manifest.json" || lower == "info.json") {
                        runCatching {
                            val jsonText = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                            val obj = JSONObject(jsonText)
                            pkgName = obj.optString("package_name", obj.optString("pname", "")).ifBlank { pkgName }
                            appLabel = obj.optString("name", obj.optString("app_name", "")).ifBlank { appLabel }
                            verName = obj.optString("version_name", obj.optString("version", "")).ifBlank { verName }
                            val vc = obj.optString("version_code", "")
                                .trim()
                                .toLongOrNull()
                                ?: obj.optString("versioncode", "").trim().toLongOrNull()
                                ?: obj.optLong("version_code", obj.optLong("versioncode", -1L))
                            if (vc > 0L) verCode = vc
                        }
                    }
                }

                // Probe base.apk whenever packageName, versionCode, or label is missing from JSON metadata
                if ((pkgName.isNullOrBlank() || verCode <= 0L || appLabel.isNullOrBlank()) && baseApkEntry != null) {
                    val tempProbe = File(context.cacheDir, "apk_installer_staging/probe_${archiveFile.name.hashCode()}.apk")
                    try {
                        tempProbe.parentFile?.mkdirs()
                        zip.getInputStream(baseApkEntry).use { input ->
                            FileOutputStream(tempProbe).use { out ->
                                input.copyTo(out, 64 * 1024)
                            }
                        }
                        val info = getArchivePackageInfo(context, tempProbe.absolutePath)
                        if (info != null) {
                            if (pkgName.isNullOrBlank()) pkgName = info.packageName
                            if (verName.isNullOrBlank()) verName = info.versionName
                            if (verCode <= 0L) verCode = getPackageVersionCode(info)
                            if (appLabel.isNullOrBlank()) {
                                appLabel = runCatching {
                                    info.applicationInfo?.let { appInfo ->
                                        appInfo.sourceDir = tempProbe.absolutePath
                                        appInfo.publicSourceDir = tempProbe.absolutePath
                                        context.packageManager.getApplicationLabel(appInfo)?.toString()
                                    }
                                }.getOrNull()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { tempProbe.delete() }
                    }
                }

                SplitArchiveMeta(
                    packageName = pkgName,
                    appLabel = appLabel,
                    versionName = verName,
                    versionCode = verCode,
                    splitCount = apkCount.coerceAtLeast(1),
                    hasObb = hasObb
                )
            }
        } catch (_: Exception) {
            SplitArchiveMeta()
        }
    }

    private fun inspectSplitArchiveStream(rawStream: InputStream): SplitArchiveMeta {
        var pkgName: String? = null
        var appLabel: String? = null
        var verName: String? = null
        var verCode = -1L
        var apkCount = 0
        var hasObb = false
        var hasUnsignedSplit = false
        try {
            ZipInputStream(BufferedInputStream(rawStream)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.replace('\\', '/').trimStart('/')
                        val lower = name.lowercase(Locale.US)
                        if (lower.endsWith(".apk")) {
                            apkCount++
                            if (!hasUnsignedSplit && apkCount <= 3) {
                                // Check if sub-APK stream has any META-INF signature block
                                runCatching {
                                    val innerZip = ZipInputStream(zis)
                                    var hasCert = false
                                    var innerEntry = innerZip.nextEntry
                                    while (innerEntry != null) {
                                        val innerName = innerEntry.name.uppercase(Locale.US)
                                        if (innerName.startsWith("META-INF/") &&
                                            (innerName.endsWith(".RSA") || innerName.endsWith(".DSA") || innerName.endsWith(".EC"))
                                        ) {
                                            hasCert = true
                                            break
                                        }
                                        innerEntry = innerZip.nextEntry
                                    }
                                    if (!hasCert) hasUnsignedSplit = true
                                }
                            }
                        } else if (lower.endsWith(".obb")) {
                            hasObb = true
                        } else if ((lower == "manifest.json" || lower == "info.json") && pkgName == null) {
                            runCatching {
                                val bytes = zis.readBytes()
                                val obj = JSONObject(String(bytes, Charsets.UTF_8))
                                pkgName = obj.optString("package_name", obj.optString("pname", "")).ifBlank { null }
                                appLabel = obj.optString("name", obj.optString("app_name", "")).ifBlank { null }
                                verName = obj.optString("version_name", obj.optString("version", "")).ifBlank { null }
                                val vc = obj.optLong("version_code", obj.optLong("versioncode", -1L))
                                if (vc > 0L) verCode = vc
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {}
        return SplitArchiveMeta(
            packageName = pkgName,
            appLabel = appLabel,
            versionName = verName,
            versionCode = verCode,
            splitCount = apkCount.coerceAtLeast(1),
            hasObb = hasObb,
            hasUnsignedSplit = hasUnsignedSplit
        )
    }

    private fun getArchivePackageInfo(context: Context, archiveFilePath: String): PackageInfo? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(
                    archiveFilePath,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(archiveFilePath, 0)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getInstalledPackageInfo(context: Context, packageName: String): PackageInfo? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getPackageVersionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /**
     * Requests the "Install unknown apps" setting page for this app when `canRequestPackageInstalls()` is false.
     */
    fun openUnknownSourcesSettings(
        context: Context,
        task: DownloaderTask,
        fmTaskId: Int = 0,
        onFeedback: (String) -> Unit
    ) {
        pendingPermissionTaskId = task.id
        val msg = "Allow 'Install unknown apps' to install ${task.filename}"
        onFeedback(msg)
        if (fmTaskId > 0) {
            DragonFileManagerTaskEngine.finishPackageInstallTask(
                context = context,
                taskId = fmTaskId,
                title = "Permission Required",
                summaryMessage = msg,
                status = FileManagerTerminalStatus.FAILED
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Installs, updates, or downgrades any `.apk`, `.apks`, `.xapk`, or `.apkm` package
     * (signed or unsigned) independently via `PackageInstaller.Session`, with automatic V1+V2
     * signing/zipaligning and real-time notification lifecycle tracking.
     */
    fun installOrUpdatePackage(
        context: Context,
        task: DownloaderTask,
        scope: CoroutineScope = installScope,
        forceSign: Boolean = false,
        existingFmTaskId: Int = -1,
        onFeedback: (String) -> Unit
    ): Job? {
        val appContext = context.applicationContext
        pendingTasksById[task.id] = task

        val fmTaskId = if (existingFmTaskId > 0) {
            existingFmTaskId
        } else {
            val allocated = DragonFileManagerTaskEngine.allocatePackageInstallTaskId()
            DragonFileManagerTaskEngine.beginPackageInstallTask(
                context = appContext,
                taskId = allocated,
                title = if (forceSign) "Auto-Signing & Installing" else "Preparing Package",
                subtitle = task.filename,
                currentFileName = task.filename,
                totalBytes = task.totalBytes
            )
            allocated
        }
        fmTaskIdByInstallId[task.id] = fmTaskId

        if (!canRequestPackageInstalls(appContext)) {
            openUnknownSourcesSettings(appContext, task, fmTaskId, onFeedback)
            return null
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)
                setTaskInstallProgress(
                    task.id,
                    if (forceSign) "Signing & verifying package..." else "Verifying package..."
                )
                DragonFileManagerTaskEngine.updatePackageInstallProgress(
                    context = appContext,
                    taskId = fmTaskId,
                    title = if (forceSign) "Auto-Signing & Verifying" else "Verifying Package",
                    subtitle = "Inspecting ${task.filename}",
                    currentFileName = task.filename,
                    progressPercent = 18,
                    isIndeterminate = false
                )

                val summary = inspectTaskPackage(appContext, task, forceRefresh = true)
                DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)

                val ext = resolveTaskPackageExtension(task).ifBlank { "apk" }
                val isSplitBundle = (summary?.isSplitBundle == true) || ext in setOf("apks", "xapk", "apkm")

                if (isSplitBundle) {
                    installSplitPackageBundle(
                        context = appContext,
                        task = task,
                        summary = summary,
                        forceSign = forceSign,
                        fmTaskId = fmTaskId,
                        onFeedback = onFeedback
                    )
                } else {
                    installSingleApkPackage(
                        context = appContext,
                        task = task,
                        summary = summary,
                        forceSign = forceSign,
                        fmTaskId = fmTaskId,
                        onFeedback = onFeedback
                    )
                }
            } catch (ce: CancellationException) {
                setTaskInstallProgress(task.id, null)
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = appContext,
                    taskId = fmTaskId,
                    title = "Installation Cancelled",
                    summaryMessage = "Cancelled • ${task.filename}",
                    status = FileManagerTerminalStatus.CANCELLED
                )
                throw ce
            } catch (e: Exception) {
                setTaskInstallProgress(task.id, null)
                val errMsg = "Failed to install ${task.filename}: ${e.message ?: "Invalid package archive"}"
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = appContext,
                    taskId = fmTaskId,
                    title = "Installation Failed",
                    summaryMessage = errMsg,
                    status = FileManagerTerminalStatus.FAILED
                )
                withContext(Dispatchers.Main) {
                    onFeedback(errMsg)
                }
            }
        }

        DragonFileManagerTaskEngine.attachPackageInstallJob(fmTaskId, job)
        return job
    }

    /**
     * Installs, updates, or downgrades any standalone `.apk`, `.apks`, `.xapk`, or `.apkm` file
     * directly from the Dragon File Manager.
     */
    fun installOrUpdateStandaloneFile(
        context: Context,
        file: File,
        scope: CoroutineScope = installScope,
        forceSign: Boolean = false,
        existingFmTaskId: Int = -1,
        onFeedback: (String) -> Unit
    ): Job? {
        if (!file.exists() || !file.isFile) {
            val errMsg = "Package file does not exist: ${file.name}"
            if (existingFmTaskId > 0) {
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = context.applicationContext,
                    taskId = existingFmTaskId,
                    title = "Installation Failed",
                    summaryMessage = errMsg,
                    status = FileManagerTerminalStatus.FAILED
                )
            }
            onFeedback(errMsg)
            return null
        }
        val syntheticTask = buildSyntheticTaskForFile(file)
        return installOrUpdatePackage(
            context = context,
            task = syntheticTask,
            scope = scope,
            forceSign = forceSign,
            existingFmTaskId = existingFmTaskId,
            onFeedback = onFeedback
        )
    }

    private suspend fun installSingleApkPackage(
        context: Context,
        task: DownloaderTask,
        summary: ApkPackageSummary?,
        forceSign: Boolean,
        fmTaskId: Int,
        onFeedback: (String) -> Unit
    ) {
        DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)
        setTaskInstallProgress(task.id, "Staging APK for installation...")
        DragonFileManagerTaskEngine.updatePackageInstallProgress(
            context = context,
            taskId = fmTaskId,
            title = "Staging APK",
            subtitle = "Preparing ${task.filename}",
            currentFileName = task.filename,
            progressPercent = 28,
            isIndeterminate = false
        )

        val directFile = task.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?.takeIf { it.exists() && it.canRead() && it.length() > 0L }

        val stagedApkFile = directFile ?: stageMediaStoreToTempFile(
            context = context,
            publicUriString = task.publicUriString,
            tempName = sanitizeApkStagingName(task.filename)
        )

        if (stagedApkFile == null || !stagedApkFile.exists() || stagedApkFile.length() <= 0L) {
            setTaskInstallProgress(task.id, null)
            val errMsg = "Package file not found on disk: ${task.filename}"
            DragonFileManagerTaskEngine.finishPackageInstallTask(
                context = context,
                taskId = fmTaskId,
                title = "Installation Failed",
                summaryMessage = errMsg,
                status = FileManagerTerminalStatus.FAILED
            )
            withContext(Dispatchers.Main) {
                onFeedback(errMsg)
            }
            return
        }

        DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)

        // Check if this file is actually a split bundle disguised with a .apk extension
        val sniffedType = sniffZipArchivePackageType(stagedApkFile)?.first
        if (sniffedType == "xapk" || sniffedType == "apks") {
            installSplitPackageFromFile(
                context = context,
                task = task,
                bundleFile = stagedApkFile,
                summary = summary,
                forceSign = forceSign,
                fmTaskId = fmTaskId,
                cleanupBundleFile = (directFile == null),
                onFeedback = onFeedback
            )
            return
        }

        // Re-check package details if summary didn't resolve yet (e.g. MediaStore file)
        val effectivePkgInfo = if (summary?.packageName == null) {
            getArchivePackageInfo(context, stagedApkFile.absolutePath)
        } else null
        val targetPackageName = summary?.packageName ?: effectivePkgInfo?.packageName
        val incomingVerCode = summary?.versionCode?.takeIf { it > 0L }
            ?: effectivePkgInfo?.let { getPackageVersionCode(it) } ?: -1L
        val installedInfo = targetPackageName?.let { getInstalledPackageInfo(context, it) }
        val installedVerCode = installedInfo?.let { getPackageVersionCode(it) } ?: -1L
        val isDowngrade = installedInfo != null && incomingVerCode > 0L && installedVerCode > 0L && incomingVerCode < installedVerCode
        val isAlreadyInstalled = installedInfo != null

        // Only auto-re-sign already-signed APKs with DDM key if updating/downgrading an app ALREADY installed with DDM key
        val installedMatchesDdmKey = targetPackageName?.let { pkg ->
            isInstalledAppSignedByDdmAutoSigner(context, pkg)
        } ?: false

        val actionWord = when {
            isDowngrade -> "Downgrading"
            isAlreadyInstalled -> "Updating"
            else -> "Installing"
        }

        val (readyApkFile, wasAutoSigned) = ApkAutoSigner.ensureApkSigned(
            context = context,
            apkFile = stagedApkFile,
            forceSign = forceSign || installedMatchesDdmKey,
            onProgress = { progressMsg ->
                setTaskInstallProgress(task.id, progressMsg)
                DragonFileManagerTaskEngine.updatePackageInstallProgress(
                    context = context,
                    taskId = fmTaskId,
                    title = "Auto-Signing APK (V1+V2)",
                    subtitle = progressMsg,
                    currentFileName = task.filename,
                    progressPercent = 52,
                    isIndeterminate = false
                )
            }
        )

        DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)

        val displayTitle = summary?.appLabel?.takeIf { it.isNotBlank() } ?: task.filename
        val signedNotice = if (wasAutoSigned) " (Auto-Signed V1+V2)" else ""
        withContext(Dispatchers.Main) {
            onFeedback("$actionWord $displayTitle$signedNotice...")
        }

        // Commit ALL signed & unsigned single APKs independently via PackageInstaller.Session
        // so downgrades, completion, cancellation, and failure diagnostics are tracked reliably.
        try {
            commitApksViaPackageInstallerSession(
                context = context,
                task = task,
                apkFiles = listOf(readyApkFile),
                targetPackageName = targetPackageName,
                stagingDirToClean = null,
                wasAutoSigned = wasAutoSigned || forceSign,
                isDowngrade = isDowngrade,
                forceSign = forceSign,
                fmTaskId = fmTaskId
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Fallback to system VIEW intent only if PackageInstaller session creation failed unexpectedly on a signed APK
            val fallbackLaunched = if (!wasAutoSigned && !forceSign && !isDowngrade) {
                withContext(Dispatchers.Main) {
                    try {
                        val contentUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            readyApkFile
                        )
                        @Suppress("DEPRECATION")
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(contentUri, MIME_ANDROID_PACKAGE)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
                        }
                        context.startActivity(installIntent)
                        DragonFileManagerTaskEngine.updatePackageInstallProgress(
                            context = context,
                            taskId = fmTaskId,
                            title = "Installer Opened",
                            subtitle = "Complete installation in system dialog",
                            currentFileName = task.filename,
                            progressPercent = 90,
                            isIndeterminate = true
                        )
                        setTaskInstallProgress(task.id, null)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            } else {
                false
            }
            if (!fallbackLaunched) {
                throw e
            }
        } finally {
            if (wasAutoSigned && readyApkFile != directFile && readyApkFile.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                runCatching { readyApkFile.delete() }
            }
            if (directFile == null && stagedApkFile.absolutePath.startsWith(context.cacheDir.absolutePath)) {
                runCatching { stagedApkFile.delete() }
            }
        }
    }

    private suspend fun installSplitPackageBundle(
        context: Context,
        task: DownloaderTask,
        summary: ApkPackageSummary?,
        forceSign: Boolean,
        fmTaskId: Int,
        onFeedback: (String) -> Unit
    ) {
        val directFile = task.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?.takeIf { it.exists() && it.canRead() && it.length() > 0L }

        if (directFile != null) {
            installSplitPackageFromFile(
                context = context,
                task = task,
                bundleFile = directFile,
                summary = summary,
                forceSign = forceSign,
                fmTaskId = fmTaskId,
                cleanupBundleFile = false,
                onFeedback = onFeedback
            )
            return
        }

        val ext = resolveTaskPackageExtension(task).ifBlank { "xapk" }
        val stagedBundle = stageMediaStoreToTempFile(
            context = context,
            publicUriString = task.publicUriString,
            tempName = "bundle_src_${task.id.hashCode().toUInt().toString(16)}.$ext"
        )
        if (stagedBundle == null || !stagedBundle.exists() || stagedBundle.length() <= 0L) {
            setTaskInstallProgress(task.id, null)
            val errMsg = "Package file no longer exists on disk: ${task.filename}"
            DragonFileManagerTaskEngine.finishPackageInstallTask(
                context = context,
                taskId = fmTaskId,
                title = "Installation Failed",
                summaryMessage = errMsg,
                status = FileManagerTerminalStatus.FAILED
            )
            withContext(Dispatchers.Main) {
                onFeedback(errMsg)
            }
            return
        }

        installSplitPackageFromFile(
            context = context,
            task = task,
            bundleFile = stagedBundle,
            summary = summary,
            forceSign = forceSign,
            fmTaskId = fmTaskId,
            cleanupBundleFile = true,
            onFeedback = onFeedback
        )
    }

    private suspend fun installSplitPackageFromFile(
        context: Context,
        task: DownloaderTask,
        bundleFile: File,
        summary: ApkPackageSummary?,
        forceSign: Boolean,
        fmTaskId: Int,
        cleanupBundleFile: Boolean = false,
        onFeedback: (String) -> Unit
    ) {
        DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)
        val formatLabel = summary?.formatBadge ?: resolveTaskPackageExtension(task).uppercase(Locale.US)
        setTaskInstallProgress(task.id, "Unpacking $formatLabel split bundle...")
        DragonFileManagerTaskEngine.updatePackageInstallProgress(
            context = context,
            taskId = fmTaskId,
            title = "Unpacking $formatLabel Bundle",
            subtitle = "Extracting split APKs from ${task.filename}",
            currentFileName = task.filename,
            progressPercent = 25,
            isIndeterminate = false
        )
        withContext(Dispatchers.Main) {
            onFeedback("Unpacking $formatLabel bundle: ${task.filename}...")
        }

        val stagingDir = prepareCleanStagingDir(context, task.id)
        val extractedApks = mutableListOf<File>()
        val obbEntryNames = mutableListOf<String>()
        var detectedPkgName: String? = summary?.packageName
        var detectedVerCode: Long = summary?.versionCode ?: -1L
        var obbCopiedCount = 0

        try {
            ZipFile(bundleFile).use { zip ->
                // 1. Parse manifest.json / info.json first if present
                val manifestEntry = zip.getEntry("manifest.json") ?: zip.getEntry("info.json")
                if (manifestEntry != null) {
                    runCatching {
                        val jsonText = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
                        val obj = JSONObject(jsonText)
                        val pkg = obj.optString("package_name", obj.optString("pname", ""))
                        if (pkg.isNotBlank()) detectedPkgName = pkg
                        if (detectedVerCode <= 0L) {
                            val vc = obj.optString("version_code", "").trim().toLongOrNull()
                                ?: obj.optString("versioncode", "").trim().toLongOrNull()
                                ?: obj.optLong("version_code", obj.optLong("versioncode", -1L))
                            if (vc > 0L) detectedVerCode = vc
                        }
                    }
                }

                // 2. Collect and extract all .apk splits first (queue .obb entries until base APK confirms package name)
                val apkEntries = mutableListOf<ZipEntry>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val cleanEntryName = entry.name.replace('\\', '/').trimStart('/')
                    val lower = cleanEntryName.lowercase(Locale.US)
                    when {
                        lower.endsWith(".apk") -> apkEntries.add(entry)
                        lower.endsWith(".obb") -> obbEntryNames.add(cleanEntryName)
                    }
                }

                val totalSplits = apkEntries.size.coerceAtLeast(1)
                apkEntries.forEachIndexed { idx, entry ->
                    DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)
                    val cleanEntryName = entry.name.replace('\\', '/').trimStart('/')
                    val flatName = sanitizeSplitEntryFilename(cleanEntryName, idx + 1)
                    val outFile = File(stagingDir, flatName)
                    setTaskInstallProgress(task.id, "Extracting split ${idx + 1}/$totalSplits ($flatName)...")
                    val pct = 25 + ((idx + 1) * 20 / totalSplits)
                    DragonFileManagerTaskEngine.updatePackageInstallProgress(
                        context = context,
                        taskId = fmTaskId,
                        title = "Unpacking $formatLabel Bundle",
                        subtitle = "Split ${idx + 1}/$totalSplits • $flatName",
                        currentFileName = flatName,
                        progressPercent = pct,
                        isIndeterminate = false
                    )
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { out ->
                            input.copyTo(out, 128 * 1024)
                        }
                    }
                    if (outFile.length() > 0L) {
                        extractedApks.add(outFile)
                    }
                }

                // 3. Resolve package name from base APK before deploying OBB files if manifest didn't have it
                val sortedForProbe = sortBaseApkFirst(extractedApks)
                if (detectedPkgName.isNullOrBlank() || detectedVerCode <= 0L) {
                    for (apk in sortedForProbe) {
                        val info = getArchivePackageInfo(context, apk.absolutePath) ?: continue
                        if (detectedPkgName.isNullOrBlank()) detectedPkgName = info.packageName
                        if (detectedVerCode <= 0L) detectedVerCode = getPackageVersionCode(info)
                        if (!detectedPkgName.isNullOrBlank() && detectedVerCode > 0L) break
                    }
                }

                // 4. Deploy all .obb expansion files now that packageName is guaranteed
                if (obbEntryNames.isNotEmpty()) {
                    obbEntryNames.forEachIndexed { idx, cleanEntryName ->
                        DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)
                        setTaskInstallProgress(task.id, "Deploying OBB expansion ${idx + 1}/${obbEntryNames.size}...")
                        val obbEntry = zip.getEntry(cleanEntryName)
                        if (obbEntry != null) {
                            zip.getInputStream(obbEntry).use { input ->
                                if (copyObbStreamToDevice(context, cleanEntryName, detectedPkgName, input)) {
                                    obbCopiedCount++
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            if (cleanupBundleFile) {
                runCatching { bundleFile.delete() }
            }
        }

        finalizeAndCommitSplitInstall(
            context = context,
            task = task,
            stagingDir = stagingDir,
            extractedApks = extractedApks,
            detectedPkgName = detectedPkgName,
            detectedVersionCode = detectedVerCode,
            obbCopiedCount = obbCopiedCount,
            forceSign = forceSign,
            fmTaskId = fmTaskId,
            onFeedback = onFeedback
        )
    }

    private fun sortBaseApkFirst(apkFiles: List<File>): List<File> {
        return apkFiles.sortedBy { file ->
            val lower = file.name.lowercase(Locale.US)
            when {
                lower == "base.apk" || lower == "base-master.apk" -> 0
                !lower.startsWith("config.") && !lower.startsWith("split_config.") && !lower.contains(".config.") -> 1
                else -> 2
            }
        }
    }

    private suspend fun finalizeAndCommitSplitInstall(
        context: Context,
        task: DownloaderTask,
        stagingDir: File,
        extractedApks: List<File>,
        detectedPkgName: String?,
        detectedVersionCode: Long,
        obbCopiedCount: Int,
        forceSign: Boolean,
        fmTaskId: Int,
        onFeedback: (String) -> Unit
    ) {
        if (extractedApks.isEmpty()) {
            runCatching { stagingDir.deleteRecursively() }
            setTaskInstallProgress(task.id, null)
            val errMsg = "No installable APK files found inside ${task.filename}"
            DragonFileManagerTaskEngine.finishPackageInstallTask(
                context = context,
                taskId = fmTaskId,
                title = "Installation Failed",
                summaryMessage = errMsg,
                status = FileManagerTerminalStatus.FAILED
            )
            withContext(Dispatchers.Main) {
                onFeedback(errMsg)
            }
            return
        }

        DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)

        // Filter ABI splits so universal bundles stage base + compatible ABI + density/locale splits
        val compatibleApks = sortBaseApkFirst(filterCompatibleSplitApks(extractedApks))

        var resolvedPkgName = detectedPkgName
        var resolvedVerCode = detectedVersionCode
        if (resolvedPkgName.isNullOrBlank() || resolvedVerCode <= 0L) {
            for (apk in compatibleApks) {
                val info = getArchivePackageInfo(context, apk.absolutePath) ?: continue
                if (resolvedPkgName.isNullOrBlank()) resolvedPkgName = info.packageName
                if (resolvedVerCode <= 0L) resolvedVerCode = getPackageVersionCode(info)
                if (!resolvedPkgName.isNullOrBlank() && resolvedVerCode > 0L) break
            }
        }

        val installedInfo = resolvedPkgName?.let { getInstalledPackageInfo(context, it) }
        val installedVerCode = installedInfo?.let { getPackageVersionCode(it) } ?: -1L
        val isDowngrade = installedInfo != null && resolvedVerCode > 0L && installedVerCode > 0L && resolvedVerCode < installedVerCode

        val installedMatchesDdmKey = resolvedPkgName?.let { pkg ->
            isInstalledAppSignedByDdmAutoSigner(context, pkg)
        } ?: false

        val (readyApks, wasAutoSigned) = ApkAutoSigner.ensureSplitApksSigned(
            context = context,
            apkFiles = compatibleApks,
            forceSign = forceSign || installedMatchesDdmKey,
            onProgress = { progressMsg ->
                setTaskInstallProgress(task.id, progressMsg)
                DragonFileManagerTaskEngine.updatePackageInstallProgress(
                    context = context,
                    taskId = fmTaskId,
                    title = "Auto-Signing Split APKs (V1+V2)",
                    subtitle = progressMsg,
                    currentFileName = task.filename,
                    progressPercent = 60,
                    isIndeterminate = false
                )
            }
        )

        DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)

        if (wasAutoSigned) {
            withContext(Dispatchers.Main) {
                onFeedback("Auto-signed ${readyApks.size} split APKs (V1+V2 Scheme) • Staging installation...")
            }
        } else if (obbCopiedCount > 0) {
            withContext(Dispatchers.Main) {
                onFeedback("Deployed $obbCopiedCount OBB expansion file(s) • Staging ${readyApks.size} APK splits...")
            }
        }

        commitApksViaPackageInstallerSession(
            context = context,
            task = task,
            apkFiles = sortBaseApkFirst(readyApks),
            targetPackageName = resolvedPkgName,
            stagingDirToClean = stagingDir,
            wasAutoSigned = wasAutoSigned || forceSign,
            isDowngrade = isDowngrade,
            forceSign = forceSign,
            fmTaskId = fmTaskId
        )
    }

    /**
     * Checks whether an already-installed package on the device was signed by DDM's persistent
     * on-device `ApkAutoSigner` certificate so updates/downgrades to that app are automatically
     * signed with the exact matching certificate.
     */
    private fun isInstalledAppSignedByDdmAutoSigner(context: Context, packageName: String): Boolean {
        return try {
            val pm = context.packageManager
            val installedCertBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }
                val sigInfo = info.signingInfo
                val sigs = if (sigInfo?.hasMultipleSigners() == true) {
                    sigInfo.apkContentsSigners
                } else {
                    sigInfo?.signingCertificateHistory
                }
                sigs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (installedCertBytes == null) return false
            val ddmCertBytes = ApkAutoSigner.getSignerCertificateBytes(context)
            installedCertBytes.contentEquals(ddmCertBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Filters out conflicting CPU architecture split APKs when a universal `.apks` or `.xapk` archive
     * bundles multiple ABI splits (e.g. both `config.arm64_v8a.apk` and `config.armeabi_v7a.apk`).
     */
    private fun filterCompatibleSplitApks(apkFiles: List<File>): List<File> {
        if (apkFiles.size <= 1) return apkFiles

        fun detectAbiForApk(file: File): String? {
            val lower = file.name.lowercase(Locale.US)
            for ((canonicalAbi, tokens) in KNOWN_ABI_TOKENS) {
                for (token in tokens) {
                    val regex = Regex("(^|[._-])${Regex.escape(token)}([._-]|\\.apk$)")
                    if (regex.containsMatchIn(lower)) {
                        return canonicalAbi
                    }
                }
            }
            return null
        }

        val abiByFile = apkFiles.associateWith { detectAbiForApk(it) }
        val presentAbis = abiByFile.values.filterNotNull().toSet()
        if (presentAbis.isEmpty()) return apkFiles

        val deviceSupportedAbis = Build.SUPPORTED_ABIS?.map { it.lowercase(Locale.US) }.orEmpty()
        val chosenAbi = deviceSupportedAbis.firstOrNull { it in presentAbis } ?: presentAbis.first()

        return apkFiles.filter { file ->
            val fileAbi = abiByFile[file]
            fileAbi == null || fileAbi == chosenAbi
        }
    }

    private suspend fun commitApksViaPackageInstallerSession(
        context: Context,
        task: DownloaderTask,
        apkFiles: List<File>,
        targetPackageName: String?,
        stagingDirToClean: File?,
        wasAutoSigned: Boolean = false,
        isDowngrade: Boolean = false,
        forceSign: Boolean = false,
        fmTaskId: Int = 0
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val totalBytes = apkFiles.sumOf { it.length().coerceAtLeast(0L) }

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
            if (totalBytes > 0L) {
                setSize(totalBytes)
            }
            if (!targetPackageName.isNullOrBlank()) {
                setAppPackageName(targetPackageName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (isDowngrade) {
                runCatching {
                    javaClass.getMethod("setRequestDowngrade", Boolean::class.javaPrimitiveType)
                        .invoke(this, true)
                }
            }
        }

        var sessionId = -1
        var session: PackageInstaller.Session? = null
        try {
            sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEachIndexed { idx, apkFile ->
                DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)
                val statusMsg = "Staging APK ${idx + 1}/${apkFiles.size} (${apkFile.name})..."
                setTaskInstallProgress(task.id, statusMsg)
                if (fmTaskId > 0) {
                    val pct = 68 + (((idx + 1) * 18) / apkFiles.size.coerceAtLeast(1))
                    DragonFileManagerTaskEngine.updatePackageInstallProgress(
                        context = context,
                        taskId = fmTaskId,
                        title = if (isDowngrade) "Staging Downgrade" else "Staging Package",
                        subtitle = statusMsg,
                        currentFileName = apkFile.name,
                        progressPercent = pct,
                        isIndeterminate = false
                    )
                }
                val entryName = "${idx}_${apkFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
                FileInputStream(apkFile).use { input ->
                    session.openWrite(entryName, 0, apkFile.length()).use { out ->
                        input.copyTo(out, 128 * 1024)
                        session.fsync(out)
                    }
                }
            }

            DragonFileManagerTaskEngine.checkPauseOrCancel(fmTaskId)

            setTaskInstallProgress(task.id, "Waiting for user confirmation...")
            if (fmTaskId > 0) {
                DragonFileManagerTaskEngine.updatePackageInstallProgress(
                    context = context,
                    taskId = fmTaskId,
                    title = if (isDowngrade) "Confirm Downgrade" else "Waiting Confirmation",
                    subtitle = "Confirm installation in system dialog",
                    currentFileName = task.filename,
                    progressPercent = 90,
                    isIndeterminate = true
                )
            }

            val callbackIntent = Intent(context, ApkInstallerReceiver::class.java).apply {
                action = ApkInstallerReceiver.ACTION_PACKAGE_INSTALL_STATUS
                setPackage(context.packageName)
                putExtra(ApkInstallerReceiver.EXTRA_TASK_ID, task.id)
                putExtra(ApkInstallerReceiver.EXTRA_FM_TASK_ID, fmTaskId)
                putExtra(ApkInstallerReceiver.EXTRA_FILENAME, task.filename)
                putExtra(ApkInstallerReceiver.EXTRA_PACKAGE_NAME, targetPackageName.orEmpty())
                putExtra(ApkInstallerReceiver.EXTRA_WAS_AUTO_SIGNED, wasAutoSigned)
                putExtra(ApkInstallerReceiver.EXTRA_IS_DOWNGRADE, isDowngrade)
                putExtra(ApkInstallerReceiver.EXTRA_SOURCE_PATH, task.filePath.orEmpty())
                putExtra(ApkInstallerReceiver.EXTRA_PUBLIC_URI, task.publicUriString.orEmpty())
                putExtra(ApkInstallerReceiver.EXTRA_FORCE_SIGN, forceSign)
            }

            val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId and 0x7FFFFFFF,
                callbackIntent,
                pendingFlags
            )

            session.commit(pendingIntent.intentSender)
            session.close()
            session = null

            if (stagingDirToClean != null) {
                runCatching { stagingDirToClean.deleteRecursively() }
            }
        } catch (e: Exception) {
            runCatching { session?.abandon() }
            if (stagingDirToClean != null) {
                runCatching { stagingDirToClean.deleteRecursively() }
            }
            setTaskInstallProgress(task.id, null)
            throw e
        }
    }

    private suspend fun executeRootInstall(
        context: Context,
        task: DownloaderTask,
        forceSign: Boolean,
        isDowngrade: Boolean,
        fmTaskId: Int
    ) {
        val stagingDir = prepareCleanStagingDir(context, "${task.id}_root")
        try {
            val directFile = task.filePath?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?.takeIf { it.exists() && it.canRead() && it.length() > 0L }
            val ext = resolveTaskPackageExtension(task).ifBlank { "apk" }
            val bundleOrApk = directFile ?: stageMediaStoreToTempFile(
                context = context,
                publicUriString = task.publicUriString,
                tempName = "root_pkg_${task.id.hashCode().toUInt().toString(16)}.$ext"
            )
            if (bundleOrApk == null || !bundleOrApk.exists()) {
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = context,
                    taskId = fmTaskId,
                    title = "Root Install Failed",
                    summaryMessage = "Package file not found: ${task.filename}",
                    status = FileManagerTerminalStatus.FAILED
                )
                return
            }

            val isSplit = ext in setOf("apks", "xapk", "apkm") ||
                sniffZipArchivePackageType(bundleOrApk)?.first in setOf("apks", "xapk")

            val apksToInstall = if (!isSplit) {
                val (ready, _) = ApkAutoSigner.ensureApkSigned(context, bundleOrApk, forceSign = forceSign)
                listOf(ready)
            } else {
                val extracted = mutableListOf<File>()
                ZipFile(bundleOrApk).use { zip ->
                    var idx = 0
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        if (!e.isDirectory && e.name.lowercase(Locale.US).endsWith(".apk")) {
                            idx++
                            val outF = File(stagingDir, sanitizeSplitEntryFilename(e.name, idx))
                            zip.getInputStream(e).use { inp ->
                                FileOutputStream(outF).use { out -> inp.copyTo(out, 128 * 1024) }
                            }
                            if (outF.length() > 0L) extracted.add(outF)
                        }
                    }
                }
                val compat = sortBaseApkFirst(filterCompatibleSplitApks(extracted))
                val (ready, _) = ApkAutoSigner.ensureSplitApksSigned(context, compat, forceSign = forceSign)
                sortBaseApkFirst(ready)
            }

            if (apksToInstall.isEmpty()) {
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = context,
                    taskId = fmTaskId,
                    title = "Root Install Failed",
                    summaryMessage = "No APK files found in ${task.filename}",
                    status = FileManagerTerminalStatus.FAILED
                )
                return
            }

            DragonFileManagerTaskEngine.updatePackageInstallProgress(
                context = context,
                taskId = fmTaskId,
                title = "Executing Root Install",
                subtitle = "Running pm install -r -d...",
                currentFileName = task.filename,
                progressPercent = 75,
                isIndeterminate = true
            )

            val result = runRootPmInstall(apksToInstall)
            if (result.first) {
                notifyPackageInstallChanged(task.id)
                val successMsg = "${if (isDowngrade) "Downgraded" else "Installed"} ${task.filename} (Root)"
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = context,
                    taskId = fmTaskId,
                    title = if (isDowngrade) "Downgrade Complete" else "Installation Complete",
                    summaryMessage = successMsg,
                    status = FileManagerTerminalStatus.COMPLETED
                )
                showToastOnMain(context, successMsg)
            } else {
                val failMsg = result.second.ifBlank { "Root pm install failed" }
                DragonFileManagerTaskEngine.finishPackageInstallTask(
                    context = context,
                    taskId = fmTaskId,
                    title = "Root Install Failed",
                    summaryMessage = failMsg,
                    status = FileManagerTerminalStatus.FAILED
                )
                showToastOnMain(context, failMsg)
            }
        } catch (e: Exception) {
            DragonFileManagerTaskEngine.finishPackageInstallTask(
                context = context,
                taskId = fmTaskId,
                title = "Root Install Failed",
                summaryMessage = e.message ?: "Root install error",
                status = FileManagerTerminalStatus.FAILED
            )
        } finally {
            runCatching { stagingDir.deleteRecursively() }
        }
    }

    private fun runRootPmInstall(apkFiles: List<File>): Pair<Boolean, String> {
        return try {
            fun esc(path: String) = "'" + path.replace("'", "'\\''") + "'"
            val cmd = if (apkFiles.size == 1) {
                "pm install -r -d ${esc(apkFiles.first().absolutePath)}"
            } else {
                val totalSize = apkFiles.sumOf { it.length() }
                val sb = StringBuilder()
                sb.append("SID=$(pm install-create -r -d -S $totalSize | grep -oE '[0-9]+' | head -n1)\n")
                sb.append("if [ -z \"\$SID\" ]; then echo 'Failed to create pm install session'; exit 1; fi\n")
                apkFiles.forEachIndexed { idx, file ->
                    val safeName = "${idx}_${file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
                    sb.append("cat ${esc(file.absolutePath)} | pm install-write -S ${file.length()} \"\$SID\" \"$safeName\" -\n")
                }
                sb.append("pm install-commit \"\$SID\"")
                sb.toString()
            }
            val res = com.example.data.root.RootHelper.runRootShellCommand(cmd, timeoutMs = 120_000L)
            val ok = res.exitCode == 0 && res.output.contains("Success", ignoreCase = true)
            ok to res.output
        } catch (e: Exception) {
            false to (e.message ?: "Root package installer execution failed")
        }
    }

    private fun copyObbStreamToDevice(
        context: Context,
        entryPath: String,
        packageNameHint: String?,
        input: InputStream
    ): Boolean {
        return try {
            val normalized = entryPath.replace('\\', '/').trimStart('/')
            val obbFilename = normalized.substringAfterLast('/')
            if (obbFilename.isBlank()) return false

            // Extract package folder from Android/obb/<pkg>/<file>.obb or fallback to packageNameHint
            val pkgFromPath = if (normalized.contains("Android/obb/", ignoreCase = true)) {
                normalized.substringAfter("obb/", "").substringBefore('/', "")
            } else ""
            val targetPkg = pkgFromPath.ifBlank { packageNameHint.orEmpty() }
            if (targetPkg.isBlank()) return false

            @Suppress("DEPRECATION")
            val externalRoot = Environment.getExternalStorageDirectory()
            val primaryObbDir = File(externalRoot, "Android/obb/$targetPkg")
            val fallbackObbDir = context.obbDir?.parentFile?.let { File(it, targetPkg) }
            val targetDir = when {
                runCatching { primaryObbDir.mkdirs() || primaryObbDir.exists() }.getOrDefault(false) && primaryObbDir.canWrite() ->
                    primaryObbDir
                fallbackObbDir != null && runCatching { fallbackObbDir.mkdirs() || fallbackObbDir.exists() }.getOrDefault(false) ->
                    fallbackObbDir
                else -> return false
            }

            val outFile = File(targetDir, obbFilename)
            FileOutputStream(outFile).use { out ->
                input.copyTo(out, 128 * 1024)
            }
            outFile.exists() && outFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun stageMediaStoreToTempFile(
        context: Context,
        publicUriString: String?,
        tempName: String
    ): File? {
        if (publicUriString.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(publicUriString) }.getOrNull() ?: return null
        return try {
            val stagingRoot = File(context.cacheDir, "apk_installer_staging").apply { mkdirs() }
            cleanOldStagingFiles(stagingRoot)
            val targetFile = File(stagingRoot, tempName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { out ->
                    input.copyTo(out, 128 * 1024)
                }
            }
            targetFile.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) {
            null
        }
    }

    private fun prepareCleanStagingDir(context: Context, taskId: String): File {
        val stagingRoot = File(context.cacheDir, "apk_installer_staging").apply { mkdirs() }
        cleanOldStagingFiles(stagingRoot)
        val dir = File(stagingRoot, "bundle_${taskId.hashCode().toUInt().toString(16)}")
        if (dir.exists()) {
            runCatching { dir.deleteRecursively() }
        }
        dir.mkdirs()
        return dir
    }

    private fun cleanOldStagingFiles(stagingRoot: File) {
        val now = System.currentTimeMillis()
        stagingRoot.listFiles()?.forEach { child ->
            if (now - child.lastModified() > 90 * 1000L) {
                runCatching { child.deleteRecursively() }
            }
        }
    }

    private fun sanitizeApkStagingName(filename: String): String {
        val base = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_").takeLast(80).ifBlank { "package.apk" }
        return if (base.endsWith(".apk", ignoreCase = true)) base else "$base.apk"
    }

    private fun sanitizeSplitEntryFilename(entryPath: String, index: Int): String {
        val rawName = entryPath.substringAfterLast('/').ifBlank { "split_$index.apk" }
        val clean = rawName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return if (clean.endsWith(".apk", ignoreCase = true)) clean else "$clean.apk"
    }

    fun showToastOnMain(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
