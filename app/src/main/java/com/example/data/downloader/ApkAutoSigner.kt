package com.example.data.downloader

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * On-Device APK & Split Bundle (APKS / XAPK / APKM) Auto-Signer and Zipalign Engine.
 *
 * Enables installing and updating any unsigned, debug-stripped, or inconsistently signed
 * `.apk`, `.apks`, `.xapk`, or `.apkm` package by:
 * 1. Detecting missing, invalid, or mismatched signatures across single APKs and multi-split archives.
 * 2. Rebuilding and 4-byte / 4096-byte zipaligning uncompressed entries (`resources.arsc` and `.so` libs).
 * 3. Signing with BOTH:
 *    - V1 JAR Signature Scheme (`META-INF/MANIFEST.MF`, `META-INF/CERT.SF`, `META-INF/CERT.RSA` PKCS#7 CMS)
 *    - V2 APK Signature Scheme (`APK Sig Block 42`, Block ID `0x7109871a`, `RSA-2048` + `SHA-256` 1MB chunk tree)
 * 4. Persisting a dedicated on-device RSA-2048 signing certificate so subsequent unsigned updates to the
 *    same package share the exact same certificate and can update in-place without conflict.
 */
object ApkAutoSigner {

    private const val APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L // "Block 42" little-endian
    private const val APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L // "APK Sig " little-endian
    private const val APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871a
    private const val APK_SIGNATURE_SCHEME_V3_BLOCK_ID = -0xfac9740 // 0xf05368c0
    private const val VERITY_PADDING_BLOCK_ID = 0x42726577
    private const val SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256 = 0x0103
    private const val CHUNK_SIZE_BYTES = 1024 * 1024 // 1 MB chunks per APK Signature Scheme v2 spec

    data class SigningIdentity(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val certificateDer: ByteArray,
        val x509Certificate: X509Certificate
    )

    @Volatile
    private var cachedIdentity: SigningIdentity? = null

    fun getSignerCertificateBytes(context: Context): ByteArray {
        return getOrCreateSigningIdentity(context).certificateDer
    }

    /**
     * Checks whether a single `.apk` file has a valid v1 or v2/v3 cryptographic signature
     * recognized by Android's PackageManager.
     */
    fun isApkValidlySigned(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() < 64L) return false

        val hasStructuralSig = hasStructuralV1OrV2Signature(apkFile)
        if (!hasStructuralSig) return false

        // Also verify with Android's native PackageManager archive parser
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                }
                if (info == null) {
                    // If basic getPackageArchiveInfo(0) succeeds but GET_SIGNING_CERTIFICATES fails, it's unsigned!
                    val basicInfo = getBasicArchiveInfo(pm, apkFile.absolutePath)
                    if (basicInfo != null) return false
                    // In local JVM unit tests where PackageManager is not shadowed, fall back to structural check
                    return hasStructuralSig
                }
                val signingInfo = info.signingInfo ?: return false
                val certs = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                !certs.isNullOrEmpty()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                if (info == null) {
                    val basicInfo = getBasicArchiveInfo(pm, apkFile.absolutePath)
                    if (basicInfo != null) return false
                    return hasStructuralSig
                }
                @Suppress("DEPRECATION")
                !info.signatures.isNullOrEmpty()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks whether all split `.apk` files in a `.apks` / `.xapk` bundle are validly signed
     * and share an identical signing certificate.
     */
    fun areAllSplitsValidlyAndConsistentlySigned(context: Context, apkFiles: List<File>): Boolean {
        if (apkFiles.isEmpty()) return false
        var referenceCertBytes: ByteArray? = null

        for (apk in apkFiles) {
            if (!isApkValidlySigned(context, apk)) return false
            val certBytes = extractPrimaryCertificateBytes(context, apk)
            if (certBytes != null) {
                if (referenceCertBytes == null) {
                    referenceCertBytes = certBytes
                } else if (!referenceCertBytes.contentEquals(certBytes)) {
                    // Mismatched split certificates across the bundle
                    return false
                }
            }
        }
        return true
    }

    /**
     * Inspects an `.apk` or split bundle (`.apks` / `.xapk`) and returns `true` if the package
     * is unsigned or contains any unsigned split APK.
     */
    fun isFileOrBundleUnsigned(context: Context, file: File, isSplitBundle: Boolean): Boolean {
        if (!file.exists() || file.length() < 32L) return false
        return try {
            if (!isSplitBundle) {
                !isApkValidlySigned(context, file)
            } else {
                ZipFile(file).use { outerZip ->
                    val entries = outerZip.entries()
                    var checkedAny = false
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val lower = entry.name.lowercase(Locale.US)
                        if (lower.endsWith(".apk")) {
                            checkedAny = true
                            val tempSplit = File(
                                context.cacheDir,
                                "apk_installer_staging/sigcheck_${file.name.hashCode()}_${entry.name.hashCode()}.apk"
                            )
                            try {
                                tempSplit.parentFile?.mkdirs()
                                outerZip.getInputStream(entry).use { input ->
                                    FileOutputStream(tempSplit).use { out ->
                                        input.copyTo(out, 64 * 1024)
                                    }
                                }
                                if (!isApkValidlySigned(context, tempSplit)) {
                                    return true
                                }
                            } finally {
                                runCatching { tempSplit.delete() }
                            }
                        }
                    }
                    !checkedAny
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Ensures a single `.apk` file is validly signed (and zipaligned).
     * If `forceSign` is true or the APK is unsigned/corrupted, re-signs it with V1 + V2 APK Signature Scheme.
     */
    fun ensureApkSigned(
        context: Context,
        apkFile: File,
        forceSign: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Pair<File, Boolean> {
        if (!forceSign && isApkValidlySigned(context, apkFile)) {
            return apkFile to false
        }
        onProgress?.invoke("Auto-signing unsigned APK (${apkFile.name})...")
        val stagingDir = File(context.cacheDir, "apk_installer_staging").apply { mkdirs() }
        val signedFile = File(stagingDir, "signed_${System.currentTimeMillis()}_${apkFile.name}")
        signApkWithV1AndV2(context, apkFile, signedFile)
        return signedFile to true
    }

    /**
     * Ensures all `.apk` files in a split bundle (`.apks`, `.xapk`, `.apkm`) are validly and
     * consistently signed with the same certificate. If any split is unsigned or certificates mismatch,
     * signs all splits in the bundle with Dragon Download Manager's persistent RSA-2048 V1+V2 key.
     */
    fun ensureSplitApksSigned(
        context: Context,
        apkFiles: List<File>,
        forceSign: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): Pair<List<File>, Boolean> {
        if (apkFiles.isEmpty()) return apkFiles to false
        if (!forceSign && areAllSplitsValidlyAndConsistentlySigned(context, apkFiles)) {
            return apkFiles to false
        }

        val outList = mutableListOf<File>()
        apkFiles.forEachIndexed { idx, apkFile ->
            onProgress?.invoke("Auto-signing split APK ${idx + 1}/${apkFiles.size} (${apkFile.name})...")
            val signedOut = File(apkFile.parentFile ?: context.cacheDir, "signed_${idx}_${apkFile.name}")
            signApkWithV1AndV2(context, apkFile, signedOut)
            if (signedOut.exists() && signedOut.length() > 0L) {
                runCatching { apkFile.delete() }
                outList.add(signedOut)
            } else {
                outList.add(apkFile)
            }
        }
        return outList to true
    }

    /**
     * Signs `inputApk` into `outputApk` with:
     * 1. 4-byte / 4096-byte Zipalign for uncompressed entries (`resources.arsc`, `.so`)
     * 2. JAR Signature Scheme v1 (`META-INF/MANIFEST.MF`, `META-INF/CERT.SF`, `META-INF/CERT.RSA`)
     * 3. APK Signature Scheme v2 (`APK Sig Block 42`, ID `0x7109871a`)
     */
    fun signApkWithV1AndV2(context: Context, inputApk: File, outputApk: File) {
        val identity = getOrCreateSigningIdentity(context)
        val tempUnsignedV1Zip = File(outputApk.parentFile ?: context.cacheDir, "${outputApk.name}.v1tmp")

        try {
            // Step 1: Build clean ZIP with V1 JAR signing (META-INF/MANIFEST.MF, CERT.SF, CERT.RSA) + 4-byte alignment
            buildAlignedV1SignedZip(inputApk, tempUnsignedV1Zip, identity)

            // Step 2: Inject APK Signature Scheme v2 Signing Block before the Central Directory
            injectApkSignatureSchemeV2Block(tempUnsignedV1Zip, outputApk, identity)
        } finally {
            runCatching { tempUnsignedV1Zip.delete() }
        }
    }

    private fun hasStructuralV1OrV2Signature(apkFile: File): Boolean {
        // 1. Check for APK Signature Scheme v2/v3 Block right before ZIP Central Directory
        if (hasApkSigningBlockV2OrV3(apkFile)) {
            return true
        }
        // 2. Check for V1 JAR signing files inside META-INF/
        return try {
            ZipFile(apkFile).use { zip ->
                var hasManifestMf = false
                var hasSf = false
                var hasCertBlock = false
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val upper = entry.name.replace('\\', '/').uppercase(Locale.US)
                    if (upper == "META-INF/MANIFEST.MF" && entry.size > 0L) {
                        hasManifestMf = true
                    } else if (upper.startsWith("META-INF/") && upper.endsWith(".SF") && entry.size > 0L) {
                        hasSf = true
                    } else if (upper.startsWith("META-INF/") &&
                        (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")) &&
                        entry.size > 0L
                    ) {
                        hasCertBlock = true
                    }
                    if (hasManifestMf && hasSf && hasCertBlock) return true
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun hasApkSigningBlockV2OrV3(apkFile: File): Boolean {
        return try {
            RandomAccessFile(apkFile, "r").use { raf ->
                val eocdInfo = locateZipEocd(raf) ?: return false
                val cdOffset = eocdInfo.cdOffset
                if (cdOffset < 32L) return false

                // Read 24-byte footer immediately preceding cdOffset: [sizeOfBlock: uint64][magic: 16 bytes]
                raf.seek(cdOffset - 24L)
                val footerBuf = ByteArray(24)
                raf.readFully(footerBuf)
                val bb = ByteBuffer.wrap(footerBuf).order(ByteOrder.LITTLE_ENDIAN)
                val sizeInFooter = bb.long
                val magicLo = bb.long
                val magicHi = bb.long
                if (magicLo != APK_SIG_BLOCK_MAGIC_LO || magicHi != APK_SIG_BLOCK_MAGIC_HI) {
                    return false
                }
                if (sizeInFooter < 24L || sizeInFooter > cdOffset - 8L) {
                    return false
                }
                val blockStart = cdOffset - (sizeInFooter + 8L)
                raf.seek(blockStart)
                val headerBuf = ByteArray(8)
                raf.readFully(headerBuf)
                val sizeInHeader = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN).long
                if (sizeInHeader != sizeInFooter) return false

                // Scan ID-value pairs inside the APK Signing Block for v2 (0x7109871a) or v3 (0xf05368c0)
                val pairsBytesLen = (sizeInFooter - 24L).toInt()
                if (pairsBytesLen <= 12 || pairsBytesLen > 16 * 1024 * 1024) return false
                val pairsBytes = ByteArray(pairsBytesLen)
                raf.readFully(pairsBytes)
                val pairsBb = ByteBuffer.wrap(pairsBytes).order(ByteOrder.LITTLE_ENDIAN)
                while (pairsBb.remaining() >= 12) {
                    val pairLen = pairsBb.long
                    if (pairLen < 4L || pairLen > pairsBb.remaining()) break
                    val id = pairsBb.int
                    if (id == APK_SIGNATURE_SCHEME_V2_BLOCK_ID || id == APK_SIGNATURE_SCHEME_V3_BLOCK_ID) {
                        return true
                    }
                    pairsBb.position(pairsBb.position() + (pairLen.toInt() - 4))
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractPrimaryCertificateBytes(context: Context, apkFile: File): ByteArray? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val flags = PackageManager.GET_SIGNING_CERTIFICATES
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
                }
                val signingInfo = info?.signingInfo
                val sig = if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners?.firstOrNull()
                } else {
                    signingInfo?.signingCertificateHistory?.firstOrNull()
                }
                sig?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info?.signatures?.firstOrNull()?.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getBasicArchiveInfo(pm: PackageManager, path: String) = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, 0)
        }
    } catch (_: Exception) {
        null
    }

    // =========================================================================
    // STEP 1: V1 JAR SIGNING + 4-BYTE / 4096-BYTE ZIPALIGN
    // =========================================================================

    private fun buildAlignedV1SignedZip(
        inputApk: File,
        outputZipFile: File,
        identity: SigningIdentity
    ) {
        val manifestSb = StringBuilder()
        manifestSb.append("Manifest-Version: 1.0\r\n")
        manifestSb.append("Created-By: 1.0 (Dragon Package Signer)\r\n\r\n")

        val entrySectionDigests = LinkedHashMap<String, String>()

        // Pass 1: Compute SHA-256 digest of every non-META-INF-signature entry
        ZipFile(inputApk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val cleanName = entry.name.replace('\\', '/')
                if (isMetaInfSignatureFile(cleanName)) continue

                val sha256 = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buf = ByteArray(64 * 1024)
                    var read = input.read(buf)
                    while (read > 0) {
                        sha256.update(buf, 0, read)
                        read = input.read(buf)
                    }
                }
                val digestB64 = Base64.encodeToString(sha256.digest(), Base64.NO_WRAP)
                val entryBlock = "Name: $cleanName\r\nSHA-256-Digest: $digestB64\r\n\r\n"
                manifestSb.append(entryBlock)

                val sectionDigest = MessageDigest.getInstance("SHA-256")
                    .digest(entryBlock.toByteArray(Charsets.UTF_8))
                entrySectionDigests[cleanName] = Base64.encodeToString(sectionDigest, Base64.NO_WRAP)
            }
        }

        val manifestBytes = manifestSb.toString().toByteArray(Charsets.UTF_8)
        val manifestDigestB64 = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(manifestBytes),
            Base64.NO_WRAP
        )

        val sfSb = StringBuilder()
        sfSb.append("Signature-Version: 1.0\r\n")
        sfSb.append("Created-By: 1.0 (Dragon Package Signer)\r\n")
        sfSb.append("SHA-256-Digest-Manifest: $manifestDigestB64\r\n")
        sfSb.append("X-Android-APK-Signed: 2\r\n\r\n")
        for ((name, secDigestB64) in entrySectionDigests) {
            sfSb.append("Name: $name\r\nSHA-256-Digest: $secDigestB64\r\n\r\n")
        }
        val certSfBytes = sfSb.toString().toByteArray(Charsets.UTF_8)
        val certRsaBytes = buildPkcs7SignedDataDer(certSfBytes, identity)

        // Pass 2: Write all entries with 4-byte alignment for resources.arsc / uncompressed assets
        // and 4096-byte alignment for uncompressed .so libraries
        FileOutputStream(outputZipFile).use { rawFos ->
            val countingOut = CountingOutputStream(rawFos)
            ZipOutputStream(countingOut).use { zos ->
                ZipFile(inputApk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val srcEntry = entries.nextElement()
                        if (srcEntry.isDirectory) continue
                        val cleanName = srcEntry.name.replace('\\', '/')
                        if (isMetaInfSignatureFile(cleanName)) continue

                        val shouldStoreUncompressed = cleanName == "resources.arsc" ||
                            srcEntry.method == ZipEntry.STORED ||
                            (cleanName.startsWith("lib/") && cleanName.endsWith(".so") && srcEntry.method == ZipEntry.STORED)

                        if (shouldStoreUncompressed) {
                            val alignment = if (cleanName.startsWith("lib/") && cleanName.endsWith(".so")) 4096 else 4
                            val nameBytesLen = cleanName.toByteArray(Charsets.UTF_8).size
                            val dataOffsetWithoutExtra = countingOut.bytesWritten + 30L + nameBytesLen
                            val remainder = (dataOffsetWithoutExtra % alignment).toInt()
                            val padBytes = if (remainder != 0) ByteArray(alignment - remainder) else null

                            // Stream large entries cleanly without loading >4MB blobs into heap
                            if (srcEntry.method == ZipEntry.STORED && srcEntry.size >= 0L && srcEntry.crc >= 0L) {
                                val newEntry = ZipEntry(cleanName).apply {
                                    method = ZipEntry.STORED
                                    size = srcEntry.size
                                    compressedSize = srcEntry.size
                                    crc = srcEntry.crc
                                    time = 0L
                                    if (padBytes != null) extra = padBytes
                                }
                                zos.putNextEntry(newEntry)
                                zip.getInputStream(srcEntry).use { input ->
                                    input.copyTo(zos, 64 * 1024)
                                }
                                zos.closeEntry()
                            } else if (srcEntry.size in 0L..(4L * 1024L * 1024L)) {
                                val rawBytes = zip.getInputStream(srcEntry).use { it.readBytes() }
                                val crc = CRC32().apply { update(rawBytes) }.value
                                val newEntry = ZipEntry(cleanName).apply {
                                    method = ZipEntry.STORED
                                    size = rawBytes.size.toLong()
                                    compressedSize = rawBytes.size.toLong()
                                    this.crc = crc
                                    time = 0L
                                    if (padBytes != null) extra = padBytes
                                }
                                zos.putNextEntry(newEntry)
                                zos.write(rawBytes)
                                zos.closeEntry()
                            } else {
                                // Two-pass stream for >4MB compressed resources.arsc / assets: compute CRC+size then copy
                                var measuredSize = 0L
                                val crc32 = CRC32()
                                zip.getInputStream(srcEntry).use { input ->
                                    val buf = ByteArray(64 * 1024)
                                    var read = input.read(buf)
                                    while (read > 0) {
                                        crc32.update(buf, 0, read)
                                        measuredSize += read
                                        read = input.read(buf)
                                    }
                                }
                                val newEntry = ZipEntry(cleanName).apply {
                                    method = ZipEntry.STORED
                                    size = measuredSize
                                    compressedSize = measuredSize
                                    crc = crc32.value
                                    time = 0L
                                    if (padBytes != null) extra = padBytes
                                }
                                zos.putNextEntry(newEntry)
                                zip.getInputStream(srcEntry).use { input ->
                                    input.copyTo(zos, 64 * 1024)
                                }
                                zos.closeEntry()
                            }
                        } else {
                            val newEntry = ZipEntry(cleanName).apply {
                                method = ZipEntry.DEFLATED
                                time = 0L
                            }
                            zos.putNextEntry(newEntry)
                            zip.getInputStream(srcEntry).use { input ->
                                input.copyTo(zos, 64 * 1024)
                            }
                            zos.closeEntry()
                        }
                    }
                }

                // Write V1 signature files: META-INF/MANIFEST.MF, META-INF/CERT.SF, META-INF/CERT.RSA
                writeDeflatedEntry(zos, "META-INF/MANIFEST.MF", manifestBytes)
                writeDeflatedEntry(zos, "META-INF/CERT.SF", certSfBytes)
                writeDeflatedEntry(zos, "META-INF/CERT.RSA", certRsaBytes)
            }
        }
    }

    private fun writeDeflatedEntry(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.DEFLATED
            time = 0L
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun isMetaInfSignatureFile(entryName: String): Boolean {
        val upper = entryName.uppercase(Locale.US)
        if (!upper.startsWith("META-INF/")) return false
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") ||
            upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") ||
            upper.endsWith(".EC") ||
            upper.startsWith("META-INF/SIG-")
    }

    // =========================================================================
    // STEP 2: APK SIGNATURE SCHEME V2 BLOCK INJECTION (Block ID 0x7109871a)
    // =========================================================================

    private data class ZipEocdInfo(
        val cdOffset: Long,
        val cdSize: Long,
        val eocdOffset: Long,
        val eocdBytes: ByteArray
    )

    private fun locateZipEocd(raf: RandomAccessFile): ZipEocdInfo? {
        val fileLen = raf.length()
        if (fileLen < 22L) return null
        val maxSearch = minOf(fileLen, 65557L).toInt()
        val tail = ByteArray(maxSearch)
        raf.seek(fileLen - maxSearch)
        raf.readFully(tail)

        // Scan backwards for EOCD signature 0x06054b50 (little-endian: 50 4B 05 06)
        var eocdPosInTail = -1
        for (i in maxSearch - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() &&
                tail[i + 1] == 0x4B.toByte() &&
                tail[i + 2] == 0x05.toByte() &&
                tail[i + 3] == 0x06.toByte()
            ) {
                val commentLen = ((tail[i + 20].toInt() and 0xFF) or ((tail[i + 21].toInt() and 0xFF) shl 8))
                if (i + 22 + commentLen == maxSearch) {
                    eocdPosInTail = i
                    break
                }
            }
        }
        if (eocdPosInTail < 0) return null

        val eocdBytes = tail.copyOfRange(eocdPosInTail, maxSearch)
        val bb = ByteBuffer.wrap(eocdBytes).order(ByteOrder.LITTLE_ENDIAN)
        val cdSize = bb.getInt(12).toLong() and 0xFFFFFFFFL
        val cdOffset = bb.getInt(16).toLong() and 0xFFFFFFFFL
        val eocdOffset = (fileLen - maxSearch) + eocdPosInTail
        if (cdOffset < 0L || cdOffset + cdSize > eocdOffset) return null

        return ZipEocdInfo(
            cdOffset = cdOffset,
            cdSize = cdSize,
            eocdOffset = eocdOffset,
            eocdBytes = eocdBytes
        )
    }

    private fun injectApkSignatureSchemeV2Block(
        cleanZipFile: File,
        signedApkOut: File,
        identity: SigningIdentity
    ) {
        RandomAccessFile(cleanZipFile, "r").use { raf ->
            val eocdInfo = locateZipEocd(raf)
                ?: throw IllegalStateException("Invalid APK ZIP archive: End of Central Directory not found.")

            val cdBytes = ByteArray(eocdInfo.cdSize.toInt())
            raf.seek(eocdInfo.cdOffset)
            raf.readFully(cdBytes)

            // RSA-2048 signature is always 256 bytes, SHA-256 digest is always 32 bytes.
            // Build a template APK Signing Block first so we know its exact byte size for the EOCD CD offset.
            val placeholderDigest = ByteArray(32)
            val placeholderSignature = ByteArray(256)
            val templateBlock = assembleApkSigningBlockV2(
                topLevelDigest = placeholderDigest,
                signatureBytes = placeholderSignature,
                certificateDer = identity.certificateDer,
                publicKeyDer = identity.publicKey.encoded
            )

            val newCdOffset = eocdInfo.cdOffset + templateBlock.size
            val modifiedEocd = eocdInfo.eocdBytes.copyOf()
            ByteBuffer.wrap(modifiedEocd).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(16, newCdOffset.toInt())

            // Compute 1 MB chunk SHA-256 digests across Section 1 (ZIP entries), Section 3 (CD), Section 4 (modified EOCD)
            val chunkDigests = mutableListOf<ByteArray>()
            digestFileRangeInChunks(raf, 0L, eocdInfo.cdOffset, chunkDigests)
            digestByteArrayInChunks(cdBytes, chunkDigests)
            digestByteArrayInChunks(modifiedEocd, chunkDigests)

            val topLevelMd = MessageDigest.getInstance("SHA-256")
            val topHeader = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .put(0x5a.toByte())
                .putInt(chunkDigests.size)
                .array()
            topLevelMd.update(topHeader)
            for (cd in chunkDigests) {
                topLevelMd.update(cd)
            }
            val realTopLevelDigest = topLevelMd.digest()

            val realSignedData = buildV2SignedDataBytes(realTopLevelDigest, identity.certificateDer)
            val rsaSigner = Signature.getInstance("SHA256withRSA")
            rsaSigner.initSign(identity.privateKey)
            rsaSigner.update(realSignedData)
            val realSignatureBytes = rsaSigner.sign()

            val finalSigningBlock = assembleApkSigningBlockV2(
                topLevelDigest = realTopLevelDigest,
                signatureBytes = realSignatureBytes,
                certificateDer = identity.certificateDer,
                publicKeyDer = identity.publicKey.encoded
            )

            // If for any reason signature size differed, recompute once with finalSigningBlock.size
            val finalEocd = if (finalSigningBlock.size == templateBlock.size) {
                modifiedEocd
            } else {
                modifiedEocd.copyOf().also {
                    ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(16, (eocdInfo.cdOffset + finalSigningBlock.size).toInt())
                }
            }

            // Write final signed APK: [Section 1: Entries] + [Section 2: APK Signing Block] + [Section 3: CD] + [Section 4: EOCD]
            FileOutputStream(signedApkOut).use { out ->
                raf.seek(0L)
                var remaining = eocdInfo.cdOffset
                val buf = ByteArray(128 * 1024)
                while (remaining > 0L) {
                    val toRead = minOf(remaining, buf.size.toLong()).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
                out.write(finalSigningBlock)
                out.write(cdBytes)
                out.write(finalEocd)
            }
        }
    }

    private fun digestFileRangeInChunks(
        raf: RandomAccessFile,
        startOffset: Long,
        length: Long,
        outDigests: MutableList<ByteArray>
    ) {
        raf.seek(startOffset)
        var remaining = length
        val readBuf = ByteArray(64 * 1024)
        val md = MessageDigest.getInstance("SHA-256")
        val chunkHeader = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)

        while (remaining > 0L) {
            val chunkLen = minOf(remaining, CHUNK_SIZE_BYTES.toLong()).toInt()
            md.reset()
            chunkHeader.clear()
            chunkHeader.put(0xa5.toByte()).putInt(chunkLen)
            md.update(chunkHeader.array())

            var chunkRem = chunkLen
            while (chunkRem > 0) {
                val r = raf.read(readBuf, 0, minOf(chunkRem, readBuf.size))
                if (r <= 0) break
                md.update(readBuf, 0, r)
                chunkRem -= r
            }
            outDigests.add(md.digest())
            remaining -= chunkLen
        }
    }

    private fun digestByteArrayInChunks(data: ByteArray, outDigests: MutableList<ByteArray>) {
        var offset = 0
        val md = MessageDigest.getInstance("SHA-256")
        val chunkHeader = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        while (offset < data.size) {
            val chunkLen = minOf(data.size - offset, CHUNK_SIZE_BYTES)
            md.reset()
            chunkHeader.clear()
            chunkHeader.put(0xa5.toByte()).putInt(chunkLen)
            md.update(chunkHeader.array())
            md.update(data, offset, chunkLen)
            outDigests.add(md.digest())
            offset += chunkLen
        }
    }

    private fun buildV2SignedDataBytes(topLevelDigest: ByteArray, certificateDer: ByteArray): ByteArray {
        // digest entry: uint32(algoId) + lengthPrefixed(digest)
        val digestEntry = ByteBuffer.allocate(4 + 4 + topLevelDigest.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256)
            .putInt(topLevelDigest.size)
            .put(topLevelDigest)
            .array()
        val digestsSeq = lengthPrefixedBytes(digestEntry)
        val certsSeq = lengthPrefixedBytes(certificateDer)
        val additionalAttrsSeq = ByteArray(0)

        return concatLengthPrefixed(digestsSeq, certsSeq, additionalAttrsSeq)
    }

    private fun assembleApkSigningBlockV2(
        topLevelDigest: ByteArray,
        signatureBytes: ByteArray,
        certificateDer: ByteArray,
        publicKeyDer: ByteArray
    ): ByteArray {
        val signedData = buildV2SignedDataBytes(topLevelDigest, certificateDer)

        val signatureEntry = ByteBuffer.allocate(4 + 4 + signatureBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SIGNATURE_RSA_PKCS1_V1_5_WITH_SHA256)
            .putInt(signatureBytes.size)
            .put(signatureBytes)
            .array()
        val signaturesSeq = lengthPrefixedBytes(signatureEntry)

        val signerBytes = concatLengthPrefixed(signedData, signaturesSeq, publicKeyDer)
        val signersSeq = lengthPrefixedBytes(signerBytes)
        val v2BlockValue = lengthPrefixedBytes(signersSeq)

        // ID-value pair: uint64(4 + v2BlockValue.size) + uint32(0x7109871a) + v2BlockValue
        val pairLen = 4L + v2BlockValue.size.toLong()
        val pairBytes = ByteBuffer.allocate(8 + 4 + v2BlockValue.size).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(pairLen)
            .putInt(APK_SIGNATURE_SCHEME_V2_BLOCK_ID)
            .put(v2BlockValue)
            .array()

        // Optional 4096-byte page alignment padding pair (VERITY_PADDING_BLOCK_ID = 0x42726577)
        val baseTotalSize = 8 + pairBytes.size + 8 + 16
        val remainder = baseTotalSize % 4096
        val paddingPairBytes = if (remainder != 0) {
            val neededTotalPad = 4096 - remainder
            if (neededTotalPad >= 12) {
                val padValueLen = neededTotalPad - 12
                ByteBuffer.allocate(neededTotalPad).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(4L + padValueLen.toLong())
                    .putInt(VERITY_PADDING_BLOCK_ID)
                    .put(ByteArray(padValueLen))
                    .array()
            } else {
                val padValueLen = (4096 + neededTotalPad) - 12
                ByteBuffer.allocate(12 + padValueLen).order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(4L + padValueLen.toLong())
                    .putInt(VERITY_PADDING_BLOCK_ID)
                    .put(ByteArray(padValueLen))
                    .array()
            }
        } else {
            ByteArray(0)
        }

        val allPairsSize = pairBytes.size + paddingPairBytes.size
        val sizeOfBlockField = (allPairsSize + 8 + 16).toLong()

        return ByteBuffer.allocate(8 + allPairsSize + 8 + 16).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(sizeOfBlockField)
            .put(pairBytes)
            .put(paddingPairBytes)
            .putLong(sizeOfBlockField)
            .putLong(APK_SIG_BLOCK_MAGIC_LO)
            .putLong(APK_SIG_BLOCK_MAGIC_HI)
            .array()
    }

    private fun lengthPrefixedBytes(payload: ByteArray): ByteArray {
        return ByteBuffer.allocate(4 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    private fun concatLengthPrefixed(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { 4 + it.size }
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        for (p in parts) {
            bb.putInt(p.size)
            bb.put(p)
        }
        return bb.array()
    }

    // =========================================================================
    // PERSISTENT ON-DEVICE RSA-2048 KEY & X.509v3 / PKCS#7 ASN.1 DER ENCODER
    // =========================================================================

    @Synchronized
    fun getOrCreateSigningIdentity(context: Context): SigningIdentity {
        cachedIdentity?.let { return it }

        val signerDir = File(context.filesDir ?: context.cacheDir, "ddm_apk_signer").apply { mkdirs() }
        val keyFile = File(signerDir, "dragon_signer_rsa2048.pk8")
        val certFile = File(signerDir, "dragon_signer_cert.der")

        if (keyFile.exists() && certFile.exists() && keyFile.length() > 64L && certFile.length() > 64L) {
            runCatching {
                val privBytes = keyFile.readBytes()
                val certBytes = certFile.readBytes()
                val kf = KeyFactory.getInstance("RSA")
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val cf = CertificateFactory.getInstance("X.509")
                val x509 = cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
                val identity = SigningIdentity(
                    privateKey = privKey,
                    publicKey = x509.publicKey,
                    certificateDer = certBytes,
                    x509Certificate = x509
                )
                cachedIdentity = identity
                return identity
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val certDer = buildSelfSignedX509CertificateDer(keyPair)
        val cf = CertificateFactory.getInstance("X.509")
        val x509 = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

        runCatching {
            keyFile.writeBytes(keyPair.private.encoded)
            certFile.writeBytes(certDer)
        }

        val identity = SigningIdentity(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            certificateDer = certDer,
            x509Certificate = x509
        )
        cachedIdentity = identity
        return identity
    }

    // OID 1.2.840.113549.1.1.11 (sha256WithRSAEncryption)
    private val OID_SHA256_WITH_RSA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B
    )
    // OID 1.2.840.113549.1.1.1 (rsaEncryption)
    private val OID_RSA_ENCRYPTION = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01
    )
    // OID 2.16.840.1.101.3.4.2.1 (id-sha256)
    private val OID_SHA256 = byteArrayOf(
        0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01
    )
    // OID 1.2.840.113549.1.7.1 (pkcs7-data)
    private val OID_PKCS7_DATA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x01
    )
    // OID 1.2.840.113549.1.7.2 (pkcs7-signedData)
    private val OID_PKCS7_SIGNED_DATA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x02
    )

    private fun buildSelfSignedX509CertificateDer(keyPair: KeyPair): ByteArray {
        val versionExplicit = derTag(0xA0, derInteger(2))
        val serialNumber = derInteger(0x445241474F4E3031L)
        val sigAlgId = derSequence(derOid(OID_SHA256_WITH_RSA), derNull())
        val issuerAndSubjectName = buildX500DistinguishedName("Dragon Package Signer", "DragonTool", "US")
        val validity = derSequence(
            derUtcTime("240101000000Z"),
            derGeneralizedTime("20541231235959Z")
        )
        val subjectPublicKeyInfo = keyPair.public.encoded

        val tbsCertificate = derSequence(
            versionExplicit,
            serialNumber,
            sigAlgId,
            issuerAndSubjectName,
            validity,
            issuerAndSubjectName,
            subjectPublicKeyInfo
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCertificate)
        val signatureBytes = signer.sign()

        return derSequence(
            tbsCertificate,
            sigAlgId,
            derBitString(signatureBytes)
        )
    }

    /**
     * Builds a standard PKCS#7 / CMS `SignedData` DER block (`META-INF/CERT.RSA`) containing
     * `identity.certificateDer` and a detached `SHA256withRSA` signature over `certSfBytes`.
     */
    private fun buildPkcs7SignedDataDer(certSfBytes: ByteArray, identity: SigningIdentity): ByteArray {
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(identity.privateKey)
        signer.update(certSfBytes)
        val signatureBytes = signer.sign()

        val issuerName = buildX500DistinguishedName("Dragon Package Signer", "DragonTool", "US")
        val serialNumber = derInteger(0x445241474F4E3031L)
        val issuerAndSerial = derSequence(issuerName, serialNumber)

        val sha256AlgId = derSequence(derOid(OID_SHA256), derNull())
        val rsaAlgId = derSequence(derOid(OID_RSA_ENCRYPTION), derNull())

        val signerInfo = derSequence(
            derInteger(1),
            issuerAndSerial,
            sha256AlgId,
            rsaAlgId,
            derOctetString(signatureBytes)
        )

        val signedData = derSequence(
            derInteger(1),
            derSet(sha256AlgId),
            derSequence(derOid(OID_PKCS7_DATA)),
            derTag(0xA0, identity.certificateDer),
            derSet(signerInfo)
        )

        return derSequence(
            derOid(OID_PKCS7_SIGNED_DATA),
            derTag(0xA0, signedData)
        )
    }

    private fun buildX500DistinguishedName(cn: String, org: String, country: String): ByteArray {
        // OID 2.5.4.6 (countryName) = 55 04 06
        // OID 2.5.4.10 (organizationName) = 55 04 0A
        // OID 2.5.4.3 (commonName) = 55 04 03
        val cAttr = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x06)), derPrintableString(country)))
        val oAttr = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x0A)), derUtf8String(org)))
        val cnAttr = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x03)), derUtf8String(cn)))
        return derSequence(cAttr, oAttr, cnAttr)
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 8)
        out.write(tag)
        val len = content.size
        when {
            len < 128 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            len <= 0xFFFF -> {
                out.write(0x82)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            len <= 0xFFFFFF -> {
                out.write(0x83)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x84)
                out.write((len ushr 24) and 0xFF)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        for (el in elements) bos.write(el)
        return derTag(0x30, bos.toByteArray())
    }

    private fun derSet(vararg elements: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        for (el in elements) bos.write(el)
        return derTag(0x31, bos.toByteArray())
    }

    private fun derInteger(value: Long): ByteArray {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
        var start = 0
        while (start < 7 && bytes[start] == 0.toByte() && (bytes[start + 1].toInt() and 0x80) == 0) {
            start++
        }
        return derTag(0x02, bytes.copyOfRange(start, 8))
    }

    private fun derOid(oidBytes: ByteArray): ByteArray = derTag(0x06, oidBytes)
    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun derOctetString(bytes: ByteArray): ByteArray = derTag(0x04, bytes)
    private fun derUtf8String(text: String): ByteArray = derTag(0x0C, text.toByteArray(Charsets.UTF_8))
    private fun derPrintableString(text: String): ByteArray = derTag(0x13, text.toByteArray(Charsets.US_ASCII))
    private fun derUtcTime(text: String): ByteArray = derTag(0x17, text.toByteArray(Charsets.US_ASCII))
    private fun derGeneralizedTime(text: String): ByteArray = derTag(0x18, text.toByteArray(Charsets.US_ASCII))

    private fun derBitString(bytes: ByteArray): ByteArray {
        val content = ByteArray(bytes.size + 1)
        content[0] = 0x00
        System.arraycopy(bytes, 0, content, 1, bytes.size)
        return derTag(0x03, content)
    }

    private class CountingOutputStream(private val delegate: java.io.OutputStream) : java.io.OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1L
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            bytesWritten += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
