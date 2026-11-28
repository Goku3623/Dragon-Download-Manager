package com.example.data.filemanager

import android.content.Context
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream
import org.apache.commons.compress.archivers.cpio.CpioArchiveOutputStream
import org.apache.commons.compress.archivers.jar.JarArchiveEntry
import org.apache.commons.compress.archivers.jar.JarArchiveOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream
import org.apache.commons.compress.compressors.lzma.LZMACompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

enum class UniversalCompressionFormat(
    val label: String,
    val extensionSuffix: String,
    val description: String,
    val supportsLevel: Boolean = true,
    val isSingleStreamWrapper: Boolean = false
) {
    ZIP("ZIP (.zip)", ".zip", "Universal Deflate / Store archive compatible with all devices", true),
    SEVEN_Z("7-Zip (.7z)", ".7z", "High-ratio LZMA2 / BZip2 / Deflate 7z archive", true),
    TAR("TAR (.tar)", ".tar", "Uncompressed POSIX LongFile TAR archive", false),
    TAR_GZ("TAR.GZ (.tar.gz)", ".tar.gz", "GZIP-compressed TAR archive (Linux / Android standard)", true),
    TAR_BZ2("TAR.BZ2 (.tar.bz2)", ".tar.bz2", "BZip2 high-compression TAR archive", true),
    TAR_XZ("TAR.XZ (.tar.xz)", ".tar.xz", "XZ LZMA2 ultra-compression TAR archive", true),
    TAR_ZST("TAR.ZST (.tar.zst)", ".tar.zst", "Zstandard ultra-fast modern TAR archive", true),
    TAR_LZ4("TAR.LZ4 (.tar.lz4)", ".tar.lz4", "Framed LZ4 real-time extreme speed TAR archive", false),
    JAR("JAR (.jar)", ".jar", "Java Archive with Manifest structure", true),
    APK("APK (.apk)", ".apk", "Android Package Archive container", true),
    XAPK("XAPK (.xapk)", ".xapk", "Split APK + OBB bundle container", true),
    APKS("APKS (.apks)", ".apks", "Android Split APK Set archive", true),
    CPIO("CPIO (.cpio)", ".cpio", "Unix / Ramdisk CPIO binary archive", false),
    GZIP("GZIP (.gz)", ".gz", "Single-stream GZIP compression (auto-TARs multiple files)", true, true),
    BZIP2("BZIP2 (.bz2)", ".bz2", "Single-stream BZip2 compression (auto-TARs multiple files)", true, true),
    XZ("XZ (.xz)", ".xz", "Single-stream XZ LZMA2 compression (auto-TARs multiple files)", true, true),
    ZSTD("ZSTD (.zst)", ".zst", "Single-stream Zstandard compression (auto-TARs multiple files)", true, true),
    LZ4("LZ4 (.lz4)", ".lz4", "Single-stream Framed LZ4 compression (auto-TARs multiple files)", false, true),
    LZMA("LZMA (.lzma)", ".lzma", "Single-stream raw LZMA compression (auto-TARs multiple files)", false, true)
}

enum class CompressionLevelPreset(
    val label: String,
    val shortTag: String,
    val zipLevel: Int,
    val xzPreset: Int,
    val zstdLevel: Int,
    val bzipBlockSize: Int,
    val sevenZMethod: SevenZMethod
) {
    STORE(
        label = "Store (0 - No Compression)",
        shortTag = "Store",
        zipLevel = Deflater.NO_COMPRESSION,
        xzPreset = 0,
        zstdLevel = 1,
        bzipBlockSize = 1,
        sevenZMethod = SevenZMethod.COPY
    ),
    FASTEST(
        label = "Fastest (Level 1)",
        shortTag = "Fastest",
        zipLevel = Deflater.BEST_SPEED,
        xzPreset = 1,
        zstdLevel = 1,
        bzipBlockSize = 1,
        sevenZMethod = SevenZMethod.DEFLATE
    ),
    FAST(
        label = "Fast (Level 3)",
        shortTag = "Fast",
        zipLevel = 3,
        xzPreset = 3,
        zstdLevel = 3,
        bzipBlockSize = 3,
        sevenZMethod = SevenZMethod.DEFLATE
    ),
    NORMAL(
        label = "Normal (Level 6 - Default)",
        shortTag = "Normal",
        zipLevel = Deflater.DEFAULT_COMPRESSION,
        xzPreset = 6,
        zstdLevel = 6,
        bzipBlockSize = 6,
        sevenZMethod = SevenZMethod.LZMA2
    ),
    MAXIMUM(
        label = "Maximum (Level 7)",
        shortTag = "Max",
        zipLevel = 7,
        xzPreset = 7,
        zstdLevel = 12,
        bzipBlockSize = 8,
        sevenZMethod = SevenZMethod.LZMA2
    ),
    ULTRA(
        label = "Ultra (Level 9 - Best Ratio)",
        shortTag = "Ultra",
        zipLevel = Deflater.BEST_COMPRESSION,
        xzPreset = 9,
        zstdLevel = 19,
        bzipBlockSize = 9,
        sevenZMethod = SevenZMethod.LZMA2
    )
}

data class CompressionJobConfig(
    val archiveBaseName: String,
    val format: UniversalCompressionFormat = UniversalCompressionFormat.ZIP,
    val levelPreset: CompressionLevelPreset = CompressionLevelPreset.NORMAL,
    val includeHiddenFiles: Boolean = true,
    val preserveRootFolderStructure: Boolean = true,
    val excludePatternCsv: String = "",
    val archiveComment: String = "",
    val deleteSourceAfterCompression: Boolean = false
) {
    fun resolveFullOutputFileName(singleSourceFileName: String? = null): String {
        val cleanBase = archiveBaseName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Archive" }
        UniversalCompressionFormat.entries.forEach { fmt ->
            if (cleanBase.lowercase(Locale.US).endsWith(fmt.extensionSuffix)) {
                val stripped = cleanBase.dropLast(fmt.extensionSuffix.length).ifBlank { "Archive" }
                return stripped + format.extensionSuffix
            }
        }
        if (format.isSingleStreamWrapper && singleSourceFileName != null && !cleanBase.contains(".")) {
            return "$singleSourceFileName${format.extensionSuffix}"
        }
        return if (cleanBase.lowercase(Locale.US).endsWith(format.extensionSuffix)) {
            cleanBase
        } else {
            cleanBase + format.extensionSuffix
        }
    }
}

data class ArchiveInternalNode(
    val fullEntryPath: String,
    val name: String,
    val parentPath: String,
    val isDirectory: Boolean,
    val uncompressedSize: Long,
    val compressedSize: Long,
    val lastModifiedMs: Long,
    val childCount: Int = 0
)

/**
 * Universal Archive Browser, Direct Copy/Paste Engine (without full decompression),
 * Universal Multi-Format Decompressor, and Detailed Multi-Format Compressor.
 *
 * Supported Decompression & Direct In-Archive Browsing Formats:
 * - ZIP, APK, APKS, XAPK, APKM, JAR, WAR, EAR, AAB, OBB, CBZ, EPUB
 * - 7Z (.7z)
 * - RAR (.rar, .cbr)
 * - TAR (.tar)
 * - TAR.GZ / TGZ (.tar.gz, .tgz)
 * - TAR.BZ2 / TBZ2 (.tar.bz2, .tbz2, .tbz)
 * - TAR.XZ / TXZ (.tar.xz, .txz)
 * - TAR.ZST / TZST (.tar.zst, .tzst)
 * - TAR.LZ4 (.tar.lz4)
 * - TAR.LZMA (.tar.lzma)
 * - CPIO (.cpio)
 * - AR / DEB (.ar, .deb, .a)
 * - Standalone GZ (.gz), BZ2 (.bz2), XZ (.xz), ZST (.zst), LZ4 (.lz4), LZMA (.lzma)
 */
object UniversalArchiveEngine {

    private val COMPOUND_ARCHIVE_SUFFIXES = listOf(
        ".tar.gz", ".tar.bz2", ".tar.xz", ".tar.zst", ".tar.lz4", ".tar.lzma"
    )

    private val BROWSEABLE_ARCHIVE_EXTENSIONS = setOf(
        "zip", "7z", "rar", "tar", "tgz", "tbz2", "tbz", "txz", "tzst",
        "gz", "bz2", "xz", "zst", "lz4", "lzma",
        "cpio", "ar", "deb", "a",
        "jar", "war", "ear", "apk", "apks", "xapk", "apkm", "aab", "obb", "cbz", "cbr", "epub",
        "bin", "iso"
    )

    fun isSupportedArchiveFile(file: File): Boolean {
        return isSupportedArchiveFileName(file.name)
    }

    fun isSupportedArchiveFileName(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.US)
        if (COMPOUND_ARCHIVE_SUFFIXES.any { lower.endsWith(it) }) return true
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in BROWSEABLE_ARCHIVE_EXTENSIONS
    }

    fun isPrimaryArchiveForDirectBrowse(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.US)
        if (COMPOUND_ARCHIVE_SUFFIXES.any { lower.endsWith(it) }) return true
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        // Note: .apk runs the package action dialog on primary tap (with "Browse Inside Package" option),
        // while pure archives, bundles, and .bin/.iso containers can be browsed directly inside File Manager!
        return ext in (BROWSEABLE_ARCHIVE_EXTENSIONS - "apk")
    }

    fun stripArchiveExtension(fileName: String): String {
        val lower = fileName.lowercase(Locale.US)
        for (compound in COMPOUND_ARCHIVE_SUFFIXES) {
            if (lower.endsWith(compound)) {
                return fileName.dropLast(compound.length).ifBlank { "extracted" }
            }
        }
        val dotIdx = fileName.lastIndexOf('.')
        return if (dotIdx > 0) fileName.substring(0, dotIdx) else fileName
    }

    fun detectFormatLabel(file: File): String {
        val lower = file.name.lowercase(Locale.US)
        return when {
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "TAR.GZ"
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") -> "TAR.BZ2"
            lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> "TAR.XZ"
            lower.endsWith(".tar.zst") || lower.endsWith(".tzst") -> "TAR.ZST"
            lower.endsWith(".tar.lz4") -> "TAR.LZ4"
            lower.endsWith(".tar.lzma") -> "TAR.LZMA"
            lower.endsWith(".bin") -> UniversalBinContainerEngine.detectBinContainerType(file).label
            else -> file.extension.uppercase(Locale.US).ifBlank { "ARCHIVE" }
        }
    }

    /**
     * Lists all flat entries inside any supported compressed file without decompressing it to storage,
     * and synthesizes intermediate virtual directories so the user can navigate inside the archive
     * folder-by-folder just like normal directories.
     */
    fun listVirtualDirectoryInsideArchive(
        context: Context,
        archiveFile: File,
        virtualDirPath: String
    ): List<ArchiveInternalNode> {
        val allNodes = buildCompleteVirtualTree(context, archiveFile)
        val normalizedParent = normalizeArchivePath(virtualDirPath)
        return allNodes
            .filter { it.parentPath == normalizedParent }
            .sortedWith(
                compareByDescending<ArchiveInternalNode> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )
    }

    /**
     * Builds the complete virtual directory tree (files + synthesized parent folders with accurate child counts)
     * for any archive format.
     */
    fun buildCompleteVirtualTree(
        context: Context,
        archiveFile: File
    ): List<ArchiveInternalNode> {
        val rawEntries = readRawArchiveEntries(context, archiveFile)
        val nodeByPath = LinkedHashMap<String, ArchiveInternalNode>()
        val folderSizes = HashMap<String, Long>()
        val folderTimestamps = HashMap<String, Long>()

        fun ensureParentFolders(path: String, timestamp: Long, fileSize: Long) {
            var cursor = path
            while (cursor.contains('/')) {
                val parent = cursor.substringBeforeLast('/')
                if (parent.isBlank()) break
                folderSizes[parent] = (folderSizes[parent] ?: 0L) + fileSize.coerceAtLeast(0L)
                val prevTs = folderTimestamps[parent] ?: 0L
                if (timestamp > prevTs) folderTimestamps[parent] = timestamp

                if (!nodeByPath.containsKey(parent)) {
                    val folderName = parent.substringAfterLast('/')
                    val grandParent = if (parent.contains('/')) parent.substringBeforeLast('/') else ""
                    nodeByPath[parent] = ArchiveInternalNode(
                        fullEntryPath = parent,
                        name = folderName,
                        parentPath = grandParent,
                        isDirectory = true,
                        uncompressedSize = 0L,
                        compressedSize = 0L,
                        lastModifiedMs = timestamp.takeIf { it > 0L } ?: archiveFile.lastModified()
                    )
                }
                cursor = parent
            }
        }

        rawEntries.forEach { raw ->
            val cleanPath = normalizeArchivePath(raw.fullEntryPath)
            if (cleanPath.isBlank()) return@forEach
            val name = cleanPath.substringAfterLast('/')
            val parent = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else ""
            val ts = raw.lastModifiedMs.takeIf { it > 0L } ?: archiveFile.lastModified()

            ensureParentFolders(cleanPath, ts, if (raw.isDirectory) 0L else raw.uncompressedSize)

            val existing = nodeByPath[cleanPath]
            if (existing == null || !existing.isDirectory) {
                nodeByPath[cleanPath] = ArchiveInternalNode(
                    fullEntryPath = cleanPath,
                    name = name,
                    parentPath = parent,
                    isDirectory = raw.isDirectory,
                    uncompressedSize = raw.uncompressedSize.coerceAtLeast(0L),
                    compressedSize = raw.compressedSize.coerceAtLeast(0L),
                    lastModifiedMs = ts
                )
            }
        }

        // Compute direct child counts & accumulated folder sizes
        val directChildrenCount = HashMap<String, Int>()
        nodeByPath.values.forEach { node ->
            directChildrenCount[node.parentPath] = (directChildrenCount[node.parentPath] ?: 0) + 1
        }

        return nodeByPath.values.map { node ->
            if (node.isDirectory) {
                node.copy(
                    uncompressedSize = folderSizes[node.fullEntryPath] ?: 0L,
                    lastModifiedMs = folderTimestamps[node.fullEntryPath] ?: node.lastModifiedMs,
                    childCount = directChildrenCount[node.fullEntryPath] ?: 0
                )
            } else {
                node
            }
        }
    }

    /**
     * Copies selected internal archive paths (files and/or virtual folders) directly from inside the
     * compressed archive to `targetDir` WITHOUT decompressing the entire archive!
     */
    fun copyEntriesFromArchiveDirectToDirectory(
        context: Context,
        archiveFile: File,
        selectedEntryPaths: List<String>,
        targetDir: File,
        checkConflicts: Boolean = true,
        onSkipped: () -> Unit = {},
        onProgress: (Long, String) -> Unit = { _, _ -> }
    ): Int {
        if (selectedEntryPaths.isEmpty()) return 0
        val normalizedTargets = selectedEntryPaths
            .map { normalizeArchivePath(it) }
            .filter { it.isNotBlank() }
        if (normalizedTargets.isEmpty()) return 0

        AndroidDataObbBridge.createDirectory(context, targetDir)

        // Map each target prefix to its base parent path so relative subpaths are preserved cleanly
        // e.g. if user copies "assets/textures/hero.png", it pastes as "<targetDir>/hero.png"
        // if user copies folder "assets/textures", then "assets/textures/ui/btn.png" pastes as "<targetDir>/textures/ui/btn.png"
        fun resolveRelativeDestination(entryPath: String): File? {
            val clean = normalizeArchivePath(entryPath)
            for (sel in normalizedTargets) {
                if (clean == sel) {
                    val leafName = sel.substringAfterLast('/')
                    return File(targetDir, leafName)
                } else if (clean.startsWith("$sel/")) {
                    val selParent = if (sel.contains('/')) sel.substringBeforeLast('/') else ""
                    val rel = if (selParent.isEmpty()) clean else clean.removePrefix("$selParent/")
                    return File(targetDir, rel)
                }
            }
            return null
        }

        fun resolveConflictIfEnabled(destFile: File, incomingSize: Long): File? {
            if (!checkConflicts) return destFile
            val resolved = DragonFileManagerTaskEngine.resolveConflictTargetFile(
                context = context,
                candidateDest = destFile,
                operationLabel = "Copy",
                isDirectory = false,
                incomingSizeBytes = incomingSize,
                allowApplyToAll = true
            )
            if (resolved == null) onSkipped()
            return resolved
        }

        val workFile = ensureReadableLocalArchiveFile(context, archiveFile) ?: return 0
        var extractedFilesCount = 0

        try {
            val originalLower = archiveFile.name.lowercase(Locale.US)
            val nativeBinType = if (isBinOrIsoFile(originalLower)) {
                UniversalBinContainerEngine.detectBinContainerType(workFile)
            } else {
                null
            }
            val syntheticMagicName = nativeBinType?.let {
                UniversalBinContainerEngine.syntheticArchiveNameForMagicBin(it, archiveFile.name)
            }
            val effectiveName = syntheticMagicName ?: archiveFile.name
            val lower = effectiveName.lowercase(Locale.US)

            when {
                nativeBinType != null && syntheticMagicName == null -> {
                    extractedFilesCount += UniversalBinContainerEngine.copySelectedEntriesFromNativeBin(
                        context = context,
                        workFile = workFile,
                        originalFile = archiveFile,
                        containerType = nativeBinType,
                        selectedPaths = normalizedTargets,
                        targetDir = targetDir,
                        resolveDestination = { entryPath ->
                            val candidate = resolveRelativeDestination(entryPath)
                            if (candidate != null && isSafeDestination(targetDir, candidate)) candidate else null
                        },
                        checkConflicts = checkConflicts,
                        onSkipped = onSkipped,
                        onProgress = onProgress
                    )
                }

                isZipFamily(lower) -> {
                    val handledByCommons = runCatching {
                        org.apache.commons.compress.archivers.zip.ZipFile.builder()
                            .setFile(workFile)
                            .get()
                            .use { zip ->
                                val entries = zip.entries
                                while (entries.hasMoreElements()) {
                                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                                    val entry = entries.nextElement()
                                    val cleanPath = normalizeArchivePath(entry.name)
                                    if (cleanPath.isBlank()) continue
                                    val destFile = resolveRelativeDestination(cleanPath) ?: continue
                                    if (!isSafeDestination(targetDir, destFile)) continue

                                    if (entry.isDirectory) {
                                        AndroidDataObbBridge.createDirectory(context, destFile)
                                    } else {
                                        val finalDest = resolveConflictIfEnabled(destFile, entry.size.coerceAtLeast(0L)) ?: continue
                                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                        zip.getInputStream(entry).use { input ->
                                            if (streamToDestinationFile(context, input, finalDest, finalDest.name, onProgress)) {
                                                extractedFilesCount++
                                            }
                                        }
                                    }
                                }
                            }
                        true
                    }.getOrDefault(false)

                    if (!handledByCommons) {
                        ZipFile(workFile).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                val entry = entries.nextElement()
                                val cleanPath = normalizeArchivePath(entry.name)
                                if (cleanPath.isBlank()) continue
                                val destFile = resolveRelativeDestination(cleanPath) ?: continue
                                if (!isSafeDestination(targetDir, destFile)) continue

                                if (entry.isDirectory) {
                                    AndroidDataObbBridge.createDirectory(context, destFile)
                                } else {
                                    val finalDest = resolveConflictIfEnabled(destFile, entry.size) ?: continue
                                    finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                    zip.getInputStream(entry).use { input ->
                                        if (streamToDestinationFile(context, input, finalDest, finalDest.name, onProgress)) {
                                            extractedFilesCount++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                lower.endsWith(".7z") -> {
                    SevenZFile.builder().setFile(workFile).get().use { sevenZ ->
                        var entry: SevenZArchiveEntry? = sevenZ.nextEntry
                        val buf = ByteArray(64 * 1024)
                        while (entry != null) {
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val current = entry
                            val cleanPath = normalizeArchivePath(current.name)
                            val destFile = if (cleanPath.isNotBlank()) resolveRelativeDestination(cleanPath) else null
                            if (destFile != null && isSafeDestination(targetDir, destFile)) {
                                if (current.isDirectory) {
                                    AndroidDataObbBridge.createDirectory(context, destFile)
                                } else {
                                    val finalDest = resolveConflictIfEnabled(destFile, current.size)
                                    if (finalDest != null) {
                                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                        AndroidDataObbBridge.openOutputStreamForFile(context, finalDest)?.use { out ->
                                            var r = sevenZ.read(buf)
                                            while (r > 0) {
                                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                                out.write(buf, 0, r)
                                                onProgress(r.toLong(), finalDest.name)
                                                r = sevenZ.read(buf)
                                            }
                                            out.flush()
                                            extractedFilesCount++
                                        }
                                    }
                                }
                            }
                            entry = sevenZ.nextEntry
                        }
                    }
                }

                lower.endsWith(".rar") || lower.endsWith(".cbr") -> {
                    Archive(workFile).use { rar ->
                        var header: FileHeader? = rar.nextFileHeader()
                        while (header != null) {
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val h = header
                            val rawName = h.fileName.ifBlank { h.fileNameString }
                            val cleanPath = normalizeArchivePath(rawName)
                            val destFile = if (cleanPath.isNotBlank()) resolveRelativeDestination(cleanPath) else null
                            if (destFile != null && isSafeDestination(targetDir, destFile)) {
                                if (h.isDirectory) {
                                    AndroidDataObbBridge.createDirectory(context, destFile)
                                } else {
                                    val finalDest = resolveConflictIfEnabled(destFile, h.fullUnpackSize)
                                    if (finalDest != null) {
                                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                        AndroidDataObbBridge.openOutputStreamForFile(context, finalDest)?.use { out ->
                                            rar.extractFile(h, out)
                                            out.flush()
                                            onProgress(h.fullUnpackSize.coerceAtLeast(0L), finalDest.name)
                                            extractedFilesCount++
                                        }
                                    }
                                }
                            }
                            header = rar.nextFileHeader()
                        }
                    }
                }

                else -> {
                    openArchiveOrSingleStream(workFile, effectiveName) { entryPath, isDir, entrySize, stream ->
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val cleanPath = normalizeArchivePath(entryPath)
                        val destFile = if (cleanPath.isNotBlank()) resolveRelativeDestination(cleanPath) else null
                        if (destFile != null && isSafeDestination(targetDir, destFile)) {
                            if (isDir) {
                                AndroidDataObbBridge.createDirectory(context, destFile)
                            } else {
                                val finalDest = resolveConflictIfEnabled(destFile, entrySize)
                                if (finalDest != null) {
                                    finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                    if (streamToDestinationFile(context, stream, finalDest, finalDest.name, onProgress)) {
                                        extractedFilesCount++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        } finally {
            if (workFile.absolutePath != archiveFile.absolutePath) {
                runCatching { workFile.delete() }
            }
        }

        return extractedFilesCount
    }

    /**
     * Estimates the total uncompressed byte size of `selectedEntryPaths` inside `archiveFile`
     * so copy/paste and selective extraction notifications can report accurate 0%..100% progress.
     */
    fun estimateSelectedEntriesTotalBytes(
        context: Context,
        archiveFile: File,
        selectedEntryPaths: List<String>
    ): Long {
        if (selectedEntryPaths.isEmpty()) return 0L
        val normalizedTargets = selectedEntryPaths
            .map { normalizeArchivePath(it) }
            .filter { it.isNotBlank() }
        if (normalizedTargets.isEmpty()) return 0L

        return runCatching {
            val allEntries = readRawArchiveEntries(context, archiveFile)
            var total = 0L
            for (entry in allEntries) {
                if (entry.isDirectory) continue
                val clean = normalizeArchivePath(entry.fullEntryPath)
                if (normalizedTargets.any { sel -> clean == sel || clean.startsWith("$sel/") }) {
                    total += entry.uncompressedSize.coerceAtLeast(entry.compressedSize).coerceAtLeast(0L)
                }
            }
            total
        }.getOrDefault(0L)
    }

    /**
     * Extracts a single internal entry from an archive to a temporary cache file so the user
     * can preview, view text/code, browse a nested archive (including multi-GB `payload.bin` via
     * zero-copy `CrAU_ZIP_LINK`), or install an APK directly from inside an archive without
     * decompressing the whole archive.
     */
    fun extractSingleEntryToPreviewCache(
        context: Context,
        archiveFile: File,
        entryPath: String
    ): File? {
        val previewDir = File(context.cacheDir, "archive_preview_cache").apply { mkdirs() }
        // Evict stale archive preview files so previewing entries inside archives never bloats cacheDir
        runCatching {
            val now = System.currentTimeMillis()
            val existing = previewDir.listFiles().orEmpty()
            var remainingBytes = 0L
            for (old in existing.sortedByDescending { it.lastModified() }) {
                val len = old.length().coerceAtLeast(0L)
                if (now - old.lastModified() > 2 * 60_000L || remainingBytes + len > 10L * 1024L * 1024L) {
                    runCatching { old.deleteRecursively() }
                } else {
                    remainingBytes += len
                }
            }
        }
        val leafName = normalizeArchivePath(entryPath).substringAfterLast('/').ifBlank { "preview_file" }
        val outFile = File(previewDir, leafName)
        runCatching { if (outFile.exists()) outFile.delete() }

        // Fast zero-copy path for Android OTA payload.bin inside a .zip ROM archive:
        // creates a < 2 MB virtual OTA link file in < 50 ms instead of copying 4–6 GB to cacheDir!
        if (leafName.endsWith(".bin", ignoreCase = true) && isZipFamily(archiveFile.name.lowercase(Locale.US))) {
            val workArchive = ensureReadableLocalArchiveFile(context, archiveFile)
            if (workArchive != null) {
                val linked = UniversalBinContainerEngine.tryCreateVirtualOtaLinkFromArchive(
                    outerArchiveFile = workArchive,
                    entryPath = entryPath,
                    previewOutFile = outFile
                )
                if (linked != null && linked.exists()) {
                    return linked
                }
            }
        }

        val count = copyEntriesFromArchiveDirectToDirectory(
            context = context,
            archiveFile = archiveFile,
            selectedEntryPaths = listOf(entryPath),
            targetDir = previewDir,
            checkConflicts = false
        )
        return if (count > 0 && outFile.exists()) outFile else null
    }

    /**
     * Universal Decompression Engine: Decompresses the entire compressed file (any format) into `targetDir`.
     */
    fun extractEntireArchiveUniversal(
        context: Context,
        archiveFile: File,
        targetDir: File,
        onSkipped: () -> Unit = {},
        onProgress: (Int, Int, Long, Long, String) -> Unit = { _, _, _, _, _ -> }
    ): Int {
        AndroidDataObbBridge.createDirectory(context, targetDir)
        val rawEntries = readRawArchiveEntries(context, archiveFile)
        val totalFiles = rawEntries.count { !it.isDirectory }.coerceAtLeast(1)
        val totalUncompressedBytes = rawEntries.sumOf {
            if (it.isDirectory) 0L else it.uncompressedSize.coerceAtLeast(0L)
        }.coerceAtLeast(archiveFile.length().coerceAtLeast(1L))

        val workFile = ensureReadableLocalArchiveFile(context, archiveFile) ?: return 0
        var count = 0
        var bytesDone = 0L

        fun resolveDecompressConflict(destFile: File, incomingSize: Long): File? {
            val resolved = DragonFileManagerTaskEngine.resolveConflictTargetFile(
                context = context,
                candidateDest = destFile,
                operationLabel = "Decompress",
                isDirectory = false,
                incomingSizeBytes = incomingSize,
                allowApplyToAll = true
            )
            if (resolved == null) {
                onSkipped()
                if (incomingSize > 0L) {
                    bytesDone += incomingSize
                    val effectiveTotal = maxOf(totalUncompressedBytes, bytesDone)
                    onProgress(count, totalFiles, bytesDone, effectiveTotal, destFile.name)
                }
            }
            return resolved
        }

        try {
            val originalLower = archiveFile.name.lowercase(Locale.US)
            val nativeBinType = if (isBinOrIsoFile(originalLower)) {
                UniversalBinContainerEngine.detectBinContainerType(workFile)
            } else {
                null
            }
            val syntheticMagicName = nativeBinType?.let {
                UniversalBinContainerEngine.syntheticArchiveNameForMagicBin(it, archiveFile.name)
            }
            val effectiveName = syntheticMagicName ?: archiveFile.name
            val lower = effectiveName.lowercase(Locale.US)

            when {
                nativeBinType != null && syntheticMagicName == null -> {
                    count = UniversalBinContainerEngine.extractEntireNativeBin(
                        context = context,
                        workFile = workFile,
                        originalFile = archiveFile,
                        containerType = nativeBinType,
                        targetDir = targetDir,
                        onSkipped = onSkipped,
                        onProgress = onProgress
                    )
                }

                isZipFamily(lower) -> {
                    val handledByCommons = runCatching {
                        org.apache.commons.compress.archivers.zip.ZipFile.builder()
                            .setFile(workFile)
                            .get()
                            .use { zip ->
                                val entries = zip.entries
                                while (entries.hasMoreElements()) {
                                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                                    val entry = entries.nextElement()
                                    val cleanPath = normalizeArchivePath(entry.name)
                                    if (cleanPath.isBlank()) continue
                                    val destFile = File(targetDir, cleanPath)
                                    if (!isSafeDestination(targetDir, destFile)) continue

                                    if (entry.isDirectory) {
                                        AndroidDataObbBridge.createDirectory(context, destFile)
                                    } else {
                                        val finalDest = resolveDecompressConflict(destFile, entry.size.coerceAtLeast(0L)) ?: continue
                                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                        zip.getInputStream(entry).use { input ->
                                            val ok = streamToDestinationFile(context, input, finalDest, finalDest.name) { delta, name ->
                                                bytesDone += delta
                                                val effectiveTotal = maxOf(totalUncompressedBytes, bytesDone)
                                                onProgress(count, totalFiles, bytesDone, effectiveTotal, name)
                                            }
                                            if (ok) {
                                                count++
                                                val effectiveTotal = maxOf(totalUncompressedBytes, bytesDone)
                                                onProgress(count, totalFiles, bytesDone, effectiveTotal, finalDest.name)
                                            }
                                        }
                                    }
                                }
                            }
                        true
                    }.getOrDefault(false)

                    if (!handledByCommons) {
                        ZipFile(workFile).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                val entry = entries.nextElement()
                                val cleanPath = normalizeArchivePath(entry.name)
                                if (cleanPath.isBlank()) continue
                                val destFile = File(targetDir, cleanPath)
                                if (!isSafeDestination(targetDir, destFile)) continue

                                if (entry.isDirectory) {
                                    AndroidDataObbBridge.createDirectory(context, destFile)
                                } else {
                                    val finalDest = resolveDecompressConflict(destFile, entry.size) ?: continue
                                    finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                    zip.getInputStream(entry).use { input ->
                                        val ok = streamToDestinationFile(context, input, finalDest, finalDest.name) { delta, name ->
                                            bytesDone += delta
                                            val effectiveTotal = maxOf(totalUncompressedBytes, bytesDone)
                                            onProgress(count, totalFiles, bytesDone, effectiveTotal, name)
                                        }
                                        if (ok) {
                                            count++
                                            val effectiveTotal = maxOf(totalUncompressedBytes, bytesDone)
                                            onProgress(count, totalFiles, bytesDone, effectiveTotal, finalDest.name)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                lower.endsWith(".7z") -> {
                    SevenZFile.builder().setFile(workFile).get().use { sevenZ ->
                        var entry: SevenZArchiveEntry? = sevenZ.nextEntry
                        val buf = ByteArray(64 * 1024)
                        while (entry != null) {
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val current = entry
                            val cleanPath = normalizeArchivePath(current.name)
                            if (cleanPath.isNotBlank()) {
                                val destFile = File(targetDir, cleanPath)
                                if (isSafeDestination(targetDir, destFile)) {
                                    if (current.isDirectory) {
                                        AndroidDataObbBridge.createDirectory(context, destFile)
                                    } else {
                                        val finalDest = resolveDecompressConflict(destFile, current.size)
                                        if (finalDest != null) {
                                            finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                            AndroidDataObbBridge.openOutputStreamForFile(context, finalDest)?.use { out ->
                                                var r = sevenZ.read(buf)
                                                while (r > 0) {
                                                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                                                    out.write(buf, 0, r)
                                                    bytesDone += r
                                                    onProgress(count, totalFiles, bytesDone, totalUncompressedBytes, finalDest.name)
                                                    r = sevenZ.read(buf)
                                                }
                                                out.flush()
                                                count++
                                            }
                                        }
                                    }
                                }
                            }
                            entry = sevenZ.nextEntry
                        }
                    }
                }

                lower.endsWith(".rar") || lower.endsWith(".cbr") -> {
                    Archive(workFile).use { rar ->
                        var header: FileHeader? = rar.nextFileHeader()
                        while (header != null) {
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val h = header
                            val rawName = h.fileName.ifBlank { h.fileNameString }
                            val cleanPath = normalizeArchivePath(rawName)
                            if (cleanPath.isNotBlank()) {
                                val destFile = File(targetDir, cleanPath)
                                if (isSafeDestination(targetDir, destFile)) {
                                    if (h.isDirectory) {
                                        AndroidDataObbBridge.createDirectory(context, destFile)
                                    } else {
                                        val finalDest = resolveDecompressConflict(destFile, h.fullUnpackSize)
                                        if (finalDest != null) {
                                            finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                            AndroidDataObbBridge.openOutputStreamForFile(context, finalDest)?.use { out ->
                                                rar.extractFile(h, out)
                                                out.flush()
                                                bytesDone += h.fullUnpackSize.coerceAtLeast(0L)
                                                count++
                                                onProgress(count, totalFiles, bytesDone, totalUncompressedBytes, finalDest.name)
                                            }
                                        }
                                    }
                                }
                            }
                            header = rar.nextFileHeader()
                        }
                    }
                }

                else -> {
                    openArchiveOrSingleStream(workFile, effectiveName) { entryPath, isDir, entrySize, stream ->
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val cleanPath = normalizeArchivePath(entryPath)
                        if (cleanPath.isNotBlank()) {
                            val destFile = File(targetDir, cleanPath)
                            if (isSafeDestination(targetDir, destFile)) {
                                if (isDir) {
                                    AndroidDataObbBridge.createDirectory(context, destFile)
                                } else {
                                    val finalDest = resolveDecompressConflict(destFile, entrySize)
                                    if (finalDest != null) {
                                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                                        val ok = streamToDestinationFile(context, stream, finalDest, finalDest.name) { delta, name ->
                                            bytesDone += delta
                                            onProgress(count, totalFiles, bytesDone, totalUncompressedBytes, name)
                                        }
                                        if (ok) count++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        } finally {
            if (workFile.absolutePath != archiveFile.absolutePath) {
                runCatching { workFile.delete() }
            }
        }

        return count
    }

    /**
     * Universal Detailed Compression Engine:
     * Compresses `sources` into `outputArchive` using the user's chosen format, compression level preset,
     * hidden-file policy, root folder structure option, exclude pattern list, and comment.
     */
    fun compressUniversalArchive(
        context: Context,
        sources: List<File>,
        outputArchive: File,
        config: CompressionJobConfig,
        onProgress: (Int, Int, Long, Long, String) -> Unit = { _, _, _, _, _ -> }
    ): Boolean {
        if (sources.isEmpty()) return false

        val excludeTokens = config.excludePatternCsv
            .split(',', ';')
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotEmpty() }

        fun isExcluded(file: File): Boolean {
            if (!config.includeHiddenFiles && file.name.startsWith(".")) return true
            val nameLower = file.name.lowercase(Locale.US)
            for (pattern in excludeTokens) {
                val cleanGlob = pattern.removePrefix("*")
                if (cleanGlob.isNotEmpty() && (nameLower.endsWith(cleanGlob) || nameLower.contains(cleanGlob))) {
                    return true
                }
            }
            return false
        }

        // Collect all (SourceFile, RelativeEntryPath, IsDirectory) pairs
        data class SourceArchiveItem(
            val file: File,
            val entryPath: String,
            val isDirectory: Boolean,
            val lengthBytes: Long,
            val lastModifiedMs: Long
        )

        val collectedItems = mutableListOf<SourceArchiveItem>()

        fun collectFromSource(file: File, parentEntryPath: String, isTopLevel: Boolean) {
            if (file.absolutePath == outputArchive.absolutePath) return
            if (isExcluded(file)) return

            val isDir = AndroidDataObbBridge.isDirectoryUniversal(context, file)
            val entryPath = when {
                isTopLevel && isDir && !config.preserveRootFolderStructure -> ""
                parentEntryPath.isEmpty() -> file.name
                else -> "$parentEntryPath/${file.name}"
            }

            if (isDir) {
                if (entryPath.isNotEmpty()) {
                    collectedItems.add(
                        SourceArchiveItem(
                            file = file,
                            entryPath = "$entryPath/",
                            isDirectory = true,
                            lengthBytes = 0L,
                            lastModifiedMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                    )
                }
                val direct = file.listFiles()
                if (direct != null) {
                    direct.sortedBy { it.name.lowercase(Locale.US) }.forEach { child ->
                        collectFromSource(child, entryPath, isTopLevel = false)
                    }
                } else {
                    AndroidDataObbBridge.listEntriesInDirectory(file)
                        ?.sortedBy { it.name.lowercase(Locale.US) }
                        ?.forEach { childEntry ->
                            collectFromSource(childEntry.file, entryPath, isTopLevel = false)
                        }
                }
            } else {
                val len = AndroidDataObbBridge.estimateTotalBytesUniversal(context, file)
                collectedItems.add(
                    SourceArchiveItem(
                        file = file,
                        entryPath = entryPath.ifEmpty { file.name },
                        isDirectory = false,
                        lengthBytes = len,
                        lastModifiedMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                )
            }
        }

        sources.forEach { src ->
            if (AndroidDataObbBridge.existsUniversal(context, src)) {
                collectFromSource(src, "", isTopLevel = true)
            }
        }

        if (collectedItems.isEmpty()) return false

        val totalFiles = collectedItems.count { !it.isDirectory }.coerceAtLeast(1)
        val totalBytes = collectedItems.sumOf { it.lengthBytes }.coerceAtLeast(1L)
        var processedFiles = 0
        var processedBytes = 0L

        // Write to a local temp file first if outputArchive is inside Android/data or Android/obb requiring Root copy
        val needsStaging = AndroidDataObbBridge.isAndroidDataOrObbPath(outputArchive.absolutePath)
        val workingOutFile = if (needsStaging) {
            File(context.cacheDir, "compress_stage_${System.currentTimeMillis()}_${outputArchive.name}")
        } else {
            outputArchive.parentFile?.mkdirs()
            outputArchive
        }

        val success = try {
            when (config.format) {
                UniversalCompressionFormat.ZIP,
                UniversalCompressionFormat.APK,
                UniversalCompressionFormat.XAPK,
                UniversalCompressionFormat.APKS -> {
                    ZipArchiveOutputStream(BufferedOutputStream(FileOutputStream(workingOutFile))).use { zos ->
                        zos.setLevel(config.levelPreset.zipLevel)
                        if (config.archiveComment.isNotBlank()) {
                            zos.setComment(config.archiveComment)
                        }
                        if (config.levelPreset == CompressionLevelPreset.STORE) {
                            zos.setMethod(ZipEntry.STORED)
                        } else {
                            zos.setMethod(ZipEntry.DEFLATED)
                        }

                        collectedItems.forEach { item ->
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            if (item.isDirectory) {
                                val dirEntry = ZipArchiveEntry(item.entryPath)
                                dirEntry.time = item.lastModifiedMs
                                if (config.levelPreset == CompressionLevelPreset.STORE) {
                                    dirEntry.method = ZipEntry.STORED
                                    dirEntry.size = 0L
                                    dirEntry.compressedSize = 0L
                                    dirEntry.crc = 0L
                                }
                                zos.putArchiveEntry(dirEntry)
                                zos.closeArchiveEntry()
                            } else {
                                val entry = ZipArchiveEntry(item.entryPath)
                                entry.time = item.lastModifiedMs
                                if (config.levelPreset == CompressionLevelPreset.STORE) {
                                    val crc = CRC32()
                                    var exactSize = 0L
                                    AndroidDataObbBridge.openInputStreamForFile(context, item.file)?.use { s ->
                                        val b = ByteArray(64 * 1024)
                                        var r = s.read(b)
                                        while (r > 0) {
                                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                                            crc.update(b, 0, r)
                                            exactSize += r
                                            r = s.read(b)
                                        }
                                    }
                                    entry.method = ZipEntry.STORED
                                    entry.size = exactSize
                                    entry.compressedSize = exactSize
                                    entry.crc = crc.value
                                }
                                zos.putArchiveEntry(entry)
                                AndroidDataObbBridge.openInputStreamForFile(context, item.file)?.use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    var r = input.read(buf)
                                    while (r > 0) {
                                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                                        zos.write(buf, 0, r)
                                        processedBytes += r
                                        onProgress(processedFiles, totalFiles, processedBytes, totalBytes, item.file.name)
                                        r = input.read(buf)
                                    }
                                }
                                zos.closeArchiveEntry()
                                processedFiles++
                            }
                        }
                        zos.finish()
                    }
                    true
                }

                UniversalCompressionFormat.JAR -> {
                    JarArchiveOutputStream(BufferedOutputStream(FileOutputStream(workingOutFile))).use { jos ->
                        jos.setLevel(config.levelPreset.zipLevel)
                        if (config.archiveComment.isNotBlank()) {
                            jos.setComment(config.archiveComment)
                        }
                        collectedItems.forEach { item ->
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val entry = JarArchiveEntry(item.entryPath)
                            entry.time = item.lastModifiedMs
                            jos.putArchiveEntry(entry)
                            if (!item.isDirectory) {
                                AndroidDataObbBridge.openInputStreamForFile(context, item.file)?.use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    var r = input.read(buf)
                                    while (r > 0) {
                                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                                        jos.write(buf, 0, r)
                                        processedBytes += r
                                        onProgress(processedFiles, totalFiles, processedBytes, totalBytes, item.file.name)
                                        r = input.read(buf)
                                    }
                                }
                                processedFiles++
                            }
                            jos.closeArchiveEntry()
                        }
                        jos.finish()
                    }
                    true
                }

                UniversalCompressionFormat.SEVEN_Z -> {
                    SevenZOutputFile(workingOutFile).use { sevenZOut ->
                        sevenZOut.setContentCompression(config.levelPreset.sevenZMethod)
                        collectedItems.forEach { item ->
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val entry = SevenZArchiveEntry().apply {
                                name = item.entryPath.trimEnd('/')
                                isDirectory = item.isDirectory
                                lastModifiedDate = java.util.Date(item.lastModifiedMs)
                            }
                            sevenZOut.putArchiveEntry(entry)
                            if (!item.isDirectory) {
                                AndroidDataObbBridge.openInputStreamForFile(context, item.file)?.use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    var r = input.read(buf)
                                    while (r > 0) {
                                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                                        sevenZOut.write(buf, 0, r)
                                        processedBytes += r
                                        onProgress(processedFiles, totalFiles, processedBytes, totalBytes, item.file.name)
                                        r = input.read(buf)
                                    }
                                }
                                processedFiles++
                            }
                            sevenZOut.closeArchiveEntry()
                        }
                        sevenZOut.finish()
                    }
                    true
                }

                UniversalCompressionFormat.CPIO -> {
                    CpioArchiveOutputStream(BufferedOutputStream(FileOutputStream(workingOutFile))).use { cos ->
                        collectedItems.forEach { item ->
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            if (!item.isDirectory) {
                                val entry = CpioArchiveEntry(item.entryPath, item.lengthBytes)
                                entry.time = item.lastModifiedMs / 1000L
                                cos.putArchiveEntry(entry)
                                AndroidDataObbBridge.openInputStreamForFile(context, item.file)?.use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    var r = input.read(buf)
                                    while (r > 0) {
                                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                                        cos.write(buf, 0, r)
                                        processedBytes += r
                                        onProgress(processedFiles, totalFiles, processedBytes, totalBytes, item.file.name)
                                        r = input.read(buf)
                                    }
                                }
                                cos.closeArchiveEntry()
                                processedFiles++
                            }
                        }
                        cos.finish()
                    }
                    true
                }

                UniversalCompressionFormat.TAR,
                UniversalCompressionFormat.TAR_GZ,
                UniversalCompressionFormat.TAR_BZ2,
                UniversalCompressionFormat.TAR_XZ,
                UniversalCompressionFormat.TAR_ZST,
                UniversalCompressionFormat.TAR_LZ4 -> {
                    BufferedOutputStream(FileOutputStream(workingOutFile)).use { rawOut ->
                        val compressedOut = wrapCompressorOutputStream(rawOut, config.format, config.levelPreset)
                        TarArchiveOutputStream(compressedOut, "UTF-8").use { tos ->
                            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                            collectedItems.forEach { item ->
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                val entry = TarArchiveEntry(item.entryPath)
                                entry.setModTime(item.lastModifiedMs)
                                if (!item.isDirectory) {
                                    entry.size = item.lengthBytes
                                }
                                tos.putArchiveEntry(entry)
                                if (!item.isDirectory) {
                                    AndroidDataObbBridge.openInputStreamForFile(context, item.file)?.use { input ->
                                        val buf = ByteArray(64 * 1024)
                                        var r = input.read(buf)
                                        while (r > 0) {
                                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                                            tos.write(buf, 0, r)
                                            processedBytes += r
                                            onProgress(processedFiles, totalFiles, processedBytes, totalBytes, item.file.name)
                                            r = input.read(buf)
                                        }
                                    }
                                    processedFiles++
                                }
                                tos.closeArchiveEntry()
                            }
                            tos.finish()
                        }
                    }
                    true
                }

                UniversalCompressionFormat.GZIP,
                UniversalCompressionFormat.BZIP2,
                UniversalCompressionFormat.XZ,
                UniversalCompressionFormat.ZSTD,
                UniversalCompressionFormat.LZ4,
                UniversalCompressionFormat.LZMA -> {
                    val fileItems = collectedItems.filter { !it.isDirectory }
                    BufferedOutputStream(FileOutputStream(workingOutFile)).use { rawOut ->
                        wrapCompressorOutputStream(rawOut, config.format, config.levelPreset).use { compOut ->
                            if (fileItems.size == 1) {
                                val single = fileItems.first()
                                AndroidDataObbBridge.openInputStreamForFile(context, single.file)?.use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    var r = input.read(buf)
                                    while (r > 0) {
                                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                                        compOut.write(buf, 0, r)
                                        processedBytes += r
                                        onProgress(1, 1, processedBytes, totalBytes, single.file.name)
                                        r = input.read(buf)
                                    }
                                }
                                compOut.flush()
                            } else {
                                // Automatically wrap multiple files in a TAR stream inside the compressor
                                TarArchiveOutputStream(compOut, "UTF-8").use { tos ->
                                    tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                                    tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                                    collectedItems.forEach { item ->
                                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                                        val entry = TarArchiveEntry(item.entryPath)
                                        entry.setModTime(item.lastModifiedMs)
                                        if (!item.isDirectory) {
                                            entry.size = item.lengthBytes
                                        }
                                        tos.putArchiveEntry(entry)
                                        if (!item.isDirectory) {
                                            AndroidDataObbBridge.openInputStreamForFile(context, item.file)?.use { input ->
                                                val buf = ByteArray(64 * 1024)
                                                var r = input.read(buf)
                                                while (r > 0) {
                                                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                                                    tos.write(buf, 0, r)
                                                    processedBytes += r
                                                    onProgress(processedFiles, totalFiles, processedBytes, totalBytes, item.file.name)
                                                    r = input.read(buf)
                                                }
                                            }
                                            processedFiles++
                                        }
                                        tos.closeArchiveEntry()
                                    }
                                    tos.finish()
                                }
                            }
                        }
                    }
                    true
                }
            }
        } catch (ce: CancellationException) {
            runCatching { workingOutFile.delete() }
            throw ce
        } catch (_: Exception) {
            false
        }

        if (!success || !workingOutFile.exists() || workingOutFile.length() <= 0L) {
            if (needsStaging) runCatching { workingOutFile.delete() }
            return false
        }

        val finalOk = if (needsStaging) {
            val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                context = context,
                source = workingOutFile,
                dest = outputArchive,
                isDirectoryHint = false
            )
            runCatching { workingOutFile.delete() }
            copied
        } else {
            true
        }

        if (finalOk && config.deleteSourceAfterCompression) {
            sources.forEach { src ->
                if (src.absolutePath != outputArchive.absolutePath) {
                    AndroidDataObbBridge.deleteFileOrDirectory(context, src)
                }
            }
        }

        return finalOk
    }

    private fun wrapCompressorOutputStream(
        baseOut: OutputStream,
        format: UniversalCompressionFormat,
        preset: CompressionLevelPreset
    ): OutputStream {
        return when (format) {
            UniversalCompressionFormat.TAR_GZ,
            UniversalCompressionFormat.GZIP -> {
                val params = GzipParameters().apply {
                    compressionLevel = if (preset == CompressionLevelPreset.STORE) {
                        Deflater.NO_COMPRESSION
                    } else {
                        preset.zipLevel
                    }
                }
                GzipCompressorOutputStream(baseOut, params)
            }
            UniversalCompressionFormat.TAR_BZ2,
            UniversalCompressionFormat.BZIP2 -> {
                BZip2CompressorOutputStream(baseOut, preset.bzipBlockSize.coerceIn(1, 9))
            }
            UniversalCompressionFormat.TAR_XZ,
            UniversalCompressionFormat.XZ -> {
                XZCompressorOutputStream(baseOut, preset.xzPreset.coerceIn(0, 9))
            }
            UniversalCompressionFormat.TAR_ZST,
            UniversalCompressionFormat.ZSTD -> {
                ZstdOutputStream(baseOut, preset.zstdLevel)
            }
            UniversalCompressionFormat.TAR_LZ4,
            UniversalCompressionFormat.LZ4 -> {
                FramedLZ4CompressorOutputStream(baseOut)
            }
            UniversalCompressionFormat.LZMA -> {
                LZMACompressorOutputStream(baseOut)
            }
            else -> baseOut
        }
    }

    private fun readRawArchiveEntries(
        context: Context,
        archiveFile: File
    ): List<ArchiveInternalNode> {
        val workFile = ensureReadableLocalArchiveFile(context, archiveFile) ?: return emptyList()
        val list = mutableListOf<ArchiveInternalNode>()
        try {
            val originalLower = archiveFile.name.lowercase(Locale.US)
            val nativeBinType = if (isBinOrIsoFile(originalLower)) {
                UniversalBinContainerEngine.detectBinContainerType(workFile)
            } else {
                null
            }
            val syntheticMagicName = nativeBinType?.let {
                UniversalBinContainerEngine.syntheticArchiveNameForMagicBin(it, archiveFile.name)
            }
            val effectiveName = syntheticMagicName ?: archiveFile.name
            val lower = effectiveName.lowercase(Locale.US)

            when {
                nativeBinType != null && syntheticMagicName == null -> {
                    list.addAll(
                        UniversalBinContainerEngine.readNativeBinEntries(
                            workFile = workFile,
                            originalFile = archiveFile,
                            containerType = nativeBinType
                        )
                    )
                }

                isZipFamily(lower) -> {
                    val handledByCommons = runCatching {
                        org.apache.commons.compress.archivers.zip.ZipFile.builder()
                            .setFile(workFile)
                            .get()
                            .use { zip ->
                                val en = zip.entries
                                while (en.hasMoreElements()) {
                                    val entry = en.nextElement()
                                    val cleanPath = normalizeArchivePath(entry.name)
                                    if (cleanPath.isBlank()) continue
                                    list.add(
                                        ArchiveInternalNode(
                                            fullEntryPath = cleanPath,
                                            name = cleanPath.substringAfterLast('/'),
                                            parentPath = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else "",
                                            isDirectory = entry.isDirectory,
                                            uncompressedSize = entry.size.coerceAtLeast(0L),
                                            compressedSize = entry.compressedSize.coerceAtLeast(0L),
                                            lastModifiedMs = entry.time.takeIf { it > 0L } ?: archiveFile.lastModified()
                                        )
                                    )
                                }
                            }
                        list.isNotEmpty()
                    }.getOrDefault(false)

                    if (!handledByCommons) {
                        ZipFile(workFile).use { zip ->
                            val en = zip.entries()
                            while (en.hasMoreElements()) {
                                val entry = en.nextElement()
                                val cleanPath = normalizeArchivePath(entry.name)
                                if (cleanPath.isBlank()) continue
                                list.add(
                                    ArchiveInternalNode(
                                        fullEntryPath = cleanPath,
                                        name = cleanPath.substringAfterLast('/'),
                                        parentPath = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else "",
                                        isDirectory = entry.isDirectory,
                                        uncompressedSize = entry.size.coerceAtLeast(0L),
                                        compressedSize = entry.compressedSize.coerceAtLeast(0L),
                                        lastModifiedMs = entry.time.takeIf { it > 0L } ?: archiveFile.lastModified()
                                    )
                                )
                            }
                        }
                    }
                }

                lower.endsWith(".7z") -> {
                    SevenZFile.builder().setFile(workFile).get().use { sevenZ ->
                        sevenZ.entries.forEach { entry ->
                            val cleanPath = normalizeArchivePath(entry.name)
                            if (cleanPath.isNotBlank()) {
                                val ts = runCatching {
                                    if (entry.hasLastModifiedDate) entry.lastModifiedDate.time else archiveFile.lastModified()
                                }.getOrDefault(archiveFile.lastModified())
                                list.add(
                                    ArchiveInternalNode(
                                        fullEntryPath = cleanPath,
                                        name = cleanPath.substringAfterLast('/'),
                                        parentPath = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else "",
                                        isDirectory = entry.isDirectory,
                                        uncompressedSize = entry.size.coerceAtLeast(0L),
                                        compressedSize = 0L,
                                        lastModifiedMs = ts
                                    )
                                )
                            }
                        }
                    }
                }

                lower.endsWith(".rar") || lower.endsWith(".cbr") -> {
                    Archive(workFile).use { rar ->
                        rar.fileHeaders.forEach { h ->
                            val rawName = h.fileName.ifBlank { h.fileNameString }
                            val cleanPath = normalizeArchivePath(rawName)
                            if (cleanPath.isNotBlank()) {
                                val ts = h.mTime?.time ?: archiveFile.lastModified()
                                list.add(
                                    ArchiveInternalNode(
                                        fullEntryPath = cleanPath,
                                        name = cleanPath.substringAfterLast('/'),
                                        parentPath = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else "",
                                        isDirectory = h.isDirectory,
                                        uncompressedSize = h.fullUnpackSize.coerceAtLeast(0L),
                                        compressedSize = h.fullPackSize.coerceAtLeast(0L),
                                        lastModifiedMs = ts
                                    )
                                )
                            }
                        }
                    }
                }

                else -> {
                    openArchiveOrSingleStream(workFile, effectiveName) { entryPath, isDir, size, _ ->
                        val cleanPath = normalizeArchivePath(entryPath)
                        if (cleanPath.isNotBlank()) {
                            list.add(
                                ArchiveInternalNode(
                                    fullEntryPath = cleanPath,
                                    name = cleanPath.substringAfterLast('/'),
                                    parentPath = if (cleanPath.contains('/')) cleanPath.substringBeforeLast('/') else "",
                                    isDirectory = isDir,
                                    uncompressedSize = size.coerceAtLeast(0L),
                                    compressedSize = 0L,
                                    lastModifiedMs = archiveFile.lastModified()
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            if (workFile.absolutePath != archiveFile.absolutePath) {
                runCatching { workFile.delete() }
            }
        }
        return list
    }

    private fun openArchiveOrSingleStream(
        workFile: File,
        originalFileName: String,
        onEntry: (entryPath: String, isDir: Boolean, size: Long, stream: InputStream) -> Unit
    ) {
        val lower = originalFileName.lowercase(Locale.US)
        BufferedInputStream(FileInputStream(workFile)).use { rawIn ->
            when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> {
                    GzipCompressorInputStream(rawIn).use { gzIn ->
                        iterateArchiveInputStream(TarArchiveInputStream(gzIn), onEntry)
                    }
                }
                lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") -> {
                    BZip2CompressorInputStream(rawIn).use { bzIn ->
                        iterateArchiveInputStream(TarArchiveInputStream(bzIn), onEntry)
                    }
                }
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> {
                    XZCompressorInputStream(rawIn).use { xzIn ->
                        iterateArchiveInputStream(TarArchiveInputStream(xzIn), onEntry)
                    }
                }
                lower.endsWith(".tar.zst") || lower.endsWith(".tzst") -> {
                    ZstdInputStream(rawIn).use { zstIn ->
                        iterateArchiveInputStream(TarArchiveInputStream(zstIn), onEntry)
                    }
                }
                lower.endsWith(".tar.lz4") -> {
                    FramedLZ4CompressorInputStream(rawIn).use { lz4In ->
                        iterateArchiveInputStream(TarArchiveInputStream(lz4In), onEntry)
                    }
                }
                lower.endsWith(".tar.lzma") -> {
                    LZMACompressorInputStream(rawIn).use { lzmaIn ->
                        iterateArchiveInputStream(TarArchiveInputStream(lzmaIn), onEntry)
                    }
                }
                lower.endsWith(".tar") -> {
                    iterateArchiveInputStream(TarArchiveInputStream(rawIn), onEntry)
                }
                lower.endsWith(".cpio") -> {
                    iterateArchiveInputStream(CpioArchiveInputStream(rawIn), onEntry)
                }
                lower.endsWith(".ar") || lower.endsWith(".deb") || lower.endsWith(".a") -> {
                    iterateArchiveInputStream(ArArchiveInputStream(rawIn), onEntry)
                }
                lower.endsWith(".gz") -> {
                    GzipCompressorInputStream(rawIn).use { gzIn ->
                        handleDecompressedStreamOrEmbeddedTar(gzIn, stripArchiveExtension(originalFileName), workFile.length(), onEntry)
                    }
                }
                lower.endsWith(".bz2") -> {
                    BZip2CompressorInputStream(rawIn).use { bzIn ->
                        handleDecompressedStreamOrEmbeddedTar(bzIn, stripArchiveExtension(originalFileName), workFile.length(), onEntry)
                    }
                }
                lower.endsWith(".xz") -> {
                    XZCompressorInputStream(rawIn).use { xzIn ->
                        handleDecompressedStreamOrEmbeddedTar(xzIn, stripArchiveExtension(originalFileName), workFile.length(), onEntry)
                    }
                }
                lower.endsWith(".zst") -> {
                    ZstdInputStream(rawIn).use { zstIn ->
                        handleDecompressedStreamOrEmbeddedTar(zstIn, stripArchiveExtension(originalFileName), workFile.length(), onEntry)
                    }
                }
                lower.endsWith(".lz4") -> {
                    FramedLZ4CompressorInputStream(rawIn).use { lz4In ->
                        handleDecompressedStreamOrEmbeddedTar(lz4In, stripArchiveExtension(originalFileName), workFile.length(), onEntry)
                    }
                }
                lower.endsWith(".lzma") -> {
                    LZMACompressorInputStream(rawIn).use { lzmaIn ->
                        handleDecompressedStreamOrEmbeddedTar(lzmaIn, stripArchiveExtension(originalFileName), workFile.length(), onEntry)
                    }
                }
                else -> {
                    // Fallback to ZipArchiveInputStream
                    iterateArchiveInputStream(ZipArchiveInputStream(rawIn), onEntry)
                }
            }
        }
    }

    private fun handleDecompressedStreamOrEmbeddedTar(
        decompressedIn: InputStream,
        innerFileName: String,
        fallbackSize: Long,
        onEntry: (entryPath: String, isDir: Boolean, size: Long, stream: InputStream) -> Unit
    ) {
        val buffered = BufferedInputStream(decompressedIn, 64 * 1024)
        buffered.mark(1024)
        val probe = ByteArray(512)
        var totalRead = 0
        while (totalRead < 512) {
            val r = buffered.read(probe, totalRead, 512 - totalRead)
            if (r <= 0) break
            totalRead += r
        }
        buffered.reset()

        // Check POSIX ustar magic at offset 257 ("ustar") in case a multi-file TAR was wrapped in .gz/.xz/.zst/.bz2
        val isEmbeddedTar = totalRead >= 263 &&
            probe[257] == 'u'.code.toByte() &&
            probe[258] == 's'.code.toByte() &&
            probe[259] == 't'.code.toByte() &&
            probe[260] == 'a'.code.toByte() &&
            probe[261] == 'r'.code.toByte()

        if (isEmbeddedTar) {
            iterateArchiveInputStream(TarArchiveInputStream(buffered), onEntry)
        } else {
            val cleanInner = innerFileName.ifBlank { "decompressed_file" }
            onEntry(cleanInner, false, fallbackSize, buffered)
        }
    }

    private fun iterateArchiveInputStream(
        ais: ArchiveInputStream<out ArchiveEntry>,
        onEntry: (entryPath: String, isDir: Boolean, size: Long, stream: InputStream) -> Unit
    ) {
        ais.use { archiveStream ->
            var entry: ArchiveEntry? = archiveStream.nextEntry
            while (entry != null) {
                val e = entry
                if (archiveStream.canReadEntryData(e)) {
                    onEntry(e.name, e.isDirectory, e.size.coerceAtLeast(0L), archiveStream)
                }
                entry = archiveStream.nextEntry
            }
        }
    }

    private fun isBinOrIsoFile(lowerFileName: String): Boolean {
        return lowerFileName.endsWith(".bin") || lowerFileName.endsWith(".iso")
    }

    private fun isZipFamily(lowerFileName: String): Boolean {
        val ext = lowerFileName.substringAfterLast('.', "")
        return ext in setOf(
            "zip", "jar", "war", "ear", "apk", "apks", "xapk", "apkm", "aab", "obb", "cbz", "epub"
        )
    }

    private fun normalizeArchivePath(rawPath: String): String {
        return rawPath
            .replace('\\', '/')
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
    }

    private fun isSafeDestination(targetRoot: File, candidate: File): Boolean {
        val canonicalRoot = runCatching { targetRoot.canonicalPath }.getOrDefault(targetRoot.absolutePath)
        val canonicalOut = runCatching { candidate.canonicalPath }.getOrDefault(candidate.absolutePath)
        return canonicalOut == canonicalRoot || canonicalOut.startsWith("$canonicalRoot/")
    }

    private fun streamToDestinationFile(
        context: Context,
        input: InputStream,
        destFile: File,
        activeName: String,
        onProgress: (Long, String) -> Unit
    ): Boolean {
        val outStream = AndroidDataObbBridge.openOutputStreamForFile(context, destFile) ?: return false
        try {
            outStream.use { out ->
                val buf = ByteArray(64 * 1024)
                var r = input.read(buf)
                while (r > 0) {
                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                    out.write(buf, 0, r)
                    onProgress(r.toLong(), activeName)
                    r = input.read(buf)
                }
                out.flush()
            }
            return true
        } catch (ce: CancellationException) {
            runCatching { destFile.delete() }
            throw ce
        }
    }

    private fun ensureReadableLocalArchiveFile(context: Context, archiveFile: File): File? {
        if (archiveFile.exists() && archiveFile.canRead()) {
            return archiveFile
        }
        val tempCopy = File(context.cacheDir, "archive_read_${System.currentTimeMillis()}_${archiveFile.name}")
        val ok = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
            context = context,
            source = archiveFile,
            dest = tempCopy,
            isDirectoryHint = false
        )
        return if (ok && tempCopy.exists()) tempCopy else null
    }
}
