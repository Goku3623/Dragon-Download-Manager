package com.example.data.filemanager

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Environment
import com.example.data.downloader.ApkAutoSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class InstalledAppRecord(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val totalSizeBytes: Long,
    val lastUpdateTimeMs: Long,
    val isSystemApp: Boolean,
    val iconBitmap: Bitmap?,
    val sha256Cert: String
) {
    val isSplitApk: Boolean get() = splitApkPaths.isNotEmpty()
    val splitCount: Int get() = splitApkPaths.size
}

data class StoredApkRecord(
    val file: File,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val folderBadge: String,
    val iconBitmap: Bitmap?
)

data class ApkZipFileEntry(
    val path: String,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val isStored: Boolean
)

data class ApkEditorProject(
    val sourceApkFile: File,
    val displayTitle: String,
    val iconBitmap: Bitmap?,
    val manifest: ParsedApkManifest,
    val arscStrings: List<ArscStringEntry>,
    val arscTypedResources: List<ArscTypedResourceEntry> = emptyList(),
    val dexSummaries: List<DexInspectionSummary>,
    val zipEntries: List<ApkZipFileEntry>,
    val nativeAbis: List<String>,
    val isSignedValidly: Boolean,
    val signatureSha256: String,
    val launcherIconEntryPaths: List<String>
)

data class ApkStagedEdits(
    val manifestSpec: ApkManifestEditSpec = ApkManifestEditSpec(),
    val rawManifestAxmlOverride: ByteArray? = null,
    val arscStringPatches: Map<Int, String> = emptyMap(),
    val arscTypedValuePatches: Map<Int, Pair<Int, Int>> = emptyMap(), // valueOffset -> (valueType, rawData)
    val dexStringPatches: Map<String, Map<Int, Pair<Int, String>>> = emptyMap(), // dexPath -> (dataOff -> (origByteLen, newText))
    val dexByteOverrides: Map<String, ByteArray> = emptyMap(), // dexPath -> full patched DEX bytes
    val replacementIconBitmap: Bitmap? = null,
    val replacedOrAddedFiles: Map<String, ByteArray> = emptyMap(),
    val renamedEntryPaths: Map<String, String> = emptyMap(), // oldPath -> newPath
    val compressionOverrides: Map<String, Boolean> = emptyMap(), // path -> true=STORED, false=DEFLATED
    val deletedEntryPaths: Set<String> = emptySet(),
    val strippedAbis: Set<String> = emptySet()
) {
    fun totalPendingChangesCount(): Int {
        var count = 0
        if (rawManifestAxmlOverride != null) count++
        if (manifestSpec.newAppLabel != null) count++
        if (manifestSpec.newPackageName != null) count++
        if (manifestSpec.newVersionName != null) count++
        if (manifestSpec.newVersionCode != null) count++
        if (manifestSpec.newMinSdkVersion != null) count++
        if (manifestSpec.newTargetSdkVersion != null) count++
        if (manifestSpec.newInstallLocation != null) count++
        if (manifestSpec.debuggable != null) count++
        if (manifestSpec.allowBackup != null) count++
        if (manifestSpec.usesCleartextTraffic != null) count++
        if (manifestSpec.extractNativeLibs != null) count++
        if (manifestSpec.largeHeap != null) count++
        if (manifestSpec.stripTestOnly) count++
        count += manifestSpec.removedPermissions.size
        count += manifestSpec.addedPermissions.size
        count += manifestSpec.componentExportedOverrides.size
        count += manifestSpec.componentEnabledOverrides.size
        count += manifestSpec.metaDataValueOverrides.size
        count += manifestSpec.addedMetaData.size
        count += manifestSpec.removedMetaData.size
        count += arscStringPatches.size
        count += arscTypedValuePatches.size
        count += dexStringPatches.values.sumOf { it.size }
        count += dexByteOverrides.size
        if (replacementIconBitmap != null) count++
        count += replacedOrAddedFiles.size
        count += renamedEntryPaths.size
        count += compressionOverrides.size
        count += deletedEntryPaths.size
        count += strippedAbis.size
        return count
    }
}

data class ApkBuildResult(
    val success: Boolean,
    val outputFile: File?,
    val message: String,
    val logLines: List<String>
)

/**
 * Unified backend engine for:
 * 1. APK Extractor (extracting installed User/System & Split APKs into `DDM/Extracted Apk`)
 * 2. Professional APK Editor Studio (inspecting, patching Manifest AXML, `resources.arsc`, `classes*.dex`,
 *    App Icons, Assets/Files, ABIs, zipaligning, and V1+V2 Auto-Signing).
 */
object DragonApkStudioEngine {

    const val EXTRACTED_APK_FOLDER_NAME = "Extracted Apk"

    val COMMON_ANDROID_PERMISSIONS = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.WAKE_LOCK",
        "android.permission.VIBRATE",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.BLUETOOTH_SCAN",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.WRITE_SETTINGS"
    )

    val DANGEROUS_PERMISSIONS = setOf(
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.BODY_SENSORS",
        "android.permission.ACTIVITY_RECOGNITION"
    )

    /**
     * Automatically creates and returns the `Extracted Apk` subfolder inside the main DDM directory.
     */
    fun ensureExtractedApkFolder(ddmRootDir: File): File {
        val dir = File(ddmRootDir, EXTRACTED_APK_FOLDER_NAME)
        runCatching {
            ddmRootDir.mkdirs()
            dir.mkdirs()
        }
        return dir
    }

    // =========================================================================
    // 1. INSTALLED APPS SCANNER & APK EXTRACTOR
    // =========================================================================

    fun loadInstalledApps(context: Context): List<InstalledAppRecord> {
        val pm = context.packageManager
        val packages: List<PackageInfo> = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())

        return packages.mapNotNull { pkgInfo ->
            val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
            val basePath = appInfo.publicSourceDir?.takeIf { it.isNotBlank() }
                ?: appInfo.sourceDir?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val splits = (appInfo.splitPublicSourceDirs ?: appInfo.splitSourceDirs)
                ?.filter { it.isNotBlank() }
                .orEmpty()

            val baseFile = File(basePath)
            val totalSize = baseFile.length().coerceAtLeast(0L) +
                splits.sumOf { File(it).length().coerceAtLeast(0L) }

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(pkgInfo.packageName)
                .ifBlank { pkgInfo.packageName }

            val versionName = pkgInfo.versionName ?: "1.0"
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkgInfo.versionCode.toLong()
            }
            val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) appInfo.minSdkVersion else 21
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val iconBmp = runCatching {
                drawableToBitmap(pm.getApplicationIcon(appInfo), 96)
            }.getOrNull()

            InstalledAppRecord(
                appName = label,
                packageName = pkgInfo.packageName,
                versionName = versionName,
                versionCode = versionCode,
                minSdk = minSdk,
                targetSdk = appInfo.targetSdkVersion,
                baseApkPath = basePath,
                splitApkPaths = splits,
                totalSizeBytes = totalSize,
                lastUpdateTimeMs = pkgInfo.lastUpdateTime,
                isSystemApp = isSystem,
                iconBitmap = iconBmp,
                sha256Cert = ""
            )
        }.sortedWith(
            compareBy<InstalledAppRecord> { it.isSystemApp }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.appName }
        )
    }

    /**
     * Extracts an installed app into `outputDir` (`DDM/Extracted Apk`).
     * - For single-APK apps (or when `extractSplitAsBundle == false`): saves `<AppName>_v<Version>.apk`
     * - For split-APK apps when `extractSplitAsBundle == true`: bundles `base.apk` + all `split_*.apk`
     *   into an installable `<AppName>_v<Version>.apks` package!
     */
    fun extractInstalledApp(
        context: Context,
        app: InstalledAppRecord,
        ddmRootDir: File,
        extractSplitAsBundle: Boolean = true,
        onProgress: (Float) -> Unit = {}
    ): File? {
        val outputDir = ensureExtractedApkFolder(ddmRootDir)
        val safeName = sanitizeFilename("${app.appName}_v${app.versionName}")
        val baseFile = File(app.baseApkPath)

        return if (app.isSplitApk && extractSplitAsBundle) {
            val outApks = resolveUniqueFile(outputDir, "$safeName.apks")
            val allFiles = listOf(baseFile) + app.splitApkPaths.map { File(it) }
            val totalBytes = allFiles.sumOf { it.length().coerceAtLeast(1L) }.coerceAtLeast(1L)
            var doneBytes = 0L
            val ok = runCatching {
                ZipOutputStream(FileOutputStream(outApks)).use { zos ->
                    zos.setLevel(0) // Store APK files inside .apks without double-compressing
                    allFiles.forEachIndexed { idx, srcFile ->
                        val entryName = if (idx == 0) "base.apk" else srcFile.name
                        val crc = computeFileCrc(context, srcFile)
                        val fileLen = srcFile.length().coerceAtLeast(0L)
                        val entry = ZipEntry(entryName).apply {
                            method = ZipEntry.STORED
                            size = fileLen
                            compressedSize = fileLen
                            this.crc = crc
                        }
                        zos.putNextEntry(entry)
                        val inStream = openApkSourceStream(context, srcFile) ?: throw IllegalStateException("Cannot read ${srcFile.name}")
                        inStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var r = input.read(buf)
                            while (r > 0) {
                                zos.write(buf, 0, r)
                                doneBytes += r
                                onProgress((doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                                r = input.read(buf)
                            }
                        }
                        zos.closeEntry()
                    }
                }
                true
            }.getOrDefault(false)
            if (ok && outApks.exists() && outApks.length() > 0L) {
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(outputDir.absolutePath)
                outApks
            } else {
                runCatching { outApks.delete() }
                null
            }
        } else {
            val suffix = if (app.isSplitApk) "_base.apk" else ".apk"
            val outApk = resolveUniqueFile(outputDir, "$safeName$suffix")
            val totalBytes = baseFile.length().coerceAtLeast(1L)
            var doneBytes = 0L
            val ok = runCatching {
                val inStream = openApkSourceStream(context, baseFile) ?: return null
                inStream.use { input ->
                    FileOutputStream(outApk).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var r = input.read(buf)
                        while (r > 0) {
                            out.write(buf, 0, r)
                            doneBytes += r
                            onProgress((doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            r = input.read(buf)
                        }
                        out.flush()
                    }
                }
                true
            }.getOrDefault(false)
            if (ok && outApk.exists() && outApk.length() > 0L) {
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(outputDir.absolutePath)
                outApk
            } else {
                runCatching { outApk.delete() }
                null
            }
        }
    }

    /**
     * Copies an installed app's base APK into a fast cache workspace file so APK Editor Pro can open it immediately.
     */
    fun stageInstalledAppForEditing(context: Context, app: InstalledAppRecord): File? {
        val stageDir = File(context.cacheDir, "apk_editor_stage").apply { mkdirs() }
        // Evict previous staged APK copies so cacheDir never accumulates multiple base APKs
        runCatching {
            stageDir.listFiles()?.forEach { old -> runCatching { old.deleteRecursively() } }
        }
        val safeName = sanitizeFilename("${app.appName}_v${app.versionName}") + ".apk"
        val dest = File(stageDir, safeName)
        val baseFile = File(app.baseApkPath)
        val ok = runCatching {
            val inStream = openApkSourceStream(context, baseFile) ?: return null
            inStream.use { input ->
                FileOutputStream(dest).use { out ->
                    input.copyTo(out, 64 * 1024)
                    out.flush()
                }
            }
            true
        }.getOrDefault(false)
        return if (ok && dest.exists() && dest.length() > 0L) dest else null
    }

    // =========================================================================
    // 2. STORED & DOWNLOADED APK SCANNER
    // =========================================================================

    fun scanStoredAndDownloadedApks(context: Context, ddmRootDir: File): List<StoredApkRecord> {
        val pm = context.packageManager
        val searchRoots: List<Pair<File, String>> = listOfNotNull(
            ensureExtractedApkFolder(ddmRootDir) to "Extracted Apk",
            File(ddmRootDir, "Program") to "DDM/Program",
            ddmRootDir to "DDM",
            runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
                .getOrNull()
                ?.let { it to "Downloads" }
        )

        val foundByPath = LinkedHashMap<String, Pair<File, String>>()
        for ((dir, badge) in searchRoots) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.extension.equals("apk", ignoreCase = true)) {
                    foundByPath.putIfAbsent(f.absolutePath, f to badge)
                }
            }
        }

        return foundByPath.values.map { (file, badge) ->
            val pkgInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(file.absolutePath, 0)
                }
            }.getOrNull()

            val appInfo = pkgInfo?.applicationInfo?.apply {
                sourceDir = file.absolutePath
                publicSourceDir = file.absolutePath
            }
            val label = if (appInfo != null) {
                runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(file.nameWithoutExtension)
            } else {
                file.nameWithoutExtension
            }
            val iconBmp = if (appInfo != null) {
                runCatching { drawableToBitmap(pm.getApplicationIcon(appInfo), 96) }.getOrNull()
            } else {
                null
            }
            val verCode = if (pkgInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkgInfo.longVersionCode
                else @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
            } else 1L

            StoredApkRecord(
                file = file,
                appName = label.ifBlank { file.nameWithoutExtension },
                packageName = pkgInfo?.packageName ?: "Unknown package",
                versionName = pkgInfo?.versionName ?: "1.0",
                versionCode = verCode,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified(),
                folderBadge = badge,
                iconBitmap = iconBmp
            )
        }.sortedByDescending { it.lastModifiedMs }
    }

    // =========================================================================
    // 3. PROFESSIONAL APK EDITOR PROJECT LOADER
    // =========================================================================

    fun loadApkProject(context: Context, apkFile: File): ApkEditorProject? {
        if (!apkFile.exists() || apkFile.length() < 64L) return null
        val pm = context.packageManager
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
        }.getOrNull()
        val appInfo = pkgInfo?.applicationInfo?.apply {
            sourceDir = apkFile.absolutePath
            publicSourceDir = apkFile.absolutePath
        }
        val pmLabel = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }.orEmpty()
        val iconBmp = appInfo?.let { runCatching { drawableToBitmap(pm.getApplicationIcon(it), 128) }.getOrNull() }

        return runCatching {
            ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return null
                val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                val parsedManifest = DragonAxmlAndDexEngine.parseManifest(manifestBytes, fallbackAppLabel = pmLabel)
                    ?: return null

                val arscEntry = zip.getEntry("resources.arsc")
                val arscBytes = if (arscEntry != null) {
                    runCatching { zip.getInputStream(arscEntry).use { it.readBytes() } }.getOrNull()
                } else null
                val arscStrings = if (arscBytes != null) {
                    runCatching { DragonAxmlAndDexEngine.parseArscStrings(arscBytes) }.getOrDefault(emptyList())
                } else emptyList()
                val arscTypedResources = if (arscBytes != null) {
                    runCatching { DragonAxmlCompilerEngine.parseArscTypedResources(arscBytes) }.getOrDefault(emptyList())
                } else emptyList()

                val zipEntries = mutableListOf<ApkZipFileEntry>()
                val dexSummaries = mutableListOf<DexInspectionSummary>()
                val abis = LinkedHashSet<String>()
                val launcherIcons = mutableListOf<String>()

                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val e = en.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/')
                    zipEntries.add(
                        ApkZipFileEntry(
                            path = name,
                            sizeBytes = e.size.coerceAtLeast(0L),
                            compressedBytes = e.compressedSize.coerceAtLeast(0L),
                            isStored = e.method == ZipEntry.STORED
                        )
                    )
                    if (name.startsWith("lib/") && name.count { it == '/' } >= 2) {
                        val abi = name.removePrefix("lib/").substringBefore('/')
                        if (abi.isNotBlank()) abis.add(abi)
                    }
                    if (name.matches(Regex("^classes\\d*\\.dex$"))) {
                        runCatching {
                            val dexBytes = zip.getInputStream(e).use { it.readBytes() }
                            DragonAxmlAndDexEngine.inspectDex(name, dexBytes, maxStrings = 1800)?.let {
                                dexSummaries.add(it)
                            }
                        }
                    }
                    val lower = name.lowercase(Locale.US)
                    if ((lower.startsWith("res/mipmap") || lower.startsWith("res/drawable")) &&
                        (lower.endsWith(".png") || lower.endsWith(".webp")) &&
                        (lower.contains("ic_launcher") || lower.contains("app_icon") || lower.contains("icon_launcher"))
                    ) {
                        launcherIcons.add(name)
                    }
                }

                val isSigned = ApkAutoSigner.isApkValidlySigned(context, apkFile)
                val sha256 = computeApkCertSha256(context, apkFile)

                ApkEditorProject(
                    sourceApkFile = apkFile,
                    displayTitle = parsedManifest.appLabel.ifBlank { pmLabel.ifBlank { apkFile.nameWithoutExtension } },
                    iconBitmap = iconBmp,
                    manifest = parsedManifest,
                    arscStrings = arscStrings,
                    arscTypedResources = arscTypedResources,
                    dexSummaries = dexSummaries.sortedBy { it.entryPath },
                    zipEntries = zipEntries.sortedBy { it.path },
                    nativeAbis = abis.toList().sorted(),
                    isSignedValidly = isSigned,
                    signatureSha256 = sha256,
                    launcherIconEntryPaths = launcherIcons
                )
            }
        }.getOrNull()
    }

    fun readApkEntryBytes(apkFile: File, entryPath: String): ByteArray? {
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(entryPath) ?: return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
        }.getOrNull()
    }

    // =========================================================================
    // 4. PROFESSIONAL APK BUILD, PATCH, ZIPALIGN & V1+V2 SIGN PIPELINE
    // =========================================================================

    fun buildEditedApk(
        context: Context,
        project: ApkEditorProject,
        edits: ApkStagedEdits,
        outputFile: File,
        signWithV1AndV2: Boolean = true,
        onStatus: (String) -> Unit = {}
    ): ApkBuildResult {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            logs.add(msg)
            onStatus(msg)
        }

        val stagingDir = File(context.cacheDir, "apk_editor_build").apply { mkdirs() }
        val tempUnsigned = File(stagingDir, "unsigned_${System.currentTimeMillis()}.apk")
        val tempFinal = File(stagingDir, "final_${System.currentTimeMillis()}.apk")

        try {
            log("Opening source package: ${project.sourceApkFile.name}")
            val iconTargets = project.launcherIconEntryPaths.toSet()

            ZipFile(project.sourceApkFile).use { srcZip ->
                ZipOutputStream(FileOutputStream(tempUnsigned)).use { zos ->
                    val writtenPaths = HashSet<String>()

                    val entries = srcZip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val path = entry.name.replace('\\', '/')
                        val upper = path.uppercase(Locale.US)

                        // Strip old META-INF signatures so new V1+V2 signing is 100% clean
                        if (signWithV1AndV2 && (upper == "META-INF/MANIFEST.MF" ||
                                (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))))
                        ) {
                            continue
                        }

                        // Skip deleted entries
                        if (path in edits.deletedEntryPaths) {
                            log("Removed entry: $path")
                            continue
                        }

                        // Strip unselected native ABIs
                        if (path.startsWith("lib/") && edits.strippedAbis.isNotEmpty()) {
                            val abi = path.removePrefix("lib/").substringBefore('/')
                            if (abi in edits.strippedAbis) {
                                continue
                            }
                        }

                        val finalPath = edits.renamedEntryPaths[path] ?: path
                        if (finalPath != path) {
                            log("Renamed entry: $path -> $finalPath")
                        }

                        // Determine final bytes for this entry
                        val replacementBytes: ByteArray? = when {
                            path == "AndroidManifest.xml" && edits.rawManifestAxmlOverride != null -> {
                                log("Applying recompiled binary AndroidManifest.xml (AXML)...")
                                if (edits.manifestSpec.hasAnyChanges()) {
                                    DragonAxmlAndDexEngine.applyManifestEdits(edits.rawManifestAxmlOverride, edits.manifestSpec)
                                } else {
                                    edits.rawManifestAxmlOverride
                                }
                            }
                            edits.dexByteOverrides.containsKey(path) -> {
                                log("Applying patched DEX: $path (SHA-1 & Adler32 verified)")
                                edits.dexByteOverrides[path]
                            }
                            edits.replacedOrAddedFiles.containsKey(path) -> {
                                log("Replaced file: $path")
                                edits.replacedOrAddedFiles[path]
                            }
                            edits.replacedOrAddedFiles.containsKey(finalPath) -> {
                                log("Replaced renamed file: $finalPath")
                                edits.replacedOrAddedFiles[finalPath]
                            }
                            path == "AndroidManifest.xml" && edits.manifestSpec.hasAnyChanges() -> {
                                log("Patching binary AndroidManifest.xml (AXML)...")
                                val orig = srcZip.getInputStream(entry).use { it.readBytes() }
                                DragonAxmlAndDexEngine.applyManifestEdits(orig, edits.manifestSpec)
                            }
                            path == "resources.arsc" && (edits.arscTypedValuePatches.isNotEmpty() || edits.arscStringPatches.isNotEmpty()) -> {
                                log("Patching resources.arsc (${edits.arscTypedValuePatches.size} typed value(s), ${edits.arscStringPatches.size} string(s))...")
                                var arscData = srcZip.getInputStream(entry).use { it.readBytes() }
                                // Apply typed value offset patches FIRST before string pool resizing shifts downstream offsets!
                                if (edits.arscTypedValuePatches.isNotEmpty()) {
                                    arscData = DragonAxmlCompilerEngine.patchArscTypedValues(arscData, edits.arscTypedValuePatches)
                                }
                                if (edits.arscStringPatches.isNotEmpty()) {
                                    arscData = DragonAxmlAndDexEngine.patchArscStrings(arscData, edits.arscStringPatches)
                                }
                                arscData
                            }
                            edits.dexStringPatches.containsKey(path) -> {
                                val patches = edits.dexStringPatches[path].orEmpty()
                                log("Patching $path (${patches.size} string(s)) + recalculating SHA-1/Adler32...")
                                val orig = srcZip.getInputStream(entry).use { it.readBytes() }
                                DragonAxmlAndDexEngine.patchDexStringsInPlace(orig, patches)
                            }
                            edits.replacementIconBitmap != null && path in iconTargets -> {
                                val origBytes = srcZip.getInputStream(entry).use { it.readBytes() }
                                encodeScaledIconMatchingEntry(edits.replacementIconBitmap, path, origBytes)
                            }
                            else -> null
                        }

                        val mustStore = edits.compressionOverrides[finalPath]
                            ?: edits.compressionOverrides[path]
                            ?: (finalPath == "resources.arsc" ||
                                finalPath.lowercase(Locale.US).endsWith(".so") ||
                                entry.method == ZipEntry.STORED)

                        if (replacementBytes != null) {
                            writeZipEntryBytes(zos, finalPath, replacementBytes, storeUncompressed = mustStore)
                        } else {
                            val origBytes = srcZip.getInputStream(entry).use { it.readBytes() }
                            writeZipEntryBytes(zos, finalPath, origBytes, storeUncompressed = mustStore)
                        }
                        writtenPaths.add(finalPath)
                    }

                    // Write newly added files & newly added DEX files that weren't in the original APK
                    val combinedNewFiles = LinkedHashMap<String, ByteArray>()
                    combinedNewFiles.putAll(edits.replacedOrAddedFiles)
                    combinedNewFiles.putAll(edits.dexByteOverrides)
                    combinedNewFiles.forEach { (newPath, newBytes) ->
                        if (newPath !in writtenPaths && newPath !in edits.deletedEntryPaths) {
                            log("Added new file: $newPath")
                            val mustStore = edits.compressionOverrides[newPath]
                                ?: (newPath == "resources.arsc" || newPath.lowercase(Locale.US).endsWith(".so"))
                            writeZipEntryBytes(zos, newPath, newBytes, storeUncompressed = mustStore)
                            writtenPaths.add(newPath)
                        }
                    }

                    if (edits.replacementIconBitmap != null && iconTargets.isNotEmpty()) {
                        log("Updated ${iconTargets.size} launcher icon asset(s)")
                    }
                    if (edits.strippedAbis.isNotEmpty()) {
                        log("Stripped native ABIs: ${edits.strippedAbis.joinToString(", ")}")
                    }
                }
            }

            // Step 2: Zipalign & V1+V2 Sign
            val sourceForCopy = if (signWithV1AndV2) {
                log("Zipaligning (4B / 4KB) & Signing with V1 JAR + V2 APK Signature Scheme...")
                ApkAutoSigner.signApkWithV1AndV2(context, tempUnsigned, tempFinal)
                tempFinal
            } else {
                log("Build complete (unsigned APK mode)")
                tempUnsigned
            }

            if (!sourceForCopy.exists() || sourceForCopy.length() <= 0L) {
                return ApkBuildResult(false, null, "Failed to generate output APK file.", logs)
            }

            // Step 3: Write to target destination (Root / standard storage aware)
            log("Saving output APK to ${outputFile.absolutePath}...")
            outputFile.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                context = context,
                source = sourceForCopy,
                dest = outputFile,
                isDirectoryHint = false
            )
            if (!copied) {
                return ApkBuildResult(false, null, "Could not write output APK to ${outputFile.parentFile?.name}", logs)
            }

            outputFile.parentFile?.absolutePath?.let {
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(it)
            }
            log("SUCCESS! Built ${outputFile.name} (${com.example.data.model.formatBytes(outputFile.length())})")
            return ApkBuildResult(true, outputFile, "Successfully built & signed ${outputFile.name}", logs)
        } catch (e: Exception) {
            log("ERROR: ${e.message ?: "Unknown build failure"}")
            return ApkBuildResult(false, null, "APK build failed: ${e.message}", logs)
        } finally {
            runCatching { tempUnsigned.delete() }
            runCatching { tempFinal.delete() }
            com.example.data.cache.DragonSmartCacheController.runSmartMaintenanceAsync(
                context = context.applicationContext,
                aggressive = false,
                force = true
            )
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun encodeScaledIconMatchingEntry(
        replacement: Bitmap,
        entryPath: String,
        originalEntryBytes: ByteArray
    ): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(originalEntryBytes, 0, originalEntryBytes.size, opts) }
        val fallbackDim = when {
            entryPath.contains("xxxhdpi") -> 192
            entryPath.contains("xxhdpi") -> 144
            entryPath.contains("xhdpi") -> 96
            entryPath.contains("hdpi") -> 72
            entryPath.contains("mdpi") -> 48
            else -> 192
        }
        val targetW = if (opts.outWidth in 24..512) opts.outWidth else fallbackDim
        val targetH = if (opts.outHeight in 24..512) opts.outHeight else fallbackDim
        val scaled = Bitmap.createScaledBitmap(replacement, targetW, targetH, true)
        val out = ByteArrayOutputStream()
        val isWebp = entryPath.lowercase(Locale.US).endsWith(".webp")
        if (isWebp && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, out)
        } else {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return out.toByteArray()
    }

    private fun writeZipEntryBytes(
        zos: ZipOutputStream,
        path: String,
        bytes: ByteArray,
        storeUncompressed: Boolean
    ) {
        val entry = ZipEntry(path)
        if (storeUncompressed) {
            val crc = CRC32().apply { update(bytes) }.value
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc
        } else {
            entry.method = ZipEntry.DEFLATED
        }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun openApkSourceStream(context: Context, file: File): java.io.InputStream? {
        if (file.exists() && file.canRead()) {
            return FileInputStream(file)
        }
        return AndroidDataObbBridge.openInputStreamForFile(context, file)
    }

    private fun computeFileCrc(context: Context, file: File): Long {
        val crc = CRC32()
        openApkSourceStream(context, file)?.use { input ->
            val buf = ByteArray(64 * 1024)
            var r = input.read(buf)
            while (r > 0) {
                crc.update(buf, 0, r)
                r = input.read(buf)
            }
        }
        return crc.value
    }

    private fun computeApkCertSha256(context: Context, apkFile: File): String {
        return runCatching {
            val pm = context.packageManager
            val certBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                }
                val s = info?.signingInfo
                val sig = if (s?.hasMultipleSigners() == true) s.apkContentsSigners?.firstOrNull()
                else s?.signingCertificateHistory?.firstOrNull()
                sig?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info?.signatures?.firstOrNull()?.toByteArray()
            } ?: return ""
            val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
            digest.joinToString(":") { "%02X".format(it) }
        }.getOrDefault("")
    }

    private fun drawableToBitmap(drawable: Drawable, maxSizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val b = drawable.bitmap
            return if (b.width > maxSizePx || b.height > maxSizePx) {
                Bitmap.createScaledBitmap(b, maxSizePx, maxSizePx, true)
            } else {
                b
            }
        }
        val w = drawable.intrinsicWidth.coerceIn(32, maxSizePx)
        val h = drawable.intrinsicHeight.coerceIn(32, maxSizePx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    private fun sanitizeFilename(raw: String): String {
        return raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(72)
            .ifBlank { "ExtractedApp" }
    }

    private fun resolveUniqueFile(dir: File, desiredName: String): File {
        val candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val base = desiredName.substringBeforeLast('.')
        val ext = desiredName.substringAfterLast('.', "")
        var idx = 1
        while (idx < 1000) {
            val next = File(dir, if (ext.isNotEmpty()) "${base}_$idx.$ext" else "${base}_$idx")
            if (!next.exists()) return next
            idx++
        }
        return File(dir, "${base}_${System.currentTimeMillis()}.$ext")
    }
}
