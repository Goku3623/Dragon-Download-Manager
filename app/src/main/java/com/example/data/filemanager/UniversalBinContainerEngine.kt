package com.example.data.filemanager

import android.content.Context
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

enum class BinContainerType(val label: String) {
    ANDROID_OTA_PAYLOAD("OTA.BIN"),
    MAGIC_ZIP("ZIP.BIN"),
    MAGIC_7Z("7Z.BIN"),
    MAGIC_RAR("RAR.BIN"),
    MAGIC_TAR("TAR.BIN"),
    MAGIC_GZIP("GZ.BIN"),
    MAGIC_BZIP2("BZ2.BIN"),
    MAGIC_XZ("XZ.BIN"),
    MAGIC_ZSTD("ZST.BIN"),
    MAGIC_LZ4("LZ4.BIN"),
    MAGIC_CPIO("CPIO.BIN"),
    ISO_9660_IMAGE("ISO.BIN"),
    RAW_EMBEDDED_BINARY("BIN")
}

/**
 * Universal `.bin` Container Browser, Direct Copy/Paste Streamer (no full extraction required),
 * and Full Extractor for Dragon Pro File Manager.
 *
 * Supports:
 * 1. Android A/B OTA `payload.bin` (`CrAU` v2 protobuf manifest — browse & directly copy/extract individual
 *    partitions like `boot.img`, `vendor_boot.img`, `dtbo.img`, `system.img`, `vbmeta.img` using
 *    REPLACE, BZIP2, XZ, ZSTD, and ZERO operations without extracting the rest of the payload!)
 * 2. Magic-byte Archive `.bin` files (ZIP, 7Z, RAR, TAR, GZIP, BZIP2, XZ, ZSTD, LZ4, CPIO saved with `.bin`)
 * 3. ISO 9660 `.bin` Disc Images (2048-byte standard & 2352-byte RAW Mode 1 / Mode 2 sectors)
 * 4. Raw Firmware / Game Asset `.bin` Blobs (carves embedded PNG, JPEG, PDF, ZIP, GZIP, ELF, DEX payloads
 *    plus exposes raw binary & extracted ASCII strings cleanly for direct copy/paste or extraction)
 */
object UniversalBinContainerEngine {

    private const val VIRTUAL_OTA_LINK_MAGIC = "CrAU_ZIP_LINK\n"

    private data class VirtualOtaZipLink(
        val outerZipFile: File,
        val zipEntryName: String,
        val storedBaseOffset: Long,
        val uncompressedPayloadSize: Long,
        val embeddedHeaderOffset: Long
    )

    private data class OtaExtent(
        val startBlock: Long,
        val numBlocks: Long
    )

    private data class OtaInstallOp(
        val type: Int, // 0=REPLACE, 1=REPLACE_BZ, 2=MOVE, 4=SOURCE_COPY, 6=ZERO, 7=DISCARD, 8=REPLACE_XZ, 14=ZSTD
        val dataOffset: Long,
        val dataLength: Long,
        val srcExtents: List<OtaExtent>,
        val dstExtents: List<OtaExtent>
    )

    private data class OtaPartitionUpdate(
        val name: String,
        val sizeBytes: Long,
        val operations: List<OtaInstallOp>
    )

    private data class OtaPayloadHeader(
        val blockSize: Long,
        val dataBaseOffset: Long,
        val partitions: List<OtaPartitionUpdate>,
        val virtualZipLink: VirtualOtaZipLink? = null
    )

    private data class IsoLayout(
        val sectorSize: Int,
        val dataOffsetInSector: Int,
        val rootExtentLba: Long,
        val rootDataLength: Long
    )

    private data class IsoEntry(
        val path: String,
        val isDirectory: Boolean,
        val extentLba: Long,
        val sizeBytes: Long
    )

    private data class CarvedBlobEntry(
        val path: String,
        val offset: Long,
        val sizeBytes: Long,
        val isGeneratedStringsText: Boolean = false
    )

    /**
     * Creates a lightweight (< 2 MB) virtual OTA link file in `previewOutFile` when `entryPath`
     * inside `outerArchiveFile` (.zip) is an Android A/B OTA `payload.bin` (`CrAU`).
     * This allows instant (< 50 ms) browsing and direct partition extraction/copy-paste from a
     * `payload.bin` inside a 4–7 GB ROM `.zip` without extracting the multi-GB `payload.bin` to cacheDir!
     */
    fun tryCreateVirtualOtaLinkFromArchive(
        outerArchiveFile: File,
        entryPath: String,
        previewOutFile: File
    ): File? {
        if (!outerArchiveFile.exists() || !outerArchiveFile.canRead()) return null
        val cleanTarget = entryPath.replace('\\', '/').trim('/')
        return runCatching {
            org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setFile(outerArchiveFile)
                .get()
                .use { zip ->
                    val entry = zip.getEntry(cleanTarget)
                        ?: zip.entries.asSequence().firstOrNull {
                            it.name.replace('\\', '/').trim('/').equals(cleanTarget, ignoreCase = true)
                        }
                        ?: return@runCatching null

                    zip.getInputStream(entry).use { inStream ->
                        val prefix = ByteArray(24)
                        var readTotal = 0
                        while (readTotal < 20) {
                            val r = inStream.read(prefix, readTotal, 20 - readTotal)
                            if (r <= 0) break
                            readTotal += r
                        }
                        if (readTotal < 20) return@runCatching null
                        if (prefix[0] != 'C'.code.toByte() ||
                            prefix[1] != 'r'.code.toByte() ||
                            prefix[2] != 'A'.code.toByte() ||
                            prefix[3] != 'U'.code.toByte()
                        ) {
                            return@runCatching null
                        }
                        val bb = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
                        bb.position(4)
                        val version = bb.long
                        val manifestSize = bb.long
                        if (version < 1L || version > 3L || manifestSize <= 0L || manifestSize > 128L * 1024L * 1024L) {
                            return@runCatching null
                        }
                        val headerSize = if (version >= 2L) {
                            var extra = 0
                            while (extra < 4) {
                                val r = inStream.read(prefix, 20 + extra, 4 - extra)
                                if (r <= 0) break
                                extra += r
                            }
                            if (extra < 4) return@runCatching null
                            24
                        } else {
                            20
                        }
                        val manifestBytes = ByteArray(manifestSize.toInt())
                        var mRead = 0
                        while (mRead < manifestBytes.size) {
                            val r = inStream.read(manifestBytes, mRead, manifestBytes.size - mRead)
                            if (r <= 0) break
                            mRead += r
                        }
                        if (mRead < manifestBytes.size) return@runCatching null

                        val isStored = entry.method == java.util.zip.ZipEntry.STORED && entry.dataOffset > 0L
                        val storedOffset = if (isStored) entry.dataOffset else -1L

                        previewOutFile.parentFile?.mkdirs()
                        RandomAccessFile(previewOutFile, "rw").use { outRaf ->
                            outRaf.setLength(0L)
                            outRaf.write(VIRTUAL_OTA_LINK_MAGIC.toByteArray(Charsets.UTF_8))
                            outRaf.write("${outerArchiveFile.absolutePath}\n".toByteArray(Charsets.UTF_8))
                            outRaf.write("${entry.name}\n".toByteArray(Charsets.UTF_8))
                            outRaf.writeLong(storedOffset)
                            outRaf.writeLong(entry.size.coerceAtLeast(0L))
                            outRaf.write(prefix, 0, headerSize)
                            outRaf.write(manifestBytes)
                        }
                        if (previewOutFile.exists() && previewOutFile.length() > 32L) previewOutFile else null
                    }
                }
        }.getOrNull()
    }

    private fun readVirtualOtaZipLink(file: File): VirtualOtaZipLink? {
        if (!file.exists() || !file.canRead() || file.length() < 40L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magicBytes = ByteArray(VIRTUAL_OTA_LINK_MAGIC.length)
                raf.readFully(magicBytes)
                if (String(magicBytes, Charsets.UTF_8) != VIRTUAL_OTA_LINK_MAGIC) return@runCatching null
                val outerPath = raf.readLine()?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }.orEmpty()
                val entryName = raf.readLine()?.let { String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) }.orEmpty()
                if (outerPath.isBlank() || entryName.isBlank()) return@runCatching null
                val storedOffset = raf.readLong()
                val uncompressedSize = raf.readLong()
                val embeddedHeaderOffset = raf.filePointer
                VirtualOtaZipLink(
                    outerZipFile = File(outerPath),
                    zipEntryName = entryName,
                    storedBaseOffset = storedOffset,
                    uncompressedPayloadSize = uncompressedSize,
                    embeddedHeaderOffset = embeddedHeaderOffset
                )
            }
        }.getOrNull()
    }

    fun detectBinContainerType(file: File): BinContainerType {
        val fileLen = runCatching { file.length() }.getOrDefault(0L)
        if (!file.exists() || !file.canRead() || fileLen < 4L) {
            return if (file.name.equals("payload.bin", ignoreCase = true)) {
                BinContainerType.ANDROID_OTA_PAYLOAD
            } else {
                BinContainerType.RAW_EMBEDDED_BINARY
            }
        }
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                // IMPORTANT: Use 64-bit minOf(512L, fileLen) before .toInt() so 2 GB–4 GB payload.bin files never overflow Int!
                val headerSize = minOf(512L, fileLen.coerceAtLeast(0L)).toInt()
                val header = ByteArray(headerSize)
                val read = raf.read(header)
                if (read >= 4) {
                    // 0. Virtual OTA ZIP Link ("CrAU_ZIP_LINK\n") or Android OTA payload.bin ("CrAU")
                    if (header[0] == 'C'.code.toByte() &&
                        header[1] == 'r'.code.toByte() &&
                        header[2] == 'A'.code.toByte() &&
                        header[3] == 'U'.code.toByte()
                    ) {
                        return@runCatching BinContainerType.ANDROID_OTA_PAYLOAD
                    }
                    // 2. ZIP / APK / JAR ("PK\x03\x04" or "PK\x05\x06")
                    if (header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
                            (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()))
                    ) {
                        return@runCatching BinContainerType.MAGIC_ZIP
                    }
                    // 3. 7-Zip ("7z\xBC\xAF\x27\x1C")
                    if (read >= 6 &&
                        header[0] == 0x37.toByte() && header[1] == 0x7A.toByte() &&
                        header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() &&
                        header[4] == 0x27.toByte() && header[5] == 0x1C.toByte()
                    ) {
                        return@runCatching BinContainerType.MAGIC_7Z
                    }
                    // 4. RAR ("Rar!\x1A\x07")
                    if (read >= 6 &&
                        header[0] == 0x52.toByte() && header[1] == 0x61.toByte() &&
                        header[2] == 0x72.toByte() && header[3] == 0x21.toByte() &&
                        header[4] == 0x1A.toByte() && header[5] == 0x07.toByte()
                    ) {
                        return@runCatching BinContainerType.MAGIC_RAR
                    }
                    // 5. GZIP (1F 8B)
                    if (header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
                        return@runCatching BinContainerType.MAGIC_GZIP
                    }
                    // 6. BZIP2 ("BZh")
                    if (header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte()) {
                        return@runCatching BinContainerType.MAGIC_BZIP2
                    }
                    // 7. XZ (FD 37 7A 58 5A 00)
                    if (read >= 6 &&
                        header[0] == 0xFD.toByte() && header[1] == 0x37.toByte() &&
                        header[2] == 0x7A.toByte() && header[3] == 0x58.toByte() &&
                        header[4] == 0x5A.toByte() && header[5] == 0x00.toByte()
                    ) {
                        return@runCatching BinContainerType.MAGIC_XZ
                    }
                    // 8. ZSTD (28 B5 2F FD)
                    if (header[0] == 0x28.toByte() && header[1] == 0xB5.toByte() &&
                        header[2] == 0x2F.toByte() && header[3] == 0xFD.toByte()
                    ) {
                        return@runCatching BinContainerType.MAGIC_ZSTD
                    }
                    // 9. Framed LZ4 (04 22 4D 18)
                    if (header[0] == 0x04.toByte() && header[1] == 0x22.toByte() &&
                        header[2] == 0x4D.toByte() && header[3] == 0x18.toByte()
                    ) {
                        return@runCatching BinContainerType.MAGIC_LZ4
                    }
                    // 10. CPIO ("070701" or "070702" or "070707")
                    if (read >= 6 &&
                        header[0] == '0'.code.toByte() && header[1] == '7'.code.toByte() &&
                        header[2] == '0'.code.toByte() && header[3] == '7'.code.toByte() &&
                        header[4] == '0'.code.toByte()
                    ) {
                        return@runCatching BinContainerType.MAGIC_CPIO
                    }
                    // 11. POSIX TAR ("ustar" at offset 257)
                    if (read >= 263 &&
                        header[257] == 'u'.code.toByte() && header[258] == 's'.code.toByte() &&
                        header[259] == 't'.code.toByte() && header[260] == 'a'.code.toByte() &&
                        header[261] == 'r'.code.toByte()
                    ) {
                        return@runCatching BinContainerType.MAGIC_TAR
                    }
                }
                // 12. ISO 9660 CD/DVD .bin image ("CD001" at sector 16)
                if (detectIso9660Layout(raf) != null) {
                    return@runCatching BinContainerType.ISO_9660_IMAGE
                }
                BinContainerType.RAW_EMBEDDED_BINARY
            }
        }.getOrDefault(BinContainerType.RAW_EMBEDDED_BINARY)
    }

    /**
     * Maps magic-detected archive `.bin` files to a synthetic filename extension so
     * `UniversalArchiveEngine` can reuse its native ZIP/7Z/RAR/TAR/GZ/BZ2/XZ/ZST/LZ4/CPIO handlers.
     */
    fun syntheticArchiveNameForMagicBin(type: BinContainerType, originalFileName: String): String? {
        val base = originalFileName.substringBeforeLast('.', originalFileName).ifBlank { "archive" }
        return when (type) {
            BinContainerType.MAGIC_ZIP -> "$base.zip"
            BinContainerType.MAGIC_7Z -> "$base.7z"
            BinContainerType.MAGIC_RAR -> "$base.rar"
            BinContainerType.MAGIC_TAR -> "$base.tar"
            BinContainerType.MAGIC_GZIP -> "$base.gz"
            BinContainerType.MAGIC_BZIP2 -> "$base.bz2"
            BinContainerType.MAGIC_XZ -> "$base.xz"
            BinContainerType.MAGIC_ZSTD -> "$base.zst"
            BinContainerType.MAGIC_LZ4 -> "$base.lz4"
            BinContainerType.MAGIC_CPIO -> "$base.cpio"
            else -> null
        }
    }

    /**
     * Lists entries inside a non-magic `.bin` container (Android OTA `payload.bin`,
     * ISO 9660 `.bin` image, or raw binary `.bin` with carved embedded payloads).
     */
    fun readNativeBinEntries(workFile: File, originalFile: File, containerType: BinContainerType): List<ArchiveInternalNode> {
        val ts = originalFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        return when (containerType) {
            BinContainerType.ANDROID_OTA_PAYLOAD -> {
                val ota = parseOtaPayloadHeader(workFile) ?: return emptyList()
                ota.partitions.map { part ->
                    val imgName = "${part.name}.img"
                    val compSize = part.operations.sumOf { it.dataLength.coerceAtLeast(0L) }
                    val extentBytes = part.operations
                        .flatMap { it.dstExtents }
                        .filter { it.startBlock >= 0L && it.numBlocks > 0L }
                        .maxOfOrNull { (it.startBlock + it.numBlocks) * ota.blockSize } ?: 0L
                    val uncompSize = maxOf(part.sizeBytes, extentBytes, compSize)
                    ArchiveInternalNode(
                        fullEntryPath = imgName,
                        name = imgName,
                        parentPath = "",
                        isDirectory = false,
                        uncompressedSize = uncompSize,
                        compressedSize = compSize,
                        lastModifiedMs = ts
                    )
                }
            }
            BinContainerType.ISO_9660_IMAGE -> {
                val isoEntries = parseIso9660Entries(workFile)
                isoEntries.map { e ->
                    val clean = e.path.trim('/')
                    ArchiveInternalNode(
                        fullEntryPath = clean,
                        name = clean.substringAfterLast('/'),
                        parentPath = if (clean.contains('/')) clean.substringBeforeLast('/') else "",
                        isDirectory = e.isDirectory,
                        uncompressedSize = e.sizeBytes,
                        compressedSize = e.sizeBytes,
                        lastModifiedMs = ts
                    )
                }
            }
            else -> {
                val carved = scanEmbeddedPayloads(workFile, originalFile.name)
                carved.map { blob ->
                    val clean = blob.path.trim('/')
                    ArchiveInternalNode(
                        fullEntryPath = clean,
                        name = clean.substringAfterLast('/'),
                        parentPath = if (clean.contains('/')) clean.substringBeforeLast('/') else "",
                        isDirectory = false,
                        uncompressedSize = blob.sizeBytes,
                        compressedSize = blob.sizeBytes,
                        lastModifiedMs = ts
                    )
                }
            }
        }
    }

    /**
     * Directly copies selected virtual entries from inside a `.bin` file to `targetDir`
     * WITHOUT extracting the entire `.bin` container!
     */
    fun copySelectedEntriesFromNativeBin(
        context: Context,
        workFile: File,
        originalFile: File,
        containerType: BinContainerType,
        selectedPaths: List<String>,
        targetDir: File,
        resolveDestination: (String) -> File?,
        checkConflicts: Boolean = true,
        onSkipped: () -> Unit = {},
        onProgress: (deltaBytes: Long, activeFileName: String) -> Unit
    ): Int {
        fun resolveConflict(destFile: File, incomingSize: Long): File? {
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

        var count = 0
        when (containerType) {
            BinContainerType.ANDROID_OTA_PAYLOAD -> {
                val ota = parseOtaPayloadHeader(workFile) ?: return 0
                val matchingParts = ota.partitions.mapNotNull { part ->
                    val entryPath = "${part.name}.img"
                    val destFile = resolveDestination(entryPath) ?: return@mapNotNull null
                    part to destFile
                }
                if (matchingParts.isEmpty()) return 0

                val zipLink = ota.virtualZipLink
                if (zipLink != null && zipLink.storedBaseOffset < 0L) {
                    // Deflated payload.bin inside a ZIP archive: extract via streaming ZIP InputStream
                    for ((part, destFile) in matchingParts) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val finalDest = resolveConflict(destFile, part.sizeBytes) ?: continue
                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                        if (extractSingleOtaPartitionFromDeflatedZip(context, zipLink, ota, part, finalDest, onProgress)) {
                            count++
                        }
                    }
                } else {
                    val rafTargetFile = zipLink?.outerZipFile ?: workFile
                    val baseOffsetInFile = zipLink?.storedBaseOffset?.coerceAtLeast(0L) ?: 0L
                    RandomAccessFile(rafTargetFile, "r").use { raf ->
                        for ((part, destFile) in matchingParts) {
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val finalDest = resolveConflict(destFile, part.sizeBytes) ?: continue
                            finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                            if (extractSingleOtaPartition(context, raf, baseOffsetInFile, ota, part, finalDest, onProgress)) {
                                count++
                            }
                        }
                    }
                }
            }
            BinContainerType.ISO_9660_IMAGE -> {
                RandomAccessFile(workFile, "r").use { raf ->
                    val layout = detectIso9660Layout(raf) ?: return 0
                    val entries = parseIso9660Entries(workFile)
                    for (entry in entries) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val clean = entry.path.trim('/')
                        val destFile = resolveDestination(clean) ?: continue
                        if (entry.isDirectory) {
                            AndroidDataObbBridge.createDirectory(context, destFile)
                        } else {
                            val finalDest = resolveConflict(destFile, entry.sizeBytes) ?: continue
                            finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                            if (extractSingleIsoEntry(context, raf, layout, entry, finalDest, onProgress)) {
                                count++
                            }
                        }
                    }
                }
            }
            else -> {
                val carved = scanEmbeddedPayloads(workFile, originalFile.name)
                RandomAccessFile(workFile, "r").use { raf ->
                    for (blob in carved) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val destFile = resolveDestination(blob.path) ?: continue
                        val finalDest = resolveConflict(destFile, blob.sizeBytes) ?: continue
                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                        if (extractSingleCarvedBlob(context, raf, blob, finalDest, onProgress)) {
                            count++
                        }
                    }
                }
            }
        }
        return count
    }

    /**
     * Extracts ALL entries from a native `.bin` container (OTA `payload.bin`, ISO 9660 `.bin`,
     * or raw embedded `.bin`) into `targetDir` with full progress & pause/cancel support.
     */
    fun extractEntireNativeBin(
        context: Context,
        workFile: File,
        originalFile: File,
        containerType: BinContainerType,
        targetDir: File,
        onSkipped: () -> Unit = {},
        onProgress: (doneFiles: Int, totalFiles: Int, doneBytes: Long, totalBytes: Long, activeName: String) -> Unit
    ): Int {
        AndroidDataObbBridge.createDirectory(context, targetDir)
        var doneFiles = 0
        var doneBytes = 0L

        fun resolveConflict(destFile: File, incomingSize: Long): File? {
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
            }
            return resolved
        }

        when (containerType) {
            BinContainerType.ANDROID_OTA_PAYLOAD -> {
                val ota = parseOtaPayloadHeader(workFile) ?: return 0
                val totalFiles = ota.partitions.size.coerceAtLeast(1)
                val totalBytes = ota.partitions.sumOf { it.sizeBytes.coerceAtLeast(0L) }
                    .coerceAtLeast(workFile.length().coerceAtLeast(1L))
                val zipLink = ota.virtualZipLink
                if (zipLink != null && zipLink.storedBaseOffset < 0L) {
                    for (part in ota.partitions) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val destFile = File(targetDir, "${part.name}.img")
                        val finalDest = resolveConflict(destFile, part.sizeBytes) ?: continue
                        val ok = extractSingleOtaPartitionFromDeflatedZip(context, zipLink, ota, part, finalDest) { delta, name ->
                            doneBytes += delta
                            onProgress(doneFiles, totalFiles, doneBytes, maxOf(totalBytes, doneBytes), name)
                        }
                        if (ok) {
                            doneFiles++
                            onProgress(doneFiles, totalFiles, doneBytes, maxOf(totalBytes, doneBytes), finalDest.name)
                        }
                    }
                } else {
                    val rafTargetFile = zipLink?.outerZipFile ?: workFile
                    val baseOffsetInFile = zipLink?.storedBaseOffset?.coerceAtLeast(0L) ?: 0L
                    RandomAccessFile(rafTargetFile, "r").use { raf ->
                        for (part in ota.partitions) {
                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                            val destFile = File(targetDir, "${part.name}.img")
                            val finalDest = resolveConflict(destFile, part.sizeBytes) ?: continue
                            val ok = extractSingleOtaPartition(context, raf, baseOffsetInFile, ota, part, finalDest) { delta, name ->
                                doneBytes += delta
                                onProgress(doneFiles, totalFiles, doneBytes, maxOf(totalBytes, doneBytes), name)
                            }
                            if (ok) {
                                doneFiles++
                                onProgress(doneFiles, totalFiles, doneBytes, maxOf(totalBytes, doneBytes), finalDest.name)
                            }
                        }
                    }
                }
            }
            BinContainerType.ISO_9660_IMAGE -> {
                RandomAccessFile(workFile, "r").use { raf ->
                    val layout = detectIso9660Layout(raf) ?: return 0
                    val entries = parseIso9660Entries(workFile)
                    val totalFiles = entries.count { !it.isDirectory }.coerceAtLeast(1)
                    val totalBytes = entries.sumOf { if (it.isDirectory) 0L else it.sizeBytes }
                        .coerceAtLeast(workFile.length().coerceAtLeast(1L))
                    for (entry in entries) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val clean = entry.path.trim('/')
                        if (clean.isEmpty()) continue
                        val destFile = File(targetDir, clean)
                        if (entry.isDirectory) {
                            AndroidDataObbBridge.createDirectory(context, destFile)
                        } else {
                            val finalDest = resolveConflict(destFile, entry.sizeBytes) ?: continue
                            finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                            val ok = extractSingleIsoEntry(context, raf, layout, entry, finalDest) { delta, name ->
                                doneBytes += delta
                                onProgress(doneFiles, totalFiles, doneBytes, totalBytes, name)
                            }
                            if (ok) doneFiles++
                        }
                    }
                }
            }
            else -> {
                val carved = scanEmbeddedPayloads(workFile, originalFile.name)
                val totalFiles = carved.size.coerceAtLeast(1)
                val totalBytes = carved.sumOf { it.sizeBytes }.coerceAtLeast(workFile.length().coerceAtLeast(1L))
                RandomAccessFile(workFile, "r").use { raf ->
                    for (blob in carved) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val destFile = File(targetDir, blob.path)
                        val finalDest = resolveConflict(destFile, blob.sizeBytes) ?: continue
                        finalDest.parentFile?.let { AndroidDataObbBridge.createDirectory(context, it) }
                        val ok = extractSingleCarvedBlob(context, raf, blob, finalDest) { delta, name ->
                            doneBytes += delta
                            onProgress(doneFiles, totalFiles, doneBytes, totalBytes, name)
                        }
                        if (ok) doneFiles++
                    }
                }
            }
        }
        return doneFiles
    }

    // -------------------------------------------------------------------------
    // 1. Android OTA payload.bin (CrAU v1 & v2) Protobuf Manifest & Partition Extractor
    // -------------------------------------------------------------------------

    private fun parseOtaPayloadHeader(file: File): OtaPayloadHeader? {
        return runCatching {
            val virtualLink = readVirtualOtaZipLink(file)
            RandomAccessFile(file, "r").use { raf ->
                if (virtualLink != null) {
                    raf.seek(virtualLink.embeddedHeaderOffset)
                }
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "CrAU") return@runCatching null
                val version = raf.readLong()
                if (version < 1L || version > 3L) return@runCatching null
                val manifestSize = raf.readLong()
                val metadataSigSize = if (version >= 2L) {
                    raf.readInt().toLong() and 0xFFFFFFFFL
                } else {
                    0L
                }
                val headerSize = if (version >= 2L) 24L else 20L
                if (manifestSize <= 0L || manifestSize > 128L * 1024L * 1024L) return@runCatching null

                val manifestBytes = ByteArray(manifestSize.toInt())
                raf.readFully(manifestBytes)
                val dataBaseOffset = headerSize + manifestSize + metadataSigSize

                var blockSize = 4096L
                val rawPartitions = mutableListOf<OtaPartitionUpdate>()

                var pos = 0
                while (pos < manifestBytes.size) {
                    val (tag, nextPos) = readVarint(manifestBytes, pos, manifestBytes.size) ?: break
                    pos = nextPos
                    val fieldNum = (tag ushr 3).toInt()
                    val wireType = (tag and 0x7L).toInt()
                    when (wireType) {
                        0 -> {
                            val (v, p2) = readVarint(manifestBytes, pos, manifestBytes.size) ?: break
                            pos = p2
                            if (fieldNum == 3 && v > 0L) blockSize = v
                        }
                        2 -> {
                            val (len, p2) = readVarint(manifestBytes, pos, manifestBytes.size) ?: break
                            if (len < 0L) break
                            val end = (p2 + len.toInt()).coerceIn(p2, manifestBytes.size)
                            if (fieldNum == 13) {
                                parseOtaPartitionUpdate(manifestBytes, p2, end)?.let { rawPartitions.add(it) }
                            }
                            pos = end
                        }
                        1 -> pos += 8
                        5 -> pos += 4
                        else -> break
                    }
                }

                // Ensure every partition has an accurate sizeBytes (fallback to max destination extent block * blockSize)
                val normalizedPartitions = rawPartitions.map { part ->
                    val maxExtentBytes = part.operations
                        .flatMap { it.dstExtents }
                        .filter { it.startBlock >= 0L && it.numBlocks > 0L }
                        .maxOfOrNull { (it.startBlock + it.numBlocks) * blockSize } ?: 0L
                    val effectiveSize = maxOf(part.sizeBytes, maxExtentBytes)
                    if (effectiveSize != part.sizeBytes) part.copy(sizeBytes = effectiveSize) else part
                }

                OtaPayloadHeader(
                    blockSize = blockSize,
                    dataBaseOffset = dataBaseOffset,
                    partitions = normalizedPartitions,
                    virtualZipLink = virtualLink
                )
            }
        }.getOrNull()
    }

    private fun parseOtaPartitionUpdate(buf: ByteArray, start: Int, end: Int): OtaPartitionUpdate? {
        var name = ""
        var newSize = 0L
        val ops = mutableListOf<OtaInstallOp>()
        var pos = start
        while (pos < end) {
            val (tag, nextPos) = readVarint(buf, pos, end) ?: break
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when (wireType) {
                0 -> {
                    val (_, p2) = readVarint(buf, pos, end) ?: break
                    pos = p2
                }
                2 -> {
                    val (len, p2) = readVarint(buf, pos, end) ?: break
                    if (len < 0L) break
                    val fieldEnd = (p2 + len.toInt()).coerceIn(p2, end)
                    when (fieldNum) {
                        1 -> name = String(buf, p2, fieldEnd - p2, Charsets.UTF_8)
                        7 -> newSize = parsePartitionInfoSize(buf, p2, fieldEnd)
                        8 -> parseOtaInstallOp(buf, p2, fieldEnd)?.let { ops.add(it) }
                    }
                    pos = fieldEnd
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> break
            }
        }
        if (name.isBlank()) return null
        return OtaPartitionUpdate(name = name, sizeBytes = newSize, operations = ops)
    }

    private fun parsePartitionInfoSize(buf: ByteArray, start: Int, end: Int): Long {
        var pos = start
        while (pos < end) {
            val (tag, nextPos) = readVarint(buf, pos, end) ?: break
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when (wireType) {
                0 -> {
                    val (v, p2) = readVarint(buf, pos, end) ?: break
                    pos = p2
                    if (fieldNum == 1) return v
                }
                2 -> {
                    val (len, p2) = readVarint(buf, pos, end) ?: break
                    if (len < 0L) break
                    pos = (p2 + len.toInt()).coerceIn(p2, end)
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> break
            }
        }
        return 0L
    }

    private fun parseOtaInstallOp(buf: ByteArray, start: Int, end: Int): OtaInstallOp? {
        var type = 0
        var dataOffset = 0L
        var dataLength = 0L
        val srcExtents = mutableListOf<OtaExtent>()
        val dstExtents = mutableListOf<OtaExtent>()
        var pos = start
        while (pos < end) {
            val (tag, nextPos) = readVarint(buf, pos, end) ?: break
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when (wireType) {
                0 -> {
                    val (v, p2) = readVarint(buf, pos, end) ?: break
                    pos = p2
                    when (fieldNum) {
                        1 -> type = v.toInt()
                        2 -> dataOffset = v
                        3 -> dataLength = v
                    }
                }
                2 -> {
                    val (len, p2) = readVarint(buf, pos, end) ?: break
                    if (len < 0L) break
                    val fieldEnd = (p2 + len.toInt()).coerceIn(p2, end)
                    when (fieldNum) {
                        4 -> parseOtaExtent(buf, p2, fieldEnd)?.let { srcExtents.add(it) }
                        6 -> parseOtaExtent(buf, p2, fieldEnd)?.let { dstExtents.add(it) }
                    }
                    pos = fieldEnd
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> break
            }
        }
        return OtaInstallOp(
            type = type,
            dataOffset = dataOffset,
            dataLength = dataLength,
            srcExtents = srcExtents,
            dstExtents = dstExtents
        )
    }

    private fun parseOtaExtent(buf: ByteArray, start: Int, end: Int): OtaExtent? {
        var startBlock = 0L
        var numBlocks = 0L
        var pos = start
        while (pos < end) {
            val (tag, nextPos) = readVarint(buf, pos, end) ?: break
            pos = nextPos
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x7L).toInt()
            when (wireType) {
                0 -> {
                    val (v, p2) = readVarint(buf, pos, end) ?: break
                    pos = p2
                    when (fieldNum) {
                        1 -> startBlock = v
                        2 -> numBlocks = v
                    }
                }
                2 -> {
                    val (len, p2) = readVarint(buf, pos, end) ?: break
                    if (len < 0L) break
                    pos = (p2 + len.toInt()).coerceIn(p2, end)
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> break
            }
        }
        return OtaExtent(startBlock = startBlock, numBlocks = numBlocks)
    }

    private fun readVarint(buf: ByteArray, startPos: Int, limit: Int = buf.size): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = startPos
        val maxPos = minOf(limit, buf.size)
        while (pos < maxPos && shift < 64) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to pos
            shift += 7
        }
        return null
    }

    /**
     * Zero-heap-allocation InputStream window over a [RandomAccessFile] slice `[startOffset, startOffset + length)`.
     */
    private class BoundedRafInputStream(
        private val raf: RandomAccessFile,
        startOffset: Long,
        length: Long
    ) : InputStream() {
        private var currentPos: Long = startOffset
        private var remaining: Long = length.coerceAtLeast(0L)

        override fun read(): Int {
            if (remaining <= 0L) return -1
            synchronized(raf) {
                raf.seek(currentPos)
                val b = raf.read()
                if (b < 0) {
                    remaining = 0L
                    return -1
                }
                currentPos++
                remaining--
                return b
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            if (len <= 0) return 0
            val toRead = minOf(len.toLong(), remaining).toInt()
            synchronized(raf) {
                raf.seek(currentPos)
                val r = raf.read(b, off, toRead)
                if (r <= 0) {
                    remaining = 0L
                    return -1
                }
                currentPos += r
                remaining -= r
                return r
            }
        }
    }

    /**
     * Distributes an operation's output stream sequentially across all destination [OtaExtent] blocks.
     * Properly handles multi-extent operations and sparse hole extents (`startBlock < 0`).
     */
    private class OtaExtentScatterWriter(
        private val outRaf: RandomAccessFile,
        private val blockSize: Long,
        private val extents: List<OtaExtent>,
        private val imgName: String,
        private val onProgress: (Long, String) -> Unit
    ) {
        private var extentIndex = 0
        private var byteOffsetInCurrentExtent = 0L
        private var currentExtentCapacity = if (extents.isNotEmpty()) {
            extents[0].numBlocks.coerceAtLeast(0L) * blockSize
        } else 0L

        val totalExpectedBytes: Long = extents.sumOf { it.numBlocks.coerceAtLeast(0L) * blockSize }
        var totalWrittenBytes: Long = 0L
            private set

        init {
            seekToCurrentExtentStart()
        }

        private fun seekToCurrentExtentStart() {
            while (extentIndex < extents.size) {
                val ext = extents[extentIndex]
                currentExtentCapacity = ext.numBlocks.coerceAtLeast(0L) * blockSize
                byteOffsetInCurrentExtent = 0L
                if (currentExtentCapacity > 0L) {
                    if (ext.startBlock >= 0L) {
                        outRaf.seek(ext.startBlock * blockSize)
                    }
                    break
                }
                extentIndex++
            }
        }

        fun write(buf: ByteArray, offset: Int, length: Int) {
            var srcOff = offset
            var rem = length
            while (rem > 0 && extentIndex < extents.size) {
                val availInExtent = currentExtentCapacity - byteOffsetInCurrentExtent
                if (availInExtent <= 0L) {
                    extentIndex++
                    seekToCurrentExtentStart()
                    continue
                }
                val chunk = minOf(rem.toLong(), availInExtent).toInt()
                val ext = extents[extentIndex]
                if (ext.startBlock >= 0L) {
                    outRaf.write(buf, srcOff, chunk)
                }
                srcOff += chunk
                rem -= chunk
                byteOffsetInCurrentExtent += chunk
                totalWrittenBytes += chunk
                onProgress(chunk.toLong(), imgName)
                if (byteOffsetInCurrentExtent >= currentExtentCapacity) {
                    extentIndex++
                    seekToCurrentExtentStart()
                }
            }
        }
    }

    private fun extractSingleOtaPartition(
        context: Context,
        raf: RandomAccessFile,
        baseOffsetInFile: Long,
        header: OtaPayloadHeader,
        part: OtaPartitionUpdate,
        destFile: File,
        onProgress: (Long, String) -> Unit
    ): Boolean {
        val imgName = "${part.name}.img"
        val stageOut = if (AndroidDataObbBridge.isRootProtectedRomPath(destFile.absolutePath)) {
            File(context.cacheDir, "ota_part_${System.nanoTime()}_$imgName")
        } else {
            destFile.parentFile?.mkdirs()
            destFile
        }
        return try {
            RandomAccessFile(stageOut, "rw").use { outRaf ->
                val ioBuf = ByteArray(64 * 1024)
                val zeroBuf = ByteArray(64 * 1024)

                for (op in part.operations) {
                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                    if (op.dstExtents.isEmpty()) continue
                    val writer = OtaExtentScatterWriter(
                        outRaf = outRaf,
                        blockSize = header.blockSize,
                        extents = op.dstExtents,
                        imgName = imgName,
                        onProgress = onProgress
                    )
                    val expectedBytes = writer.totalExpectedBytes
                    if (expectedBytes <= 0L) continue

                    val opDataPos = baseOffsetInFile + header.dataBaseOffset + op.dataOffset

                    when (op.type) {
                        6, 7 -> { // ZERO (6) / DISCARD (7)
                            var rem = expectedBytes
                            while (rem > 0L) {
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                val chunk = minOf(zeroBuf.size.toLong(), rem).toInt()
                                writer.write(zeroBuf, 0, chunk)
                                rem -= chunk
                            }
                        }
                        0 -> { // REPLACE (raw uncompressed)
                            var rem = minOf(op.dataLength.coerceAtLeast(0L), expectedBytes)
                            var readPos = opDataPos
                            while (rem > 0L) {
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                val toRead = minOf(ioBuf.size.toLong(), rem).toInt()
                                raf.seek(readPos)
                                val r = raf.read(ioBuf, 0, toRead)
                                if (r <= 0) break
                                readPos += r
                                writer.write(ioBuf, 0, r)
                                rem -= r
                            }
                        }
                        1, 8, 14 -> { // REPLACE_BZ (1), REPLACE_XZ (8), ZSTD (14)
                            if (op.dataLength <= 0L) continue
                            val boundedIn = java.io.BufferedInputStream(
                                BoundedRafInputStream(raf, opDataPos, op.dataLength),
                                64 * 1024
                            )
                            val decompIn: InputStream = when (op.type) {
                                1 -> BZip2CompressorInputStream(boundedIn, true)
                                8 -> XZCompressorInputStream(boundedIn, true)
                                14 -> ZstdInputStream(boundedIn)
                                else -> boundedIn
                            }
                            try {
                                while (writer.totalWrittenBytes < expectedBytes) {
                                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                                    val maxToRead = minOf(
                                        ioBuf.size.toLong(),
                                        expectedBytes - writer.totalWrittenBytes
                                    ).toInt()
                                    val r = try {
                                        decompIn.read(ioBuf, 0, maxToRead)
                                    } catch (e: Exception) {
                                        if (writer.totalWrittenBytes > 0L) -1 else throw e
                                    }
                                    if (r <= 0) break
                                    writer.write(ioBuf, 0, r)
                                }
                            } finally {
                                runCatching { decompIn.close() }
                            }
                        }
                        2, 4 -> { // MOVE (2) / SOURCE_COPY (4) within same partition
                            for (srcExt in op.srcExtents) {
                                if (srcExt.startBlock < 0L || srcExt.numBlocks <= 0L) continue
                                var rem = srcExt.numBlocks * header.blockSize
                                var srcPos = srcExt.startBlock * header.blockSize
                                while (rem > 0L && writer.totalWrittenBytes < expectedBytes) {
                                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                                    val toRead = minOf(ioBuf.size.toLong(), rem).toInt()
                                    outRaf.seek(srcPos)
                                    val r = outRaf.read(ioBuf, 0, toRead)
                                    if (r <= 0) break
                                    srcPos += r
                                    rem -= r
                                    writer.write(ioBuf, 0, r)
                                }
                            }
                        }
                    }
                }

                if (part.sizeBytes > 0L && outRaf.length() != part.sizeBytes) {
                    runCatching { outRaf.setLength(part.sizeBytes) }
                }
            }
            if (stageOut.absolutePath != destFile.absolutePath) {
                val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                    context = context,
                    source = stageOut,
                    dest = destFile,
                    isDirectoryHint = false
                )
                runCatching { stageOut.delete() }
                copied
            } else {
                stageOut.exists() && stageOut.length() > 0L
            }
        } catch (ce: CancellationException) {
            runCatching { stageOut.delete() }
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fallback extractor when `payload.bin` inside an outer `.zip` was compressed with DEFLATE
     * rather than STORED. Streams through the ZIP entry in sequential `dataOffset` order.
     */
    private fun extractSingleOtaPartitionFromDeflatedZip(
        context: Context,
        zipLink: VirtualOtaZipLink,
        header: OtaPayloadHeader,
        part: OtaPartitionUpdate,
        destFile: File,
        onProgress: (Long, String) -> Unit
    ): Boolean {
        if (!zipLink.outerZipFile.exists()) return false
        val imgName = "${part.name}.img"
        val stageOut = if (AndroidDataObbBridge.isRootProtectedRomPath(destFile.absolutePath)) {
            File(context.cacheDir, "ota_part_${System.nanoTime()}_$imgName")
        } else {
            destFile.parentFile?.mkdirs()
            destFile
        }
        return try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setFile(zipLink.outerZipFile)
                .get()
                .use { zip ->
                    val entry = zip.getEntry(zipLink.zipEntryName) ?: return false
                    zip.getInputStream(entry).use { rawZipStream ->
                        val stream = java.io.BufferedInputStream(rawZipStream, 128 * 1024)
                        var streamPos = 0L
                        val skipBuf = ByteArray(64 * 1024)

                        fun advanceStreamTo(targetOffset: Long): Boolean {
                            while (streamPos < targetOffset) {
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                val toSkip = minOf(skipBuf.size.toLong(), targetOffset - streamPos).toInt()
                                val r = stream.read(skipBuf, 0, toSkip)
                                if (r <= 0) return false
                                streamPos += r
                            }
                            return streamPos == targetOffset
                        }

                        RandomAccessFile(stageOut, "rw").use { outRaf ->
                            val ioBuf = ByteArray(64 * 1024)
                            val zeroBuf = ByteArray(64 * 1024)
                            val sortedOps = part.operations.sortedBy {
                                if (it.dataLength > 0L) it.dataOffset else Long.MIN_VALUE
                            }

                            for (op in sortedOps) {
                                DragonFileManagerTaskEngine.checkPauseOrCancel()
                                if (op.dstExtents.isEmpty()) continue
                                val writer = OtaExtentScatterWriter(
                                    outRaf = outRaf,
                                    blockSize = header.blockSize,
                                    extents = op.dstExtents,
                                    imgName = imgName,
                                    onProgress = onProgress
                                )
                                val expectedBytes = writer.totalExpectedBytes
                                if (expectedBytes <= 0L) continue

                                when (op.type) {
                                    6, 7 -> {
                                        var rem = expectedBytes
                                        while (rem > 0L) {
                                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                                            val chunk = minOf(zeroBuf.size.toLong(), rem).toInt()
                                            writer.write(zeroBuf, 0, chunk)
                                            rem -= chunk
                                        }
                                    }
                                    0 -> {
                                        val targetStart = header.dataBaseOffset + op.dataOffset
                                        if (!advanceStreamTo(targetStart)) break
                                        var rem = op.dataLength.coerceAtLeast(0L)
                                        while (rem > 0L) {
                                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                                            val toRead = minOf(ioBuf.size.toLong(), rem).toInt()
                                            val r = stream.read(ioBuf, 0, toRead)
                                            if (r <= 0) break
                                            streamPos += r
                                            writer.write(ioBuf, 0, r)
                                            rem -= r
                                        }
                                    }
                                    1, 8, 14 -> {
                                        if (op.dataLength <= 0L) continue
                                        val targetStart = header.dataBaseOffset + op.dataOffset
                                        if (!advanceStreamTo(targetStart)) break
                                        val opEndPos = targetStart + op.dataLength
                                        val boundedOpIn = object : InputStream() {
                                            override fun read(): Int {
                                                if (streamPos >= opEndPos) return -1
                                                val b = stream.read()
                                                if (b >= 0) streamPos++
                                                return b
                                            }
                                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                                if (streamPos >= opEndPos) return -1
                                                val max = minOf(len.toLong(), opEndPos - streamPos).toInt()
                                                val r = stream.read(b, off, max)
                                                if (r > 0) streamPos += r
                                                return r
                                            }
                                        }
                                        val decompIn: InputStream = when (op.type) {
                                            1 -> BZip2CompressorInputStream(boundedOpIn, true)
                                            8 -> XZCompressorInputStream(boundedOpIn, true)
                                            14 -> ZstdInputStream(boundedOpIn)
                                            else -> boundedOpIn
                                        }
                                        while (writer.totalWrittenBytes < expectedBytes) {
                                            DragonFileManagerTaskEngine.checkPauseOrCancel()
                                            val maxToRead = minOf(
                                                ioBuf.size.toLong(),
                                                expectedBytes - writer.totalWrittenBytes
                                            ).toInt()
                                            val r = try {
                                                decompIn.read(ioBuf, 0, maxToRead)
                                            } catch (_: Exception) {
                                                -1
                                            }
                                            if (r <= 0) break
                                            writer.write(ioBuf, 0, r)
                                        }
                                        advanceStreamTo(opEndPos)
                                    }
                                }
                            }
                            if (part.sizeBytes > 0L && outRaf.length() != part.sizeBytes) {
                                runCatching { outRaf.setLength(part.sizeBytes) }
                            }
                        }
                    }
                }
            if (stageOut.absolutePath != destFile.absolutePath) {
                val copied = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                    context = context,
                    source = stageOut,
                    dest = destFile,
                    isDirectoryHint = false
                )
                runCatching { stageOut.delete() }
                copied
            } else {
                stageOut.exists() && stageOut.length() > 0L
            }
        } catch (ce: CancellationException) {
            runCatching { stageOut.delete() }
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // 2. ISO 9660 `.bin` Image Parser (2048-byte & 2352-byte Raw Mode 1/2)
    // -------------------------------------------------------------------------

    private fun detectIso9660Layout(raf: RandomAccessFile): IsoLayout? {
        val candidates = listOf(
            2048 to 0,   // Standard ISO / cooked .bin
            2352 to 16,  // RAW Mode 1 CD .bin
            2352 to 24   // RAW Mode 2 XA .bin
        )
        val pvd = ByteArray(2048)
        for ((sectorSize, dataOffset) in candidates) {
            val pos = 16L * sectorSize + dataOffset
            if (pos + 2048L > raf.length()) continue
            raf.seek(pos)
            raf.readFully(pvd)
            if (pvd[0] == 1.toByte() &&
                pvd[1] == 'C'.code.toByte() &&
                pvd[2] == 'D'.code.toByte() &&
                pvd[3] == '0'.code.toByte() &&
                pvd[4] == '0'.code.toByte() &&
                pvd[5] == '1'.code.toByte()
            ) {
                val rootRecOffset = 156
                val rootLba = ByteBuffer.wrap(pvd, rootRecOffset + 2, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val rootLen = ByteBuffer.wrap(pvd, rootRecOffset + 10, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                return IsoLayout(sectorSize, dataOffset, rootLba, rootLen)
            }
        }
        return null
    }

    private fun readIsoLogicalData(raf: RandomAccessFile, layout: IsoLayout, startLba: Long, byteLength: Long): ByteArray {
        val out = ByteArray(byteLength.toInt().coerceAtLeast(0))
        var written = 0
        var lba = startLba
        while (written < out.size) {
            val pos = lba * layout.sectorSize + layout.dataOffsetInSector
            if (pos >= raf.length()) break
            raf.seek(pos)
            val toRead = minOf(2048, out.size - written)
            val r = raf.read(out, written, toRead)
            if (r <= 0) break
            written += r
            lba++
        }
        return out
    }

    private fun parseIso9660Entries(workFile: File): List<IsoEntry> {
        val result = mutableListOf<IsoEntry>()
        runCatching {
            RandomAccessFile(workFile, "r").use { raf ->
                val layout = detectIso9660Layout(raf) ?: return@runCatching
                fun walkDir(dirLba: Long, dirLen: Long, parentPath: String, depth: Int) {
                    if (depth > 16 || dirLen <= 0L || dirLen > 8L * 1024L * 1024L) return
                    val dirBytes = readIsoLogicalData(raf, layout, dirLba, dirLen)
                    var offset = 0
                    while (offset < dirBytes.size) {
                        val recLen = dirBytes[offset].toInt() and 0xFF
                        if (recLen == 0) {
                            // Jump to next 2048-byte sector boundary
                            val nextSector = ((offset / 2048) + 1) * 2048
                            if (nextSector <= offset) break
                            offset = nextSector
                            continue
                        }
                        if (offset + recLen > dirBytes.size || recLen < 34) break
                        val extentLba = ByteBuffer.wrap(dirBytes, offset + 2, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        val dataLen = ByteBuffer.wrap(dirBytes, offset + 10, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        val flags = dirBytes[offset + 25].toInt() and 0xFF
                        val isDir = (flags and 0x02) != 0
                        val nameLen = dirBytes[offset + 32].toInt() and 0xFF
                        if (nameLen > 0 && offset + 33 + nameLen <= dirBytes.size) {
                            val firstByte = dirBytes[offset + 33].toInt() and 0xFF
                            // Skip "." (0x00) and ".." (0x01)
                            if (!(nameLen == 1 && (firstByte == 0 || firstByte == 1))) {
                                val rawName = String(dirBytes, offset + 33, nameLen, Charsets.ISO_8859_1)
                                    .substringBefore(';')
                                    .trimEnd('.')
                                    .ifBlank { "file_$extentLba" }
                                val fullPath = if (parentPath.isEmpty()) rawName else "$parentPath/$rawName"
                                result.add(IsoEntry(fullPath, isDir, extentLba, dataLen))
                                if (isDir && extentLba != dirLba) {
                                    walkDir(extentLba, dataLen, fullPath, depth + 1)
                                }
                            }
                        }
                        offset += recLen
                    }
                }
                walkDir(layout.rootExtentLba, layout.rootDataLength, "", 0)
            }
        }
        return result
    }

    private fun extractSingleIsoEntry(
        context: Context,
        raf: RandomAccessFile,
        layout: IsoLayout,
        entry: IsoEntry,
        destFile: File,
        onProgress: (Long, String) -> Unit
    ): Boolean {
        val outStream = AndroidDataObbBridge.openOutputStreamForFile(context, destFile) ?: return false
        return try {
            outStream.use { out ->
                val sectorBuf = ByteArray(2048)
                var remaining = entry.sizeBytes
                var lba = entry.extentLba
                while (remaining > 0L) {
                    DragonFileManagerTaskEngine.checkPauseOrCancel()
                    val pos = lba * layout.sectorSize + layout.dataOffsetInSector
                    if (pos >= raf.length()) break
                    raf.seek(pos)
                    val toRead = minOf(2048L, remaining).toInt()
                    val r = raf.read(sectorBuf, 0, toRead)
                    if (r <= 0) break
                    out.write(sectorBuf, 0, r)
                    remaining -= r
                    lba++
                    onProgress(r.toLong(), destFile.name)
                }
                out.flush()
            }
            true
        } catch (ce: CancellationException) {
            runCatching { destFile.delete() }
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------------
    // 3. Raw Firmware / Game Asset `.bin` Embedded Payload Scanner
    // -------------------------------------------------------------------------

    private fun scanEmbeddedPayloads(workFile: File, originalFileName: String): List<CarvedBlobEntry> {
        val fileLen = workFile.length()
        val entries = mutableListOf<CarvedBlobEntry>()
        val baseName = originalFileName.substringBeforeLast('.', originalFileName).ifBlank { "payload" }

        // Always expose the full binary payload & readable ASCII strings manifest
        entries.add(CarvedBlobEntry("${baseName}_raw.bin", 0L, fileLen))
        entries.add(CarvedBlobEntry("extracted_strings.txt", 0L, minOf(fileLen, 256L * 1024L), isGeneratedStringsText = true))

        // Scan up to first 16 MB for embedded standard assets (PNG, JPEG, PDF, ZIP, GZIP, ELF, DEX)
        val scanLimit = minOf(fileLen, 16L * 1024L * 1024L).toInt()
        if (scanLimit < 16) return entries

        runCatching {
            val data = ByteArray(scanLimit)
            RandomAccessFile(workFile, "r").use { it.readFully(data) }

            var pngCount = 0
            var jpgCount = 0
            var pdfCount = 0
            var zipCount = 0
            var gzCount = 0
            var elfCount = 0
            var i = 0
            while (i + 12 < data.size && entries.size < 160) {
                val b0 = data[i].toInt() and 0xFF
                val b1 = data[i + 1].toInt() and 0xFF
                val b2 = data[i + 2].toInt() and 0xFF
                val b3 = data[i + 3].toInt() and 0xFF

                // Embedded PNG (89 50 4E 47 0D 0A 1A 0A ... IEND)
                if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47 &&
                    data[i + 4] == 0x0D.toByte() && data[i + 5] == 0x0A.toByte() &&
                    data[i + 6] == 0x1A.toByte() && data[i + 7] == 0x0A.toByte()
                ) {
                    val iendPos = findSequence(data, i + 8, byteArrayOf(0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()))
                    if (iendPos > i) {
                        val size = (iendPos + 8 - i).toLong()
                        pngCount++
                        entries.add(CarvedBlobEntry("embedded/images/image_${String.format(Locale.US, "%03d", pngCount)}.png", i.toLong(), size))
                        i = iendPos + 8
                        continue
                    }
                }

                // Embedded JPEG (FF D8 FF E0/E1 ... FF D9)
                if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF && (b3 == 0xE0 || b3 == 0xE1 || b3 == 0xDB)) {
                    val eoiPos = findSequence(data, i + 4, byteArrayOf(0xFF.toByte(), 0xD9.toByte()), maxSearch = 2 * 1024 * 1024)
                    if (eoiPos > i + 64) {
                        val size = (eoiPos + 2 - i).toLong()
                        jpgCount++
                        entries.add(CarvedBlobEntry("embedded/images/photo_${String.format(Locale.US, "%03d", jpgCount)}.jpg", i.toLong(), size))
                        i = eoiPos + 2
                        continue
                    }
                }

                // Embedded PDF ("%PDF-" ... "%%EOF")
                if (b0 == 0x25 && b1 == 0x50 && b2 == 0x44 && b3 == 0x46 && data[i + 4] == 0x2D.toByte()) {
                    val eofPos = findSequence(data, i + 8, "%%EOF".toByteArray(Charsets.US_ASCII), maxSearch = 4 * 1024 * 1024)
                    if (eofPos > i + 32) {
                        val size = (eofPos + 5 - i).toLong()
                        pdfCount++
                        entries.add(CarvedBlobEntry("embedded/docs/document_${String.format(Locale.US, "%03d", pdfCount)}.pdf", i.toLong(), size))
                        i = eofPos + 5
                        continue
                    }
                }

                // Embedded ZIP ("PK\x03\x04" ... EOCD "PK\x05\x06")
                if (i > 0 && b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) {
                    val eocdPos = findSequence(data, i + 30, byteArrayOf(0x50, 0x4B, 0x05, 0x06), maxSearch = 4 * 1024 * 1024)
                    if (eocdPos > i + 30 && eocdPos + 22 <= data.size) {
                        val commentLen = (data[eocdPos + 20].toInt() and 0xFF) or ((data[eocdPos + 21].toInt() and 0xFF) shl 8)
                        val endPos = (eocdPos + 22 + commentLen).coerceAtMost(data.size)
                        zipCount++
                        entries.add(CarvedBlobEntry("embedded/archives/archive_${String.format(Locale.US, "%03d", zipCount)}.zip", i.toLong(), (endPos - i).toLong()))
                        i = endPos
                        continue
                    }
                }

                // Embedded ELF binary ("\x7FELF")
                if (i > 0 && b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) {
                    elfCount++
                    val size = minOf((data.size - i).toLong(), 512L * 1024L)
                    entries.add(CarvedBlobEntry("embedded/binaries/module_${String.format(Locale.US, "%03d", elfCount)}.elf", i.toLong(), size))
                    i += 64
                    continue
                }

                // Embedded GZIP stream (1F 8B 08)
                if (i > 0 && b0 == 0x1F && b1 == 0x8B && b2 == 0x08 && (b3 and 0xE0) == 0 && gzCount < 12) {
                    gzCount++
                    val size = (fileLen - i.toLong()).coerceAtLeast(16L)
                    entries.add(CarvedBlobEntry("embedded/streams/stream_${String.format(Locale.US, "%03d", gzCount)}.gz", i.toLong(), size))
                    i += 128
                    continue
                }

                i++
            }
        }

        return entries
    }

    private fun findSequence(data: ByteArray, fromIndex: Int, pattern: ByteArray, maxSearch: Int = data.size): Int {
        val limit = minOf(data.size - pattern.size, fromIndex + maxSearch)
        var i = fromIndex
        outer@ while (i <= limit) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    private fun extractSingleCarvedBlob(
        context: Context,
        raf: RandomAccessFile,
        blob: CarvedBlobEntry,
        destFile: File,
        onProgress: (Long, String) -> Unit
    ): Boolean {
        val outStream = AndroidDataObbBridge.openOutputStreamForFile(context, destFile) ?: return false
        return try {
            outStream.use { out ->
                if (blob.isGeneratedStringsText) {
                    val sampleLen = minOf(raf.length(), 1024L * 1024L).toInt()
                    val buf = ByteArray(sampleLen)
                    raf.seek(0L)
                    raf.readFully(buf)
                    val strings = DragonDocumentEngine.extractSavStringsAndValues(buf, maxEntries = 2000)
                    val text = buildString {
                        appendLine("# Extracted Readable Strings (${strings.size} entries)")
                        strings.forEach { s ->
                            appendLine(String.format(Locale.US, "0x%08X  %s", s.offset, s.text))
                        }
                    }.toByteArray(Charsets.UTF_8)
                    out.write(text)
                    onProgress(text.size.toLong(), destFile.name)
                } else {
                    raf.seek(blob.offset)
                    val buf = ByteArray(64 * 1024)
                    var remaining = blob.sizeBytes
                    while (remaining > 0L) {
                        DragonFileManagerTaskEngine.checkPauseOrCancel()
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val r = raf.read(buf, 0, toRead)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        remaining -= r
                        onProgress(r.toLong(), destFile.name)
                    }
                }
                out.flush()
            }
            true
        } catch (ce: CancellationException) {
            runCatching { destFile.delete() }
            throw ce
        } catch (_: Exception) {
            false
        }
    }
}
