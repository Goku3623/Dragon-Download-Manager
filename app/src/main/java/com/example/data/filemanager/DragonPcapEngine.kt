package com.example.data.filemanager

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

enum class PcapFileFormat(val badgeLabel: String) {
    PCAP_LE("PCAP (LE)"),
    PCAP_BE("PCAP (BE)"),
    PCAP_NS_LE("PCAP-NS (LE)"),
    PCAP_NS_BE("PCAP-NS (BE)"),
    PCAPNG("PCAPNG"),
    CAP_LEGACY("CAP")
}

data class PcapInterfaceInfo(
    val id: Int,
    val linkType: Int,
    val snapLen: Int,
    val name: String = "eth0",
    val description: String = "",
    val tsResolByte: Byte = 6 // 10^-6 (microseconds)
)

data class PcapLayerField(
    val name: String,
    val value: String
)

data class PcapProtocolLayer(
    val title: String,
    val summary: String,
    val fields: List<PcapLayerField>
)

data class PcapDissection(
    val protocol: String,
    val srcAddress: String,
    val dstAddress: String,
    val srcPort: Int? = null,
    val dstPort: Int? = null,
    val info: String,
    val layers: List<PcapProtocolLayer>,
    val ipHeaderOffset: Int = -1,
    val ipVersion: Int = 0,
    val ipProtocol: Int = -1,
    val transportHeaderOffset: Int = -1,
    val payloadOffset: Int = -1,
    val payloadLength: Int = 0,
    val isEncrypted: Boolean = false,
    val encryptionHint: String = "",
    val wifiSsid: String? = null,
    val isEapol: Boolean = false,
    val streamKey: String? = null
)

data class PcapPacketRecord(
    val index: Int,
    val interfaceId: Int = 0,
    val linkType: Int = DragonPcapEngine.DLT_EN10MB,
    val timestampSeconds: Long,
    val timestampMicros: Long,
    val originalLength: Int,
    val rawData: ByteArray,
    val comment: String = "",
    val decryptedBytes: ByteArray? = null,
    val decryptedProtocol: String? = null,
    val decryptedSummary: String? = null,
    val decryptedTextPreview: String? = null,
    val decryptionMethod: String? = null
) {
    fun dissect(): PcapDissection = DragonPcapEngine.dissectPacket(this)

    val timestampMillis: Long
        get() = timestampSeconds * 1000L + (timestampMicros / 1000L)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcapPacketRecord) return false
        return index == other.index &&
            interfaceId == other.interfaceId &&
            linkType == other.linkType &&
            timestampSeconds == other.timestampSeconds &&
            timestampMicros == other.timestampMicros &&
            originalLength == other.originalLength &&
            rawData.contentEquals(other.rawData) &&
            comment == other.comment &&
            decryptedBytes.contentEquals(other.decryptedBytes) &&
            decryptedProtocol == other.decryptedProtocol &&
            decryptedSummary == other.decryptedSummary &&
            decryptedTextPreview == other.decryptedTextPreview &&
            decryptionMethod == other.decryptionMethod
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + interfaceId
        result = 31 * result + linkType
        result = 31 * result + timestampSeconds.hashCode()
        result = 31 * result + timestampMicros.hashCode()
        result = 31 * result + originalLength
        result = 31 * result + rawData.contentHashCode()
        result = 31 * result + comment.hashCode()
        result = 31 * result + (decryptedBytes?.contentHashCode() ?: 0)
        result = 31 * result + (decryptedProtocol?.hashCode() ?: 0)
        return result
    }
}

data class PcapCaptureDocument(
    val format: PcapFileFormat,
    val versionMajor: Int = 2,
    val versionMinor: Int = 4,
    val snapLen: Int = 65535,
    val defaultLinkType: Int = DragonPcapEngine.DLT_EN10MB,
    val interfaces: List<PcapInterfaceInfo>,
    val packets: List<PcapPacketRecord>,
    val sectionComment: String = "",
    val embeddedKeyLogText: String = "",
    val fileSizeBytes: Long = 0L,
    val warningMessage: String? = null
)

enum class CustomPayloadCipherMode(val label: String) {
    AES_GCM("AES-GCM"),
    AES_CBC("AES-CBC"),
    AES_CTR("AES-CTR"),
    XOR_KEY("XOR Key")
}

data class PcapDecryptionOutcome(
    val updatedDocument: PcapCaptureDocument,
    val decryptedCount: Int,
    val summaryMessage: String
)

/**
 * Native packet capture parser, multi-layer protocol dissector, authorized known-key decryptor
 * (TLS SSLKEYLOGFILE, IEEE 802.11 WPA2-PSK CCMP & WEP with user-supplied keys, and custom AES/XOR payloads),
 * and lossless packet editor/writer for `.cap`, `.pcap`, and `.pcapng` files.
 */
object DragonPcapEngine {

    val PCAP_EXTENSIONS = setOf("cap", "pcap", "pcapng")

    const val DLT_NULL = 0
    const val DLT_EN10MB = 1
    const val DLT_RAW = 101
    const val DLT_IEEE802_11 = 105
    const val DLT_LOOP = 108
    const val DLT_LINUX_SLL = 113
    const val DLT_IEEE802_11_RADIO = 127
    const val DLT_LINUX_SLL2 = 276

    private const val MAGIC_PCAP_BE = 0xA1B2C3D4.toInt()
    private const val MAGIC_PCAP_LE = 0xD4C3B2A1.toInt()
    private const val MAGIC_PCAP_NS_BE = 0xA1B23C4D.toInt()
    private const val MAGIC_PCAP_NS_LE = 0x4D3CB2A1.toInt()
    private const val BLOCK_SHB = 0x0A0D0D0A
    private const val BLOCK_IDB = 0x00000001
    private const val BLOCK_OPB = 0x00000002
    private const val BLOCK_SPB = 0x00000003
    private const val BLOCK_EPB = 0x00000006
    private const val BLOCK_DSB = 0x0000000A

    private const val MAX_CAPTURE_BYTES = 32 * 1024 * 1024 // 32 MB safety cap
    private const val MAX_PACKET_COUNT = 20_000

    fun isCaptureExtension(ext: String): Boolean = ext.lowercase(Locale.US) in PCAP_EXTENSIONS

    fun linkTypeName(dlt: Int): String = when (dlt) {
        DLT_NULL -> "BSD Loopback"
        DLT_EN10MB -> "Ethernet (EN10MB)"
        DLT_RAW -> "Raw IP"
        DLT_IEEE802_11 -> "IEEE 802.11 Wi-Fi"
        DLT_LOOP -> "OpenBSD Loopback"
        DLT_LINUX_SLL -> "Linux SLL"
        DLT_IEEE802_11_RADIO -> "802.11 Radiotap"
        DLT_LINUX_SLL2 -> "Linux SLL2"
        else -> "DLT-$dlt"
    }

    fun readCaptureFile(context: Context, file: File): PcapCaptureDocument {
        val raw = AndroidDataObbBridge.openInputStreamForFile(context, file)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < MAX_CAPTURE_BYTES) {
                val n = input.read(buf, 0, minOf(buf.size, MAX_CAPTURE_BYTES - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            out.toByteArray()
        } ?: throw IllegalStateException("Unable to read ${file.name}")

        return parseBytes(raw, file.length().coerceAtLeast(raw.size.toLong()))
    }

    fun parseBytes(bytes: ByteArray, fileSizeBytes: Long = bytes.size.toLong()): PcapCaptureDocument {
        if (bytes.size < 4) {
            throw IllegalArgumentException("File is too small (${bytes.size} bytes) to be a valid capture file.")
        }
        val beMagic = readIntBE(bytes, 0)
        return when {
            beMagic == BLOCK_SHB -> parsePcapNg(bytes, fileSizeBytes)
            beMagic == MAGIC_PCAP_BE -> parseClassicPcap(bytes, ByteOrder.BIG_ENDIAN, isNano = false, fileSizeBytes)
            beMagic == MAGIC_PCAP_LE -> parseClassicPcap(bytes, ByteOrder.LITTLE_ENDIAN, isNano = false, fileSizeBytes)
            beMagic == MAGIC_PCAP_NS_BE -> parseClassicPcap(bytes, ByteOrder.BIG_ENDIAN, isNano = true, fileSizeBytes)
            beMagic == MAGIC_PCAP_NS_LE -> parseClassicPcap(bytes, ByteOrder.LITTLE_ENDIAN, isNano = true, fileSizeBytes)
            // Microsoft Network Monitor (.cap) magic "GMBU" or "RTSS"
            bytes.size >= 16 && (String(bytes, 0, 4, Charsets.US_ASCII) == "GMBU" || String(bytes, 0, 4, Charsets.US_ASCII) == "RTSS") -> {
                parseNetMonCap(bytes, fileSizeBytes)
            }
            else -> {
                // Fallback: wrap unknown raw .cap binary as a single editable frame so user can still inspect/edit/decrypt
                PcapCaptureDocument(
                    format = PcapFileFormat.CAP_LEGACY,
                    defaultLinkType = DLT_EN10MB,
                    interfaces = listOf(PcapInterfaceInfo(0, DLT_EN10MB, bytes.size.coerceAtLeast(65535), "raw0")),
                    packets = listOf(
                        PcapPacketRecord(
                            index = 1,
                            interfaceId = 0,
                            linkType = DLT_EN10MB,
                            timestampSeconds = System.currentTimeMillis() / 1000L,
                            timestampMicros = 0L,
                            originalLength = bytes.size,
                            rawData = bytes,
                            comment = "Unrecognized header — opened in raw frame mode"
                        )
                    ),
                    fileSizeBytes = fileSizeBytes,
                    warningMessage = "Non-standard capture header (0x${beMagic.toUInt().toString(16)}); opened as raw frame for inspection & hex editing."
                )
            }
        }
    }

    private fun parseClassicPcap(
        bytes: ByteArray,
        order: ByteOrder,
        isNano: Boolean,
        fileSizeBytes: Long
    ): PcapCaptureDocument {
        if (bytes.size < 24) {
            throw IllegalArgumentException("Truncated PCAP global header (${bytes.size} bytes).")
        }
        val bb = ByteBuffer.wrap(bytes).order(order)
        bb.int // magic
        val vMajor = bb.short.toInt() and 0xFFFF
        val vMinor = bb.short.toInt() and 0xFFFF
        bb.int // thiszone
        bb.int // sigfigs
        val snapLen = bb.int
        val networkRaw = bb.int
        val linkType = networkRaw and 0x0FFFFFFF

        val packets = ArrayList<PcapPacketRecord>()
        var offset = 24
        var warning: String? = null
        while (offset + 16 <= bytes.size && packets.size < MAX_PACKET_COUNT) {
            val tsSec = readUInt(bytes, offset, order)
            val tsSub = readUInt(bytes, offset + 4, order)
            val inclLen = readInt(bytes, offset + 8, order)
            val origLen = readInt(bytes, offset + 12, order)
            offset += 16
            if (inclLen < 0 || inclLen > 16 * 1024 * 1024 || offset + inclLen > bytes.size) {
                val available = (bytes.size - offset).coerceAtLeast(0)
                if (available > 0 && inclLen > 0) {
                    val slice = bytes.copyOfRange(offset, bytes.size)
                    packets.add(
                        PcapPacketRecord(
                            index = packets.size + 1,
                            interfaceId = 0,
                            linkType = linkType,
                            timestampSeconds = tsSec,
                            timestampMicros = if (isNano) tsSub / 1000L else tsSub,
                            originalLength = origLen.coerceAtLeast(slice.size),
                            rawData = slice
                        )
                    )
                }
                warning = "Capture ended early at packet #${packets.size + 1} (truncated record)."
                break
            }
            val pktData = bytes.copyOfRange(offset, offset + inclLen)
            offset += inclLen
            packets.add(
                PcapPacketRecord(
                    index = packets.size + 1,
                    interfaceId = 0,
                    linkType = linkType,
                    timestampSeconds = tsSec,
                    timestampMicros = if (isNano) tsSub / 1000L else tsSub,
                    originalLength = origLen.coerceAtLeast(inclLen),
                    rawData = pktData
                )
            )
        }
        if (packets.size >= MAX_PACKET_COUNT && offset < bytes.size) {
            warning = "Loaded first $MAX_PACKET_COUNT packets for responsive editing."
        }
        val fmt = when {
            isNano && order == ByteOrder.LITTLE_ENDIAN -> PcapFileFormat.PCAP_NS_LE
            isNano -> PcapFileFormat.PCAP_NS_BE
            order == ByteOrder.LITTLE_ENDIAN -> PcapFileFormat.PCAP_LE
            else -> PcapFileFormat.PCAP_BE
        }
        return PcapCaptureDocument(
            format = fmt,
            versionMajor = vMajor,
            versionMinor = vMinor,
            snapLen = snapLen.coerceAtLeast(65535),
            defaultLinkType = linkType,
            interfaces = listOf(PcapInterfaceInfo(0, linkType, snapLen.coerceAtLeast(65535), "capture0")),
            packets = packets,
            fileSizeBytes = fileSizeBytes,
            warningMessage = warning
        )
    }

    private fun parsePcapNg(bytes: ByteArray, fileSizeBytes: Long): PcapCaptureDocument {
        var offset = 0
        var order = ByteOrder.LITTLE_ENDIAN
        val interfaces = ArrayList<PcapInterfaceInfo>()
        val packets = ArrayList<PcapPacketRecord>()
        val embeddedSecrets = StringBuilder()
        var sectionComment = ""
        var vMajor = 1
        var vMinor = 0
        var warning: String? = null

        while (offset + 12 <= bytes.size && packets.size < MAX_PACKET_COUNT) {
            val rawTypeLe = readIntLE(bytes, offset)
            val blockType = if (rawTypeLe == BLOCK_SHB) BLOCK_SHB else readInt(bytes, offset, order)
            if (blockType == BLOCK_SHB && offset + 12 <= bytes.size) {
                val bomLe = readIntLE(bytes, offset + 8)
                order = if (bomLe == 0x1A2B3C4D) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
            }
            val blockLen = readInt(bytes, offset + 4, order)
            if (blockLen < 12 || offset + blockLen > bytes.size) {
                warning = "PCAPNG ended with incomplete block at offset $offset."
                break
            }
            val bodyStart = offset + 8
            val bodyEnd = offset + blockLen - 4

            when (blockType) {
                BLOCK_SHB -> {
                    if (bodyStart + 16 <= bodyEnd) {
                        vMajor = readUShort(bytes, bodyStart + 4, order)
                        vMinor = readUShort(bytes, bodyStart + 6, order)
                        val opts = parsePcapNgOptions(bytes, bodyStart + 16, bodyEnd, order)
                        opts[1]?.let { c ->
                            if (sectionComment.isBlank()) sectionComment = String(c, Charsets.UTF_8).trim()
                        }
                    }
                }
                BLOCK_IDB -> {
                    if (bodyStart + 8 <= bodyEnd) {
                        val linkType = readUShort(bytes, bodyStart, order)
                        val snapLen = readInt(bytes, bodyStart + 4, order)
                        val opts = parsePcapNgOptions(bytes, bodyStart + 8, bodyEnd, order)
                        val ifName = opts[2]?.let { String(it, Charsets.UTF_8).trim() }.orEmpty().ifBlank { "if${interfaces.size}" }
                        val ifDesc = opts[3]?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
                        val tsResol = opts[9]?.firstOrNull() ?: 6.toByte()
                        interfaces.add(
                            PcapInterfaceInfo(
                                id = interfaces.size,
                                linkType = linkType,
                                snapLen = snapLen.coerceAtLeast(65535),
                                name = ifName,
                                description = ifDesc,
                                tsResolByte = tsResol
                            )
                        )
                    }
                }
                BLOCK_EPB -> {
                    if (bodyStart + 20 <= bodyEnd) {
                        val ifId = readInt(bytes, bodyStart, order)
                        val tsHigh = readUInt(bytes, bodyStart + 4, order)
                        val tsLow = readUInt(bytes, bodyStart + 8, order)
                        val capLen = readInt(bytes, bodyStart + 12, order)
                        val origLen = readInt(bytes, bodyStart + 16, order)
                        val dataStart = bodyStart + 20
                        if (capLen >= 0 && dataStart + capLen <= bodyEnd) {
                            val pktBytes = bytes.copyOfRange(dataStart, dataStart + capLen)
                            val paddedDataLen = pad4(capLen)
                            val opts = parsePcapNgOptions(bytes, dataStart + paddedDataLen, bodyEnd, order)
                            val comment = opts[1]?.let { String(it, Charsets.UTF_8).trim() }.orEmpty()
                            val iface = interfaces.getOrNull(ifId)
                            val (sec, usec) = decodePcapNgTimestamp(tsHigh, tsLow, iface?.tsResolByte ?: 6)
                            packets.add(
                                PcapPacketRecord(
                                    index = packets.size + 1,
                                    interfaceId = ifId,
                                    linkType = iface?.linkType ?: DLT_EN10MB,
                                    timestampSeconds = sec,
                                    timestampMicros = usec,
                                    originalLength = origLen.coerceAtLeast(capLen),
                                    rawData = pktBytes,
                                    comment = comment
                                )
                            )
                        }
                    }
                }
                BLOCK_SPB -> {
                    if (bodyStart + 4 <= bodyEnd) {
                        val origLen = readInt(bytes, bodyStart, order)
                        val pktBytes = bytes.copyOfRange(bodyStart + 4, bodyEnd)
                        val iface = interfaces.firstOrNull()
                        packets.add(
                            PcapPacketRecord(
                                index = packets.size + 1,
                                interfaceId = 0,
                                linkType = iface?.linkType ?: DLT_EN10MB,
                                timestampSeconds = 0L,
                                timestampMicros = 0L,
                                originalLength = origLen.coerceAtLeast(pktBytes.size),
                                rawData = pktBytes
                            )
                        )
                    }
                }
                BLOCK_OPB -> {
                    if (bodyStart + 20 <= bodyEnd) {
                        val ifId = readUShort(bytes, bodyStart, order)
                        val tsHigh = readUInt(bytes, bodyStart + 4, order)
                        val tsLow = readUInt(bytes, bodyStart + 8, order)
                        val capLen = readInt(bytes, bodyStart + 12, order)
                        val origLen = readInt(bytes, bodyStart + 16, order)
                        val dataStart = bodyStart + 20
                        if (capLen >= 0 && dataStart + capLen <= bodyEnd) {
                            val iface = interfaces.getOrNull(ifId)
                            val (sec, usec) = decodePcapNgTimestamp(tsHigh, tsLow, iface?.tsResolByte ?: 6)
                            packets.add(
                                PcapPacketRecord(
                                    index = packets.size + 1,
                                    interfaceId = ifId,
                                    linkType = iface?.linkType ?: DLT_EN10MB,
                                    timestampSeconds = sec,
                                    timestampMicros = usec,
                                    originalLength = origLen.coerceAtLeast(capLen),
                                    rawData = bytes.copyOfRange(dataStart, dataStart + capLen)
                                )
                            )
                        }
                    }
                }
                BLOCK_DSB -> {
                    if (bodyStart + 8 <= bodyEnd) {
                        val secretsType = readInt(bytes, bodyStart, order)
                        val secretsLen = readInt(bytes, bodyStart + 4, order)
                        val secStart = bodyStart + 8
                        if (secretsLen > 0 && secStart + secretsLen <= bodyEnd) {
                            // TLSK (0x544C534B) or WireGuard (0x57474B4C)
                            if (secretsType == 0x544C534B || secretsType == 0x4B534C54 || secretsType == 0x57474B4C) {
                                val text = String(bytes, secStart, secretsLen, Charsets.UTF_8).trim()
                                if (text.isNotBlank()) {
                                    if (embeddedSecrets.isNotEmpty()) embeddedSecrets.append('\n')
                                    embeddedSecrets.append(text)
                                }
                            }
                        }
                    }
                }
            }
            offset += blockLen
        }

        val resolvedIfaces = interfaces.ifEmpty {
            listOf(PcapInterfaceInfo(0, DLT_EN10MB, 65535, "eth0"))
        }
        return PcapCaptureDocument(
            format = PcapFileFormat.PCAPNG,
            versionMajor = vMajor,
            versionMinor = vMinor,
            snapLen = resolvedIfaces.first().snapLen,
            defaultLinkType = resolvedIfaces.first().linkType,
            interfaces = resolvedIfaces,
            packets = packets,
            sectionComment = sectionComment,
            embeddedKeyLogText = embeddedSecrets.toString(),
            fileSizeBytes = fileSizeBytes,
            warningMessage = warning
        )
    }

    /**
     * Parses Microsoft Network Monitor (.cap) v1/v2 capture files (`GMBU` / `RTSS`).
     */
    private fun parseNetMonCap(bytes: ByteArray, fileSizeBytes: Long): PcapCaptureDocument {
        val packets = ArrayList<PcapPacketRecord>()
        if (bytes.size >= 40) {
            val networkType = readUShortLE(bytes, 6)
            val linkType = when (networkType) {
                1 -> DLT_EN10MB
                6 -> DLT_IEEE802_11
                else -> DLT_EN10MB
            }
            val frameTableOffset = readIntLE(bytes, 24)
            val frameTableLength = readIntLE(bytes, 28)
            if (frameTableOffset > 0 && frameTableLength > 0 && frameTableOffset + frameTableLength <= bytes.size) {
                val frameCount = (frameTableLength / 4).coerceAtMost(MAX_PACKET_COUNT)
                for (i in 0 until frameCount) {
                    val recOff = readIntLE(bytes, frameTableOffset + i * 4)
                    if (recOff > 0 && recOff + 16 <= bytes.size) {
                        val tsMicros = readLongLE(bytes, recOff)
                        val origLen = readUShortLE(bytes, recOff + 8)
                        val inclLen = readUShortLE(bytes, recOff + 10)
                        val dataStart = recOff + 16
                        if (inclLen > 0 && dataStart + inclLen <= bytes.size) {
                            packets.add(
                                PcapPacketRecord(
                                    index = packets.size + 1,
                                    interfaceId = 0,
                                    linkType = linkType,
                                    timestampSeconds = tsMicros / 1_000_000L,
                                    timestampMicros = (tsMicros % 1_000_000L).coerceAtLeast(0L),
                                    originalLength = origLen.coerceAtLeast(inclLen),
                                    rawData = bytes.copyOfRange(dataStart, dataStart + inclLen)
                                )
                            )
                        }
                    }
                }
            }
        }
        return PcapCaptureDocument(
            format = PcapFileFormat.CAP_LEGACY,
            defaultLinkType = packets.firstOrNull()?.linkType ?: DLT_EN10MB,
            interfaces = listOf(PcapInterfaceInfo(0, packets.firstOrNull()?.linkType ?: DLT_EN10MB, 65535, "netmon0")),
            packets = packets,
            fileSizeBytes = fileSizeBytes,
            warningMessage = if (packets.isEmpty()) "Legacy NetMon CAP header detected; no standard frame table entries found." else null
        )
    }

    // -----------------------------------------------------------------------------------------
    // WRITERS: Save edited or decrypted captures to .pcap / .cap / .pcapng
    // -----------------------------------------------------------------------------------------

    fun writeCaptureFile(
        context: Context,
        targetFile: File,
        document: PcapCaptureDocument,
        saveAsPcapNg: Boolean = targetFile.extension.equals("pcapng", ignoreCase = true) || document.format == PcapFileFormat.PCAPNG,
        createBackup: Boolean = false
    ): Boolean {
        if (createBackup && AndroidDataObbBridge.existsUniversal(context, targetFile)) {
            val backupFile = File(targetFile.parentFile, "${targetFile.name}.bak")
            runCatching { AndroidDataObbBridge.copyFileOrDirectoryUniversal(context, targetFile, backupFile) }
        }
        val encoded = if (saveAsPcapNg) encodePcapNg(document) else encodeClassicPcap(document)
        val out = AndroidDataObbBridge.openOutputStreamForFile(context, targetFile, "application/vnd.tcpdump.pcap")
            ?: return false
        out.use {
            it.write(encoded)
            it.flush()
        }
        return true
    }

    fun encodeClassicPcap(document: PcapCaptureDocument): ByteArray {
        val out = ByteArrayOutputStream()
        val linkType = document.packets.firstOrNull()?.linkType ?: document.defaultLinkType
        val hdr = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(MAGIC_PCAP_BE) // When written in LE, bytes are D4 C3 B2 A1 (standard little-endian PCAP)
        hdr.putShort(2)
        hdr.putShort(4)
        hdr.putInt(0)
        hdr.putInt(0)
        hdr.putInt(document.snapLen.coerceAtLeast(65535))
        hdr.putInt(linkType)
        out.write(hdr.array())

        val recHdr = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        for (pkt in document.packets) {
            recHdr.clear()
            recHdr.putInt(pkt.timestampSeconds.toInt())
            recHdr.putInt(pkt.timestampMicros.coerceIn(0L, 999_999L).toInt())
            recHdr.putInt(pkt.rawData.size)
            recHdr.putInt(pkt.originalLength.coerceAtLeast(pkt.rawData.size))
            out.write(recHdr.array())
            out.write(pkt.rawData)
        }
        return out.toByteArray()
    }

    fun encodePcapNg(document: PcapCaptureDocument): ByteArray {
        val out = ByteArrayOutputStream()
        val order = ByteOrder.LITTLE_ENDIAN

        // 1. Section Header Block (SHB)
        val shbBody = ByteArrayOutputStream()
        shbBody.write(intBytes(0x1A2B3C4D, order))
        shbBody.write(shortBytes(1, order))
        shbBody.write(shortBytes(0, order))
        shbBody.write(longBytes(-1L, order)) // unspecified section length
        val shbOpts = mutableListOf<Pair<Int, ByteArray>>()
        if (document.sectionComment.isNotBlank()) {
            shbOpts.add(1 to document.sectionComment.toByteArray(Charsets.UTF_8))
        }
        shbOpts.add(4 to "Dragon Packet Studio".toByteArray(Charsets.UTF_8))
        shbBody.write(encodePcapNgOptions(shbOpts, order))
        writePcapNgBlock(out, BLOCK_SHB, shbBody.toByteArray(), order)

        // 2. Interface Description Blocks (IDB)
        val ifaces = document.interfaces.ifEmpty {
            listOf(PcapInterfaceInfo(0, document.defaultLinkType, document.snapLen, "eth0"))
        }
        for (iface in ifaces) {
            val idbBody = ByteArrayOutputStream()
            idbBody.write(shortBytes(iface.linkType, order))
            idbBody.write(shortBytes(0, order))
            idbBody.write(intBytes(iface.snapLen.coerceAtLeast(65535), order))
            val idbOpts = mutableListOf<Pair<Int, ByteArray>>()
            if (iface.name.isNotBlank()) idbOpts.add(2 to iface.name.toByteArray(Charsets.UTF_8))
            if (iface.description.isNotBlank()) idbOpts.add(3 to iface.description.toByteArray(Charsets.UTF_8))
            idbOpts.add(9 to byteArrayOf(6)) // microsecond resolution
            idbBody.write(encodePcapNgOptions(idbOpts, order))
            writePcapNgBlock(out, BLOCK_IDB, idbBody.toByteArray(), order)
        }

        // 3. Optional Decryption Secrets Block (DSB) if keylog text is present
        if (document.embeddedKeyLogText.isNotBlank()) {
            val secretBytes = document.embeddedKeyLogText.toByteArray(Charsets.UTF_8)
            val dsbBody = ByteArrayOutputStream()
            dsbBody.write(intBytes(0x544C534B, order)) // TLSK
            dsbBody.write(intBytes(secretBytes.size, order))
            dsbBody.write(secretBytes)
            val pad = pad4(secretBytes.size) - secretBytes.size
            if (pad > 0) dsbBody.write(ByteArray(pad))
            writePcapNgBlock(out, BLOCK_DSB, dsbBody.toByteArray(), order)
        }

        // 4. Enhanced Packet Blocks (EPB)
        for (pkt in document.packets) {
            val epbBody = ByteArrayOutputStream()
            val ifId = pkt.interfaceId.coerceIn(0, (ifaces.size - 1).coerceAtLeast(0))
            val totalMicros = pkt.timestampSeconds * 1_000_000L + pkt.timestampMicros.coerceIn(0L, 999_999L)
            val tsHigh = (totalMicros ushr 32).toInt()
            val tsLow = (totalMicros and 0xFFFFFFFFL).toInt()
            epbBody.write(intBytes(ifId, order))
            epbBody.write(intBytes(tsHigh, order))
            epbBody.write(intBytes(tsLow, order))
            epbBody.write(intBytes(pkt.rawData.size, order))
            epbBody.write(intBytes(pkt.originalLength.coerceAtLeast(pkt.rawData.size), order))
            epbBody.write(pkt.rawData)
            val pad = pad4(pkt.rawData.size) - pkt.rawData.size
            if (pad > 0) epbBody.write(ByteArray(pad))
            if (pkt.comment.isNotBlank()) {
                epbBody.write(encodePcapNgOptions(listOf(1 to pkt.comment.toByteArray(Charsets.UTF_8)), order))
            }
            writePcapNgBlock(out, BLOCK_EPB, epbBody.toByteArray(), order)
        }

        return out.toByteArray()
    }

    // -----------------------------------------------------------------------------------------
    // MULTI-LAYER PROTOCOL DISSECTOR
    // -----------------------------------------------------------------------------------------

    fun dissectPacket(packet: PcapPacketRecord): PcapDissection {
        val data = packet.rawData
        val layers = mutableListOf<PcapProtocolLayer>()
        layers.add(
            PcapProtocolLayer(
                title = "Frame #${packet.index}",
                summary = "${data.size} bytes captured (${packet.originalLength} bytes on wire) • ${linkTypeName(packet.linkType)}",
                fields = listOf(
                    PcapLayerField("Frame Number", "#${packet.index}"),
                    PcapLayerField("Captured Length", "${data.size} bytes"),
                    PcapLayerField("Wire Length", "${packet.originalLength} bytes"),
                    PcapLayerField("Link Layer", "${linkTypeName(packet.linkType)} (${packet.linkType})"),
                    PcapLayerField("Timestamp", "${packet.timestampSeconds}.${packet.timestampMicros.toString().padStart(6, '0')} s")
                ) + if (packet.comment.isNotBlank()) listOf(PcapLayerField("Comment", packet.comment)) else emptyList()
            )
        )

        if (data.isEmpty()) {
            return PcapDissection("RAW", "-", "-", null, null, "Empty packet (0 bytes)", layers)
        }

        // Handle 802.11 / Radiotap separately
        if (packet.linkType == DLT_IEEE802_11 || packet.linkType == DLT_IEEE802_11_RADIO) {
            return dissectWifi80211(packet, data, layers)
        }

        // Determine L3 offset & EtherType
        var l3Offset = -1
        var etherType = -1
        var linkSrc = "-"
        var linkDst = "-"

        when (packet.linkType) {
            DLT_EN10MB -> {
                if (data.size >= 14) {
                    linkDst = formatMac(data, 0)
                    linkSrc = formatMac(data, 6)
                    etherType = readUShortBE(data, 12)
                    l3Offset = 14
                    // 802.1Q VLAN Tag
                    if (etherType == 0x8100 && data.size >= 18) {
                        val vlanId = readUShortBE(data, 14) and 0x0FFF
                        etherType = readUShortBE(data, 16)
                        l3Offset = 18
                        layers.add(
                            PcapProtocolLayer(
                                title = "Ethernet II (VLAN $vlanId)",
                                summary = "$linkSrc → $linkDst • Type 0x${etherType.toString(16).padStart(4, '0')}",
                                fields = listOf(
                                    PcapLayerField("Destination MAC", linkDst),
                                    PcapLayerField("Source MAC", linkSrc),
                                    PcapLayerField("802.1Q VLAN ID", vlanId.toString()),
                                    PcapLayerField("EtherType", etherTypeLabel(etherType))
                                )
                            )
                        )
                    } else {
                        layers.add(
                            PcapProtocolLayer(
                                title = "Ethernet II",
                                summary = "$linkSrc → $linkDst • ${etherTypeLabel(etherType)}",
                                fields = listOf(
                                    PcapLayerField("Destination MAC", linkDst),
                                    PcapLayerField("Source MAC", linkSrc),
                                    PcapLayerField("EtherType", etherTypeLabel(etherType))
                                )
                            )
                        )
                    }
                }
            }
            DLT_RAW -> {
                val ver = (data[0].toInt() ushr 4) and 0x0F
                etherType = when (ver) {
                    4 -> 0x0800
                    6 -> 0x86DD
                    else -> -1
                }
                l3Offset = 0
            }
            DLT_NULL, DLT_LOOP -> {
                if (data.size >= 4) {
                    val family = if (packet.linkType == DLT_LOOP) readIntBE(data, 0) else readIntLE(data, 0)
                    etherType = when (family) {
                        2 -> 0x0800
                        10, 24, 28, 30 -> 0x86DD
                        else -> if (data.size > 4 && ((data[4].toInt() ushr 4) and 0x0F) == 4) 0x0800 else 0x86DD
                    }
                    l3Offset = 4
                    layers.add(PcapProtocolLayer("Loopback", "Family $family", listOf(PcapLayerField("Address Family", family.toString()))))
                }
            }
            DLT_LINUX_SLL -> {
                if (data.size >= 16) {
                    etherType = readUShortBE(data, 14)
                    l3Offset = 16
                    layers.add(PcapProtocolLayer("Linux Cooked Capture (SLL)", etherTypeLabel(etherType), listOf(PcapLayerField("Protocol", etherTypeLabel(etherType)))))
                }
            }
            DLT_LINUX_SLL2 -> {
                if (data.size >= 20) {
                    etherType = readUShortBE(data, 0)
                    l3Offset = 20
                    layers.add(PcapProtocolLayer("Linux Cooked Capture v2 (SLL2)", etherTypeLabel(etherType), listOf(PcapLayerField("Protocol", etherTypeLabel(etherType)))))
                }
            }
        }

        return dissectEtherTypePayload(
            packet = packet,
            data = data,
            l3Offset = l3Offset,
            etherType = etherType,
            fallbackSrc = linkSrc,
            fallbackDst = linkDst,
            layers = layers
        )
    }

    private fun dissectEtherTypePayload(
        packet: PcapPacketRecord,
        data: ByteArray,
        l3Offset: Int,
        etherType: Int,
        fallbackSrc: String,
        fallbackDst: String,
        layers: MutableList<PcapProtocolLayer>,
        wifiSsid: String? = null
    ): PcapDissection {
        if (l3Offset < 0 || l3Offset >= data.size) {
            return PcapDissection(
                protocol = "RAW",
                srcAddress = fallbackSrc,
                dstAddress = fallbackDst,
                info = "${data.size} bytes raw payload",
                layers = layers,
                payloadOffset = 0,
                payloadLength = data.size,
                wifiSsid = wifiSsid
            )
        }

        if (etherType == 0x888E) {
            val eapolInfo = parseEapolSummary(data, l3Offset, layers)
            return PcapDissection(
                protocol = "EAPOL",
                srcAddress = fallbackSrc,
                dstAddress = fallbackDst,
                info = eapolInfo,
                layers = layers,
                payloadOffset = l3Offset,
                payloadLength = data.size - l3Offset,
                isEapol = true,
                wifiSsid = wifiSsid
            )
        }

        if (etherType == 0x0806 && l3Offset + 28 <= data.size) {
            val op = readUShortBE(data, l3Offset + 6)
            val senderMac = formatMac(data, l3Offset + 8)
            val senderIp = formatIpv4(data, l3Offset + 14)
            val targetMac = formatMac(data, l3Offset + 18)
            val targetIp = formatIpv4(data, l3Offset + 24)
            val opStr = if (op == 1) "Who has $targetIp? Tell $senderIp" else "$senderIp is at $senderMac"
            layers.add(
                PcapProtocolLayer(
                    title = "ARP",
                    summary = opStr,
                    fields = listOf(
                        PcapLayerField("Operation", if (op == 1) "Request (1)" else "Reply ($op)"),
                        PcapLayerField("Sender MAC / IP", "$senderMac / $senderIp"),
                        PcapLayerField("Target MAC / IP", "$targetMac / $targetIp")
                    )
                )
            )
            return PcapDissection("ARP", senderIp, targetIp, null, null, opStr, layers, wifiSsid = wifiSsid)
        }

        var ipVersion = 0
        var ipProtocol = -1
        var srcIp = fallbackSrc
        var dstIp = fallbackDst
        var transportOffset = -1
        var ipPayloadEnd = data.size

        if (etherType == 0x0800 && l3Offset + 20 <= data.size) {
            val verIhl = data[l3Offset].toInt() and 0xFF
            val ver = (verIhl ushr 4) and 0x0F
            val ihl = (verIhl and 0x0F) * 4
            if (ver == 4 && ihl >= 20 && l3Offset + ihl <= data.size) {
                ipVersion = 4
                val totalLen = readUShortBE(data, l3Offset + 2)
                val ipId = readUShortBE(data, l3Offset + 4)
                val ttl = data[l3Offset + 8].toInt() and 0xFF
                ipProtocol = data[l3Offset + 9].toInt() and 0xFF
                val hdrChecksum = readUShortBE(data, l3Offset + 10)
                srcIp = formatIpv4(data, l3Offset + 12)
                dstIp = formatIpv4(data, l3Offset + 16)
                transportOffset = l3Offset + ihl
                if (totalLen >= ihl && l3Offset + totalLen <= data.size) {
                    ipPayloadEnd = l3Offset + totalLen
                }
                layers.add(
                    PcapProtocolLayer(
                        title = "IPv4",
                        summary = "$srcIp → $dstIp • Proto ${ipProtocolName(ipProtocol)} • TTL $ttl",
                        fields = listOf(
                            PcapLayerField("Source IP", srcIp),
                            PcapLayerField("Destination IP", dstIp),
                            PcapLayerField("Header Length", "$ihl bytes"),
                            PcapLayerField("Total Length", "$totalLen bytes"),
                            PcapLayerField("Identification", "0x${ipId.toString(16).padStart(4, '0')}"),
                            PcapLayerField("TTL", ttl.toString()),
                            PcapLayerField("Protocol", "${ipProtocolName(ipProtocol)} ($ipProtocol)"),
                            PcapLayerField("Header Checksum", "0x${hdrChecksum.toString(16).padStart(4, '0')}")
                        )
                    )
                )
            }
        } else if (etherType == 0x86DD && l3Offset + 40 <= data.size) {
            ipVersion = 6
            val payloadLen = readUShortBE(data, l3Offset + 4)
            ipProtocol = data[l3Offset + 6].toInt() and 0xFF
            val hopLimit = data[l3Offset + 7].toInt() and 0xFF
            srcIp = formatIpv6(data, l3Offset + 8)
            dstIp = formatIpv6(data, l3Offset + 24)
            transportOffset = l3Offset + 40
            if (l3Offset + 40 + payloadLen <= data.size) {
                ipPayloadEnd = l3Offset + 40 + payloadLen
            }
            layers.add(
                PcapProtocolLayer(
                    title = "IPv6",
                    summary = "$srcIp → $dstIp • Next ${ipProtocolName(ipProtocol)} • Hop $hopLimit",
                    fields = listOf(
                        PcapLayerField("Source IPv6", srcIp),
                        PcapLayerField("Destination IPv6", dstIp),
                        PcapLayerField("Payload Length", "$payloadLen bytes"),
                        PcapLayerField("Next Header", "${ipProtocolName(ipProtocol)} ($ipProtocol)"),
                        PcapLayerField("Hop Limit", hopLimit.toString())
                    )
                )
            )
        }

        if (transportOffset < 0 || transportOffset > ipPayloadEnd) {
            return PcapDissection(
                protocol = etherTypeShortName(etherType),
                srcAddress = srcIp,
                dstAddress = dstIp,
                info = "${etherTypeLabel(etherType)} (${data.size - l3Offset} bytes)",
                layers = layers,
                ipHeaderOffset = l3Offset,
                ipVersion = ipVersion,
                ipProtocol = ipProtocol,
                payloadOffset = l3Offset,
                payloadLength = data.size - l3Offset,
                wifiSsid = wifiSsid
            )
        }

        // Transport Layer
        if (ipProtocol == 6 && transportOffset + 20 <= data.size) {
            val srcPort = readUShortBE(data, transportOffset)
            val dstPort = readUShortBE(data, transportOffset + 2)
            val seq = readUIntBE(data, transportOffset + 4)
            val ack = readUIntBE(data, transportOffset + 8)
            val dataOff = ((data[transportOffset + 12].toInt() ushr 4) and 0x0F) * 4
            val flags = data[transportOffset + 13].toInt() and 0x3F
            val win = readUShortBE(data, transportOffset + 14)
            val cksum = readUShortBE(data, transportOffset + 16)
            val flagStr = formatTcpFlags(flags)
            val payOff = (transportOffset + dataOff.coerceAtLeast(20)).coerceAtMost(ipPayloadEnd)
            val payLen = (ipPayloadEnd - payOff).coerceAtLeast(0)

            layers.add(
                PcapProtocolLayer(
                    title = "TCP",
                    summary = "$srcPort → $dstPort [$flagStr] Seq=$seq Ack=$ack Len=$payLen",
                    fields = listOf(
                        PcapLayerField("Source Port", srcPort.toString()),
                        PcapLayerField("Destination Port", dstPort.toString()),
                        PcapLayerField("Sequence Number", seq.toString()),
                        PcapLayerField("Acknowledgment", ack.toString()),
                        PcapLayerField("Flags", "$flagStr (0x${flags.toString(16).padStart(2, '0')})"),
                        PcapLayerField("Window Size", win.toString()),
                        PcapLayerField("Checksum", "0x${cksum.toString(16).padStart(4, '0')}"),
                        PcapLayerField("Payload Length", "$payLen bytes")
                    )
                )
            )

            val streamKey = canonicalStreamKey("TCP", srcIp, srcPort, dstIp, dstPort)
            val app = dissectApplicationPayload(
                packet = packet,
                data = data,
                payOff = payOff,
                payLen = payLen,
                srcPort = srcPort,
                dstPort = dstPort,
                transportProto = "TCP",
                transportSummary = "$srcPort → $dstPort [$flagStr] Seq=$seq Len=$payLen",
                layers = layers
            )
            return PcapDissection(
                protocol = app.protocol,
                srcAddress = srcIp,
                dstAddress = dstIp,
                srcPort = srcPort,
                dstPort = dstPort,
                info = app.info,
                layers = layers,
                ipHeaderOffset = l3Offset,
                ipVersion = ipVersion,
                ipProtocol = ipProtocol,
                transportHeaderOffset = transportOffset,
                payloadOffset = payOff,
                payloadLength = payLen,
                isEncrypted = app.isEncrypted,
                encryptionHint = app.encryptionHint,
                wifiSsid = wifiSsid,
                streamKey = streamKey
            )
        } else if (ipProtocol == 17 && transportOffset + 8 <= data.size) {
            val srcPort = readUShortBE(data, transportOffset)
            val dstPort = readUShortBE(data, transportOffset + 2)
            val udpLen = readUShortBE(data, transportOffset + 4)
            val cksum = readUShortBE(data, transportOffset + 6)
            val payOff = transportOffset + 8
            val payLen = (minOf(ipPayloadEnd, transportOffset + udpLen.coerceAtLeast(8)) - payOff).coerceAtLeast(0)

            layers.add(
                PcapProtocolLayer(
                    title = "UDP",
                    summary = "$srcPort → $dstPort Len=$payLen",
                    fields = listOf(
                        PcapLayerField("Source Port", srcPort.toString()),
                        PcapLayerField("Destination Port", dstPort.toString()),
                        PcapLayerField("Length", "$udpLen bytes"),
                        PcapLayerField("Checksum", "0x${cksum.toString(16).padStart(4, '0')}")
                    )
                )
            )
            val streamKey = canonicalStreamKey("UDP", srcIp, srcPort, dstIp, dstPort)
            val app = dissectApplicationPayload(
                packet = packet,
                data = data,
                payOff = payOff,
                payLen = payLen,
                srcPort = srcPort,
                dstPort = dstPort,
                transportProto = "UDP",
                transportSummary = "$srcPort → $dstPort Len=$payLen",
                layers = layers
            )
            return PcapDissection(
                protocol = app.protocol,
                srcAddress = srcIp,
                dstAddress = dstIp,
                srcPort = srcPort,
                dstPort = dstPort,
                info = app.info,
                layers = layers,
                ipHeaderOffset = l3Offset,
                ipVersion = ipVersion,
                ipProtocol = ipProtocol,
                transportHeaderOffset = transportOffset,
                payloadOffset = payOff,
                payloadLength = payLen,
                isEncrypted = app.isEncrypted,
                encryptionHint = app.encryptionHint,
                wifiSsid = wifiSsid,
                streamKey = streamKey
            )
        } else if ((ipProtocol == 1 || ipProtocol == 58) && transportOffset + 4 <= data.size) {
            val type = data[transportOffset].toInt() and 0xFF
            val code = data[transportOffset + 1].toInt() and 0xFF
            val label = when {
                ipProtocol == 1 && type == 8 -> "Echo (ping) request"
                ipProtocol == 1 && type == 0 -> "Echo (ping) reply"
                ipProtocol == 1 && type == 3 -> "Destination unreachable (code $code)"
                ipProtocol == 1 && type == 11 -> "Time-to-live exceeded"
                ipProtocol == 58 && type == 128 -> "ICMPv6 Echo request"
                ipProtocol == 58 && type == 129 -> "ICMPv6 Echo reply"
                else -> "Type $type, Code $code"
            }
            val protoName = if (ipProtocol == 58) "ICMPv6" else "ICMP"
            layers.add(
                PcapProtocolLayer(
                    title = protoName,
                    summary = label,
                    fields = listOf(
                        PcapLayerField("Type", type.toString()),
                        PcapLayerField("Code", code.toString()),
                        PcapLayerField("Summary", label)
                    )
                )
            )
            return PcapDissection(
                protocol = protoName,
                srcAddress = srcIp,
                dstAddress = dstIp,
                info = label,
                layers = layers,
                ipHeaderOffset = l3Offset,
                ipVersion = ipVersion,
                ipProtocol = ipProtocol,
                transportHeaderOffset = transportOffset,
                payloadOffset = (transportOffset + 8).coerceAtMost(ipPayloadEnd),
                payloadLength = (ipPayloadEnd - (transportOffset + 8)).coerceAtLeast(0),
                wifiSsid = wifiSsid
            )
        }

        val protoName = ipProtocolName(ipProtocol)
        return PcapDissection(
            protocol = protoName,
            srcAddress = srcIp,
            dstAddress = dstIp,
            info = "$protoName (${ipPayloadEnd - transportOffset} bytes)",
            layers = layers,
            ipHeaderOffset = l3Offset,
            ipVersion = ipVersion,
            ipProtocol = ipProtocol,
            transportHeaderOffset = transportOffset,
            payloadOffset = transportOffset,
            payloadLength = (ipPayloadEnd - transportOffset).coerceAtLeast(0),
            wifiSsid = wifiSsid
        )
    }

    private data class AppDissectResult(
        val protocol: String,
        val info: String,
        val isEncrypted: Boolean = false,
        val encryptionHint: String = ""
    )

    private fun dissectApplicationPayload(
        packet: PcapPacketRecord,
        data: ByteArray,
        payOff: Int,
        payLen: Int,
        srcPort: Int,
        dstPort: Int,
        transportProto: String,
        transportSummary: String,
        layers: MutableList<PcapProtocolLayer>
    ): AppDissectResult {
        if (!packet.decryptedTextPreview.isNullOrBlank() || packet.decryptedBytes != null) {
            layers.add(
                PcapProtocolLayer(
                    title = "Decrypted Payload (${packet.decryptionMethod ?: "Authorized Key"})",
                    summary = packet.decryptedSummary ?: "${packet.decryptedBytes?.size ?: 0} bytes decrypted",
                    fields = listOf(
                        PcapLayerField("Decryption Method", packet.decryptionMethod ?: "Known Key"),
                        PcapLayerField("Decrypted Length", "${packet.decryptedBytes?.size ?: 0} bytes"),
                        PcapLayerField("Plaintext Preview", packet.decryptedTextPreview?.take(240) ?: "")
                    )
                )
            )
        }

        if (payLen <= 0 || payOff + payLen > data.size) {
            return AppDissectResult(transportProto, transportSummary)
        }

        // DNS (UDP/TCP 53 or mDNS 5353)
        if ((srcPort == 53 || dstPort == 53 || srcPort == 5353 || dstPort == 5353) && payLen >= 12) {
            val dnsOffset = if (transportProto == "TCP" && payLen >= 14) payOff + 2 else payOff
            val dnsInfo = parseDnsLayer(data, dnsOffset, payOff + payLen, layers)
            if (dnsInfo != null) {
                return AppDissectResult(if (srcPort == 5353 || dstPort == 5353) "mDNS" else "DNS", dnsInfo)
            }
        }

        // HTTP/1.x detection
        val headText = String(data, payOff, minOf(payLen, 160), Charsets.ISO_8859_1)
        if (headText.startsWith("GET ") || headText.startsWith("POST ") || headText.startsWith("PUT ") ||
            headText.startsWith("DELETE ") || headText.startsWith("HEAD ") || headText.startsWith("PATCH ") ||
            headText.startsWith("OPTIONS ") || headText.startsWith("HTTP/")
        ) {
            val firstLine = headText.lineSequence().firstOrNull()?.trim().orEmpty()
            val fullPreview = String(data, payOff, minOf(payLen, 1024), Charsets.UTF_8)
            val hostLine = fullPreview.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }?.trim()
            val fields = mutableListOf(PcapLayerField("Start Line", firstLine))
            if (!hostLine.isNullOrBlank()) fields.add(PcapLayerField("Host", hostLine.substringAfter(':').trim()))
            fields.add(PcapLayerField("Payload Size", "$payLen bytes"))
            layers.add(PcapProtocolLayer("HTTP", firstLine, fields))
            return AppDissectResult("HTTP", firstLine)
        }

        // TLS / SSL Record detection
        val recType = data[payOff].toInt() and 0xFF
        val verMaj = if (payLen >= 2) data[payOff + 1].toInt() and 0xFF else 0
        val verMin = if (payLen >= 3) data[payOff + 2].toInt() and 0xFF else 0
        if (payLen >= 5 && recType in 20..23 && verMaj == 3 && verMin in 0..4) {
            val tlsVer = when (verMin) {
                0 -> "SSL 3.0"
                1 -> "TLS 1.0"
                2 -> "TLS 1.1"
                3 -> "TLS 1.2"
                4 -> "TLS 1.3"
                else -> "TLS"
            }
            val recLen = readUShortBE(data, payOff + 3)
            val (recLabel, sni) = when (recType) {
                20 -> "Change Cipher Spec" to null
                21 -> "Encrypted Alert" to null
                22 -> {
                    val hsType = if (payLen >= 6) data[payOff + 5].toInt() and 0xFF else -1
                    val hsName = when (hsType) {
                        1 -> "Client Hello"
                        2 -> "Server Hello"
                        11 -> "Certificate"
                        12 -> "Server Key Exchange"
                        14 -> "Server Hello Done"
                        16 -> "Client Key Exchange"
                        else -> "Handshake ($hsType)"
                    }
                    val extractedSni = if (hsType == 1) extractTlsClientHelloSni(data, payOff + 5, minOf(payOff + 5 + recLen, payOff + payLen)) else null
                    (if (extractedSni != null) "$hsName (SNI: $extractedSni)" else hsName) to extractedSni
                }
                23 -> "Application Data ($recLen bytes)" to null
                else -> "TLS Record ($recType)" to null
            }
            val fields = mutableListOf(
                PcapLayerField("Version", tlsVer),
                PcapLayerField("Record Type", "$recLabel ($recType)"),
                PcapLayerField("Record Length", "$recLen bytes")
            )
            if (sni != null) fields.add(PcapLayerField("Server Name (SNI)", sni))
            layers.add(PcapProtocolLayer("TLS / SSL", "$tlsVer • $recLabel", fields))

            val isAppData = recType == 23
            val finalInfo = if (packet.decryptedSummary != null) {
                "DECRYPTED • ${packet.decryptedSummary}"
            } else {
                "$tlsVer $recLabel"
            }
            return AppDissectResult(
                protocol = if (packet.decryptedProtocol != null) packet.decryptedProtocol else "TLS",
                info = finalInfo,
                isEncrypted = isAppData && packet.decryptedBytes == null,
                encryptionHint = if (isAppData) "TLS Application Data — supply SSLKEYLOGFILE to decrypt" else ""
            )
        }

        // SSH detection
        if (headText.startsWith("SSH-")) {
            val banner = headText.lineSequence().firstOrNull()?.trim().orEmpty()
            layers.add(PcapProtocolLayer("SSH", banner, listOf(PcapLayerField("Banner", banner))))
            return AppDissectResult("SSH", banner, isEncrypted = true, encryptionHint = "SSH Protocol")
        }

        // DHCP (UDP 67/68)
        if (transportProto == "UDP" && (srcPort == 67 || dstPort == 67 || srcPort == 68 || dstPort == 68)) {
            layers.add(PcapProtocolLayer("DHCP", "DHCP Packet ($payLen bytes)", listOf(PcapLayerField("Size", "$payLen bytes"))))
            return AppDissectResult("DHCP", "DHCP ($srcPort → $dstPort, $payLen bytes)")
        }

        // QUIC / HTTPS UDP 443
        if (transportProto == "UDP" && (srcPort == 443 || dstPort == 443)) {
            layers.add(PcapProtocolLayer("QUIC", "QUIC Protected Payload ($payLen bytes)", listOf(PcapLayerField("Size", "$payLen bytes"))))
            return AppDissectResult("QUIC", "QUIC ($srcPort → $dstPort, $payLen bytes)", isEncrypted = true, encryptionHint = "QUIC Encrypted UDP")
        }

        val printable = extractPrintablePreview(data, payOff, minOf(payLen, 96))
        if (printable.isNotBlank()) {
            layers.add(
                PcapProtocolLayer(
                    title = "Payload ($payLen bytes)",
                    summary = printable.take(64),
                    fields = listOf(
                        PcapLayerField("Bytes", "$payLen bytes"),
                        PcapLayerField("ASCII Preview", printable)
                    )
                )
            )
        }
        return AppDissectResult(
            protocol = packet.decryptedProtocol ?: transportProto,
            info = packet.decryptedSummary ?: if (printable.length >= 6) "$transportSummary • ${printable.take(48)}" else transportSummary
        )
    }

    // -----------------------------------------------------------------------------------------
    // IEEE 802.11 / RADIOTAP DISSECTOR
    // -----------------------------------------------------------------------------------------

    private fun dissectWifi80211(
        packet: PcapPacketRecord,
        data: ByteArray,
        layers: MutableList<PcapProtocolLayer>
    ): PcapDissection {
        var dot11Offset = 0
        if (packet.linkType == DLT_IEEE802_11_RADIO) {
            if (data.size < 8) {
                return PcapDissection("RADIOTAP", "-", "-", info = "Truncated Radiotap header", layers = layers)
            }
            val rtLen = readUShortLE(data, 2)
            if (rtLen < 8 || rtLen > data.size) {
                return PcapDissection("RADIOTAP", "-", "-", info = "Invalid Radiotap length ($rtLen)", layers = layers)
            }
            layers.add(
                PcapProtocolLayer(
                    title = "Radiotap Header",
                    summary = "Header Length $rtLen bytes",
                    fields = listOf(
                        PcapLayerField("Version", (data[0].toInt() and 0xFF).toString()),
                        PcapLayerField("Header Length", "$rtLen bytes")
                    )
                )
            )
            dot11Offset = rtLen
        }

        if (dot11Offset + 10 > data.size) {
            return PcapDissection("802.11", "-", "-", info = "Short 802.11 frame (${data.size - dot11Offset} bytes)", layers = layers)
        }

        val fc = readUShortLE(data, dot11Offset)
        val type = (fc ushr 2) and 0x03
        val subtype = (fc ushr 4) and 0x0F
        val toDs = (fc and 0x0100) != 0
        val fromDs = (fc and 0x0200) != 0
        val isProtected = (fc and 0x4000) != 0

        val addr1 = if (dot11Offset + 10 <= data.size) formatMac(data, dot11Offset + 4) else "-"
        val addr2 = if (dot11Offset + 16 <= data.size) formatMac(data, dot11Offset + 10) else "-"
        val addr3 = if (dot11Offset + 22 <= data.size) formatMac(data, dot11Offset + 16) else "-"

        val (srcMac, dstMac, bssid) = when {
            !toDs && !fromDs -> Triple(addr2, addr1, addr3)
            !toDs && fromDs -> Triple(addr3, addr1, addr2)
            toDs && !fromDs -> Triple(addr2, addr3, addr1)
            else -> Triple(addr2, addr1, addr3)
        }

        val frameLabel = when (type) {
            0 -> when (subtype) {
                0 -> "Association Request"
                1 -> "Association Response"
                4 -> "Probe Request"
                5 -> "Probe Response"
                8 -> "Beacon"
                10 -> "Disassociation"
                11 -> "Authentication"
                12 -> "Deauthentication"
                else -> "Management ($subtype)"
            }
            1 -> when (subtype) {
                11 -> "RTS"
                12 -> "CTS"
                13 -> "ACK"
                else -> "Control ($subtype)"
            }
            2 -> when (subtype) {
                0 -> "Data"
                4 -> "Null Data"
                8 -> "QoS Data"
                12 -> "QoS Null"
                else -> "Data ($subtype)"
            }
            else -> "802.11 Type $type/$subtype"
        }

        var ssid: String? = null
        if (type == 0 && (subtype == 8 || subtype == 5 || subtype == 4 || subtype == 0)) {
            val ieStart = dot11Offset + if (subtype == 8 || subtype == 5) 36 else if (subtype == 0) 28 else 24
            ssid = extractWifiSsidFromIes(data, ieStart)
        }

        val dot11Fields = mutableListOf(
            PcapLayerField("Frame Control", "0x${fc.toString(16).padStart(4, '0')} ($frameLabel)"),
            PcapLayerField("Source MAC", srcMac),
            PcapLayerField("Destination MAC", dstMac),
            PcapLayerField("BSSID", bssid),
            PcapLayerField("Flags", "ToDS=${if (toDs) 1 else 0} FromDS=${if (fromDs) 1 else 0} Protected=${if (isProtected) 1 else 0}")
        )
        if (!ssid.isNullOrBlank()) dot11Fields.add(PcapLayerField("SSID", ssid))

        layers.add(
            PcapProtocolLayer(
                title = "IEEE 802.11 $frameLabel",
                summary = buildString {
                    append("$srcMac → $dstMac")
                    if (!ssid.isNullOrBlank()) append(" • SSID=\"$ssid\"")
                    if (isProtected) append(" • Protected")
                },
                fields = dot11Fields
            )
        )

        // If already decrypted by known-key engine, dissect decrypted inner LLC/SNAP + IP
        if (packet.decryptedBytes != null && packet.decryptedBytes.size >= 8) {
            val dec = packet.decryptedBytes
            layers.add(
                PcapProtocolLayer(
                    title = "Decrypted 802.11 Payload (${packet.decryptionMethod ?: "WPA2/WEP"})",
                    summary = "${dec.size} bytes plaintext LLC/SNAP",
                    fields = listOf(
                        PcapLayerField("Method", packet.decryptionMethod ?: "Known Key"),
                        PcapLayerField("Decrypted Size", "${dec.size} bytes")
                    )
                )
            )
            if (dec[0] == 0xAA.toByte() && dec[1] == 0xAA.toByte() && dec[2] == 0x03.toByte()) {
                val innerEtherType = readUShortBE(dec, 6)
                val inner = dissectEtherTypePayload(packet, dec, 8, innerEtherType, srcMac, dstMac, layers, ssid)
                return inner.copy(
                    info = "DECRYPTED • ${inner.info}",
                    isEncrypted = false
                )
            }
        }

        // Data frame header length
        val hdrLen = computeDot11HeaderLength(fc)
        val bodyOffset = dot11Offset + hdrLen

        // Unencrypted LLC/SNAP (e.g., EAPOL 4-way handshake or open Wi-Fi)
        if (type == 2 && !isProtected && bodyOffset + 8 <= data.size &&
            data[bodyOffset] == 0xAA.toByte() && data[bodyOffset + 1] == 0xAA.toByte() && data[bodyOffset + 2] == 0x03.toByte()
        ) {
            val etherType = readUShortBE(data, bodyOffset + 6)
            return dissectEtherTypePayload(packet, data, bodyOffset + 8, etherType, srcMac, dstMac, layers, ssid)
        }

        // Protected 802.11 Data frame (WPA2-CCMP / TKIP / WEP)
        if (type == 2 && isProtected && bodyOffset + 8 <= data.size) {
            val extIv = (data[bodyOffset + 3].toInt() and 0x20) != 0
            val encType = if (extIv) "WPA2-CCMP / TKIP" else "WEP"
            return PcapDissection(
                protocol = "802.11",
                srcAddress = srcMac,
                dstAddress = dstMac,
                info = "$frameLabel • Protected ($encType, ${data.size - bodyOffset} bytes)",
                layers = layers,
                payloadOffset = bodyOffset,
                payloadLength = data.size - bodyOffset,
                isEncrypted = true,
                encryptionHint = "Protected $encType — enter known SSID + Passphrase (or WEP key) in Decrypt tab",
                wifiSsid = ssid
            )
        }

        return PcapDissection(
            protocol = "802.11",
            srcAddress = srcMac,
            dstAddress = dstMac,
            info = buildString {
                append(frameLabel)
                if (!ssid.isNullOrBlank()) append(", SSID=\"$ssid\"")
            },
            layers = layers,
            payloadOffset = bodyOffset.coerceAtMost(data.size),
            payloadLength = (data.size - bodyOffset).coerceAtLeast(0),
            wifiSsid = ssid
        )
    }

    private fun parseEapolSummary(data: ByteArray, offset: Int, layers: MutableList<PcapProtocolLayer>): String {
        if (offset + 4 > data.size) return "EAPOL (truncated)"
        val version = data[offset].toInt() and 0xFF
        val type = data[offset + 1].toInt() and 0xFF
        val len = readUShortBE(data, offset + 2)
        if (type == 3 && offset + 99 <= data.size) {
            val keyInfo = readUShortBE(data, offset + 5)
            val hasAck = (keyInfo and 0x0080) != 0
            val hasMic = (keyInfo and 0x0100) != 0
            val hasSecure = (keyInfo and 0x0200) != 0
            val hasInstall = (keyInfo and 0x0040) != 0
            val msgNum = when {
                hasAck && !hasMic -> "Message 1 of 4 (ANonce)"
                !hasAck && hasMic && !hasSecure -> "Message 2 of 4 (SNonce + MIC)"
                hasAck && hasMic && hasInstall -> "Message 3 of 4 (Install GTK)"
                !hasAck && hasMic && hasSecure -> "Message 4 of 4 (Confirm)"
                else -> "Key Frame"
            }
            val nonceHex = bytesToHex(data, offset + 17, 16)
            layers.add(
                PcapProtocolLayer(
                    title = "EAPOL Key (4-Way Handshake)",
                    summary = msgNum,
                    fields = listOf(
                        PcapLayerField("Version", version.toString()),
                        PcapLayerField("Handshake Step", msgNum),
                        PcapLayerField("Key Info", "0x${keyInfo.toString(16).padStart(4, '0')}"),
                        PcapLayerField("Nonce (first 16B)", "$nonceHex..."),
                        PcapLayerField("Length", "$len bytes")
                    )
                )
            )
            return "EAPOL Key ($msgNum)"
        }
        layers.add(PcapProtocolLayer("EAPOL", "Type $type ($len bytes)", listOf(PcapLayerField("Type", type.toString()))))
        return "EAPOL Type $type ($len bytes)"
    }

    // -----------------------------------------------------------------------------------------
    // AUTHORIZED KNOWN-KEY DECRYPTION ENGINE
    // -----------------------------------------------------------------------------------------

    /**
     * Decrypts TLS 1.2 / TLS 1.3 Application Data records using a standard user-supplied SSLKEYLOGFILE
     * (or embedded PCAPNG DSB secrets).
     */
    fun decryptWithTlsKeyLog(
        document: PcapCaptureDocument,
        keyLogText: String
    ): PcapDecryptionOutcome {
        val secrets = parseSslKeyLog(keyLogText)
        if (secrets.clientRandomToMaster.isEmpty() && secrets.tls13TrafficSecrets.isEmpty()) {
            return PcapDecryptionOutcome(
                updatedDocument = document,
                decryptedCount = 0,
                summaryMessage = "No valid CLIENT_RANDOM or CLIENT/SERVER_TRAFFIC_SECRET_0 entries found in key log."
            )
        }

        // Pass 1: Collect ClientRandom, ServerRandom, CipherSuite per TCP stream
        data class TlsSessionInfo(
            var clientRandom: ByteArray? = null,
            var serverRandom: ByteArray? = null,
            var cipherSuite: Int = 0x1301,
            var clientIp: String = "",
            var clientPort: Int = 0,
            var clientSeq: Long = 0L,
            var serverSeq: Long = 0L
        )

        val sessions = HashMap<String, TlsSessionInfo>()
        for (pkt in document.packets) {
            val d = dissectPacket(pkt)
            val stream = d.streamKey ?: continue
            if (d.payloadOffset < 0 || d.payloadLength < 6) continue
            val data = pkt.rawData
            val off = d.payloadOffset
            val end = (off + d.payloadLength).coerceAtMost(data.size)
            if (data[off].toInt() and 0xFF == 22 && off + 44 <= end) {
                val hsType = data[off + 5].toInt() and 0xFF
                val sess = sessions.getOrPut(stream) { TlsSessionInfo() }
                if (hsType == 1 && off + 43 <= end) { // ClientHello
                    sess.clientRandom = data.copyOfRange(off + 11, off + 43)
                    sess.clientIp = d.srcAddress
                    sess.clientPort = d.srcPort ?: 0
                } else if (hsType == 2 && off + 44 <= end) { // ServerHello
                    sess.serverRandom = data.copyOfRange(off + 11, off + 43)
                    val sidLen = data[off + 43].toInt() and 0xFF
                    val csOff = off + 44 + sidLen
                    if (csOff + 2 <= end) {
                        sess.cipherSuite = readUShortBE(data, csOff)
                    }
                }
            }
        }

        var decryptedCount = 0
        val updatedPackets = document.packets.map { pkt ->
            val d = dissectPacket(pkt)
            val stream = d.streamKey ?: return@map pkt
            val sess = sessions[stream] ?: return@map pkt
            val cRand = sess.clientRandom ?: return@map pkt
            val cRandHex = bytesToHex(cRand, 0, cRand.size).lowercase(Locale.US)
            if (d.payloadOffset < 0 || d.payloadLength < 14) return@map pkt

            val data = pkt.rawData
            val off = d.payloadOffset
            val end = (off + d.payloadLength).coerceAtMost(data.size)
            val recType = data[off].toInt() and 0xFF
            if (recType != 23) return@map pkt // Application Data only
            val recLen = readUShortBE(data, off + 3)
            if (off + 5 + recLen > end) return@map pkt
            val recordPayload = data.copyOfRange(off + 5, off + 5 + recLen)
            val isFromClient = (d.srcAddress == sess.clientIp && (d.srcPort ?: 0) == sess.clientPort)

            // Try TLS 1.3 traffic secrets first
            val tls13Secret = if (isFromClient) {
                secrets.tls13TrafficSecrets["CLIENT_TRAFFIC_SECRET_0:$cRandHex"]
            } else {
                secrets.tls13TrafficSecrets["SERVER_TRAFFIC_SECRET_0:$cRandHex"]
            }
            if (tls13Secret != null) {
                val seq = if (isFromClient) sess.clientSeq++ else sess.serverSeq++
                val plain = decryptTls13AesGcmRecord(
                    trafficSecret = tls13Secret,
                    cipherSuite = sess.cipherSuite,
                    recordHeader = data.copyOfRange(off, off + 5),
                    ciphertextWithTag = recordPayload,
                    seqNum = seq
                )
                if (plain != null) {
                    decryptedCount++
                    val preview = extractPrintablePreview(plain, 0, minOf(plain.size, 512))
                    return@map pkt.copy(
                        decryptedBytes = plain,
                        decryptedProtocol = if (preview.startsWith("GET ") || preview.startsWith("POST ") || preview.startsWith("HTTP/")) "HTTP (TLS 1.3)" else "TLS 1.3 (Decrypted)",
                        decryptedSummary = preview.lineSequence().firstOrNull()?.take(80)?.ifBlank { "${plain.size} bytes decrypted" } ?: "${plain.size} bytes decrypted",
                        decryptedTextPreview = preview,
                        decryptionMethod = "TLS 1.3 KeyLog (AES-GCM)"
                    )
                }
            }

            // Try TLS 1.2 master secret
            val masterSecret = secrets.clientRandomToMaster[cRandHex]
            val sRand = sess.serverRandom
            if (masterSecret != null && sRand != null) {
                val seq = if (isFromClient) sess.clientSeq++ else sess.serverSeq++
                val plain = decryptTls12AesGcmRecord(
                    masterSecret = masterSecret,
                    clientRandom = cRand,
                    serverRandom = sRand,
                    cipherSuite = sess.cipherSuite,
                    isFromClient = isFromClient,
                    recordVersionBytes = data.copyOfRange(off + 1, off + 3),
                    recordPayload = recordPayload,
                    seqNum = seq
                )
                if (plain != null) {
                    decryptedCount++
                    val preview = extractPrintablePreview(plain, 0, minOf(plain.size, 512))
                    return@map pkt.copy(
                        decryptedBytes = plain,
                        decryptedProtocol = if (preview.startsWith("GET ") || preview.startsWith("POST ") || preview.startsWith("HTTP/")) "HTTP (TLS 1.2)" else "TLS 1.2 (Decrypted)",
                        decryptedSummary = preview.lineSequence().firstOrNull()?.take(80)?.ifBlank { "${plain.size} bytes decrypted" } ?: "${plain.size} bytes decrypted",
                        decryptedTextPreview = preview,
                        decryptionMethod = "TLS 1.2 KeyLog (AES-GCM)"
                    )
                }
            }
            pkt
        }

        val summary = if (decryptedCount > 0) {
            "Decrypted $decryptedCount TLS Application Data packet(s) using provided key log."
        } else {
            "Loaded ${secrets.clientRandomToMaster.size + secrets.tls13TrafficSecrets.size} secret(s), but no matching TLS AES-GCM records were decrypted."
        }
        return PcapDecryptionOutcome(
            updatedDocument = document.copy(packets = updatedPackets, embeddedKeyLogText = keyLogText.trim()),
            decryptedCount = decryptedCount,
            summaryMessage = summary
        )
    }

    /**
     * Decrypts protected IEEE 802.11 WPA2-PSK (AES-CCMP) frames using a user-supplied known SSID + Passphrase
     * (or 64-hex PMK) combined with the capture's 4-way EAPOL handshake.
     */
    fun decryptWifiWpa2WithKnownPassphrase(
        document: PcapCaptureDocument,
        ssidInput: String,
        passphraseOrPmkHex: String
    ): PcapDecryptionOutcome {
        val cleanSecret = passphraseOrPmkHex.trim()
        if (cleanSecret.isBlank()) {
            return PcapDecryptionOutcome(document, 0, "Enter the known Wi-Fi passphrase or 64-hex PMK.")
        }

        val pmk: ByteArray = if (cleanSecret.length == 64 && cleanSecret.all { it in "0123456789abcdefABCDEF" }) {
            hexToBytes(cleanSecret)
        } else {
            val ssid = ssidInput.trim().ifBlank { detectWifiSsids(document).firstOrNull().orEmpty() }
            if (ssid.isBlank()) {
                return PcapDecryptionOutcome(document, 0, "Enter the network SSID along with the known passphrase.")
            }
            deriveWpa2Pmk(cleanSecret, ssid)
        }

        // Collect EAPOL 4-way handshake ANonce / SNonce per (AP, STA) pair
        data class HandshakeState(
            val apMac: ByteArray,
            val staMac: ByteArray,
            var aNonce: ByteArray? = null,
            var sNonce: ByteArray? = null,
            var tk: ByteArray? = null
        )

        val handshakes = HashMap<String, HandshakeState>()
        for (pkt in document.packets) {
            if (pkt.linkType != DLT_IEEE802_11 && pkt.linkType != DLT_IEEE802_11_RADIO) continue
            val dOff = dot11OffsetOf(pkt) ?: continue
            val data = pkt.rawData
            if (dOff + 24 > data.size) continue
            val fc = readUShortLE(data, dOff)
            if (((fc ushr 2) and 0x03) != 2 || (fc and 0x4000) != 0) continue
            val bodyOff = dOff + computeDot11HeaderLength(fc)
            if (bodyOff + 8 + 99 > data.size) continue
            if (data[bodyOff] != 0xAA.toByte() || data[bodyOff + 1] != 0xAA.toByte() || data[bodyOff + 2] != 0x03.toByte()) continue
            if (readUShortBE(data, bodyOff + 6) != 0x888E) continue

            val eapolOff = bodyOff + 8
            if ((data[eapolOff + 1].toInt() and 0xFF) != 3) continue
            val keyInfo = readUShortBE(data, eapolOff + 5)
            val hasAck = (keyInfo and 0x0080) != 0
            val hasMic = (keyInfo and 0x0100) != 0
            val nonce = data.copyOfRange(eapolOff + 17, eapolOff + 49)
            if (nonce.all { it == 0.toByte() }) continue

            val addr1 = data.copyOfRange(dOff + 4, dOff + 10)
            val addr2 = data.copyOfRange(dOff + 10, dOff + 16)
            val (apMac, staMac) = if (hasAck) addr2 to addr1 else addr1 to addr2
            val pairKey = "${bytesToHex(apMac, 0, 6)}_${bytesToHex(staMac, 0, 6)}"
            val hs = handshakes.getOrPut(pairKey) { HandshakeState(apMac, staMac) }
            if (hasAck && !hasMic) {
                hs.aNonce = nonce
            } else if (!hasAck && hasMic) {
                hs.sNonce = nonce
            }
            if (hs.aNonce != null && hs.sNonce != null && hs.tk == null) {
                val ptk = deriveWpa2Ptk(pmk, hs.apMac, hs.staMac, hs.aNonce!!, hs.sNonce!!)
                hs.tk = ptk.copyOfRange(32, 48) // 16-byte Temporal Key (TK)
            }
        }

        val derivedKeys = handshakes.values.mapNotNull { hs ->
            hs.tk?.let { tk -> Triple(hs.apMac, hs.staMac, tk) }
        }
        if (derivedKeys.isEmpty()) {
            return PcapDecryptionOutcome(
                document,
                0,
                "No complete EAPOL 4-way handshake (Message 1 + Message 2) found in this capture to derive PTK."
            )
        }

        var decryptedCount = 0
        val updatedPackets = document.packets.map { pkt ->
            if (pkt.linkType != DLT_IEEE802_11 && pkt.linkType != DLT_IEEE802_11_RADIO) return@map pkt
            val dOff = dot11OffsetOf(pkt) ?: return@map pkt
            val data = pkt.rawData
            if (dOff + 24 > data.size) return@map pkt
            val fc = readUShortLE(data, dOff)
            if (((fc ushr 2) and 0x03) != 2 || (fc and 0x4000) == 0) return@map pkt
            val hdrLen = computeDot11HeaderLength(fc)
            val bodyOff = dOff + hdrLen
            if (bodyOff + 16 >= data.size) return@map pkt
            // CCMP has ExtIV bit set
            if ((data[bodyOff + 3].toInt() and 0x20) == 0) return@map pkt

            val a1 = data.copyOfRange(dOff + 4, dOff + 10)
            val a2 = data.copyOfRange(dOff + 10, dOff + 16)
            val matchTk = derivedKeys.firstOrNull { (ap, sta, _) ->
                (a1.contentEquals(ap) && a2.contentEquals(sta)) || (a1.contentEquals(sta) && a2.contentEquals(ap))
            }?.third ?: return@map pkt

            val plainLlc = decryptDot11Ccmp(data, dOff, hdrLen, matchTk) ?: return@map pkt
            decryptedCount++
            val preview = extractPrintablePreview(plainLlc, 0, minOf(plainLlc.size, 320))
            pkt.copy(
                decryptedBytes = plainLlc,
                decryptedProtocol = "802.11 (WPA2 Decrypted)",
                decryptedSummary = "CCMP Decrypted (${plainLlc.size} bytes)",
                decryptedTextPreview = preview,
                decryptionMethod = "WPA2-PSK CCMP"
            )
        }

        return PcapDecryptionOutcome(
            updatedDocument = document.copy(packets = updatedPackets),
            decryptedCount = decryptedCount,
            summaryMessage = if (decryptedCount > 0) {
                "Decrypted $decryptedCount WPA2-CCMP 802.11 frame(s) using known SSID & passphrase."
            } else {
                "Derived PTK from handshake, but no protected CCMP frames matched or passphrase did not verify MIC."
            }
        )
    }

    /**
     * Decrypts protected IEEE 802.11 WEP frames using a user-supplied known WEP key (hex or ASCII).
     */
    fun decryptWifiWepWithKnownKey(
        document: PcapCaptureDocument,
        wepKeyInput: String
    ): PcapDecryptionOutcome {
        val trimmed = wepKeyInput.trim().replace(":", "").replace("-", "").replace(" ", "")
        if (trimmed.isBlank()) {
            return PcapDecryptionOutcome(document, 0, "Enter a known WEP key (10/26 hex digits or 5/13 ASCII chars).")
        }
        val keyBytes = if ((trimmed.length == 10 || trimmed.length == 26) && trimmed.all { it in "0123456789abcdefABCDEF" }) {
            hexToBytes(trimmed)
        } else {
            trimmed.toByteArray(Charsets.US_ASCII)
        }

        var decryptedCount = 0
        val updatedPackets = document.packets.map { pkt ->
            if (pkt.linkType != DLT_IEEE802_11 && pkt.linkType != DLT_IEEE802_11_RADIO) return@map pkt
            val dOff = dot11OffsetOf(pkt) ?: return@map pkt
            val data = pkt.rawData
            if (dOff + 24 > data.size) return@map pkt
            val fc = readUShortLE(data, dOff)
            if (((fc ushr 2) and 0x03) != 2 || (fc and 0x4000) == 0) return@map pkt
            val bodyOff = dOff + computeDot11HeaderLength(fc)
            if (bodyOff + 12 >= data.size) return@map pkt
            if ((data[bodyOff + 3].toInt() and 0x20) != 0) return@map pkt // Skip CCMP/TKIP

            val iv = data.copyOfRange(bodyOff, bodyOff + 3)
            val rc4Key = iv + keyBytes
            val encAndIcv = data.copyOfRange(bodyOff + 4, data.size)
            val decAll = rc4Crypt(rc4Key, encAndIcv)
            if (decAll.size < 8) return@map pkt
            val plain = decAll.copyOfRange(0, decAll.size - 4)
            val expectedIcv = readUIntLE(decAll, decAll.size - 4)
            val crc = CRC32().apply { update(plain) }.value
            if (crc != expectedIcv) return@map pkt

            decryptedCount++
            pkt.copy(
                decryptedBytes = plain,
                decryptedProtocol = "802.11 (WEP Decrypted)",
                decryptedSummary = "WEP Decrypted (${plain.size} bytes)",
                decryptedTextPreview = extractPrintablePreview(plain, 0, minOf(plain.size, 320)),
                decryptionMethod = "WEP (RC4)"
            )
        }

        return PcapDecryptionOutcome(
            updatedDocument = document.copy(packets = updatedPackets),
            decryptedCount = decryptedCount,
            summaryMessage = if (decryptedCount > 0) {
                "Decrypted $decryptedCount WEP frame(s) with verified ICV."
            } else {
                "No WEP frames matched the supplied key."
            }
        )
    }

    /**
     * Decrypts custom TCP/UDP application payloads using a user-supplied known AES or XOR key.
     */
    fun decryptCustomPayloads(
        document: PcapCaptureDocument,
        mode: CustomPayloadCipherMode,
        keyText: String,
        ivText: String,
        keyIsHex: Boolean,
        targetPacketIndex: Int? = null
    ): PcapDecryptionOutcome {
        val keyBytes = runCatching {
            if (keyIsHex) hexToBytes(keyText) else keyText.toByteArray(Charsets.UTF_8)
        }.getOrNull()
        if (keyBytes == null || keyBytes.isEmpty()) {
            return PcapDecryptionOutcome(document, 0, "Invalid decryption key format.")
        }
        val ivBytes = runCatching {
            if (ivText.isBlank()) ByteArray(0)
            else if (keyIsHex) hexToBytes(ivText) else ivText.toByteArray(Charsets.UTF_8)
        }.getOrDefault(ByteArray(0))

        var decryptedCount = 0
        val updatedPackets = document.packets.map { pkt ->
            if (targetPacketIndex != null && pkt.index != targetPacketIndex) return@map pkt
            val d = dissectPacket(pkt)
            if (d.payloadOffset < 0 || d.payloadLength <= 0 || d.payloadOffset + d.payloadLength > pkt.rawData.size) {
                return@map pkt
            }
            val payload = pkt.rawData.copyOfRange(d.payloadOffset, d.payloadOffset + d.payloadLength)
            val decrypted = runCatching {
                when (mode) {
                    CustomPayloadCipherMode.XOR_KEY -> {
                        ByteArray(payload.size) { idx ->
                            (payload[idx].toInt() xor keyBytes[idx % keyBytes.size].toInt()).toByte()
                        }
                    }
                    CustomPayloadCipherMode.AES_GCM -> {
                        val aesKey = normalizeAesKey(keyBytes)
                        val nonce = if (ivBytes.size == 12) ivBytes else if (payload.size > 28) payload.copyOfRange(0, 12) else return@runCatching null
                        val ct = if (ivBytes.size == 12) payload else payload.copyOfRange(12, payload.size)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
                        cipher.doFinal(ct)
                    }
                    CustomPayloadCipherMode.AES_CBC -> {
                        val aesKey = normalizeAesKey(keyBytes)
                        val iv = if (ivBytes.size == 16) ivBytes else if (payload.size >= 32) payload.copyOfRange(0, 16) else ByteArray(16)
                        val ct = if (ivBytes.size == 16 || payload.size < 32) payload else payload.copyOfRange(16, payload.size)
                        if (ct.size % 16 != 0 || ct.isEmpty()) return@runCatching null
                        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                        cipher.doFinal(ct)
                    }
                    CustomPayloadCipherMode.AES_CTR -> {
                        val aesKey = normalizeAesKey(keyBytes)
                        val iv = if (ivBytes.size == 16) ivBytes else ByteArray(16).also {
                            System.arraycopy(ivBytes, 0, it, 0, minOf(ivBytes.size, 16))
                        }
                        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
                        cipher.doFinal(payload)
                    }
                }
            }.getOrNull() ?: return@map pkt

            decryptedCount++
            val preview = extractPrintablePreview(decrypted, 0, minOf(decrypted.size, 512))
            pkt.copy(
                decryptedBytes = decrypted,
                decryptedProtocol = "${d.protocol} (Decrypted)",
                decryptedSummary = preview.lineSequence().firstOrNull()?.take(80)?.ifBlank { "${decrypted.size} bytes decrypted" } ?: "${decrypted.size} bytes decrypted",
                decryptedTextPreview = preview,
                decryptionMethod = mode.label
            )
        }

        return PcapDecryptionOutcome(
            updatedDocument = document.copy(packets = updatedPackets),
            decryptedCount = decryptedCount,
            summaryMessage = if (decryptedCount > 0) {
                "Decrypted $decryptedCount packet payload(s) using ${mode.label}."
            } else {
                "Could not decrypt packet payloads with the provided ${mode.label} key/IV."
            }
        )
    }

    /**
     * Materializes decrypted packets into a clean new document (wrapping decrypted 802.11 LLC payloads
     * into standard Ethernet frames or replacing encrypted TCP/UDP payloads with plaintext + fixed checksums).
     */
    fun buildDecryptedExportDocument(document: PcapCaptureDocument): PcapCaptureDocument {
        val exported = document.packets.mapIndexed { idx, pkt ->
            val dec = pkt.decryptedBytes
            if (dec == null) {
                pkt.copy(index = idx + 1)
            } else if ((pkt.linkType == DLT_IEEE802_11 || pkt.linkType == DLT_IEEE802_11_RADIO) &&
                dec.size >= 8 && dec[0] == 0xAA.toByte() && dec[1] == 0xAA.toByte() && dec[2] == 0x03.toByte()
            ) {
                val d = dissectPacket(pkt)
                val eth = ByteArray(14 + (dec.size - 8))
                val dstMacBytes = parseMacBytes(d.dstAddress) ?: ByteArray(6)
                val srcMacBytes = parseMacBytes(d.srcAddress) ?: ByteArray(6)
                System.arraycopy(dstMacBytes, 0, eth, 0, 6)
                System.arraycopy(srcMacBytes, 0, eth, 6, 6)
                eth[12] = dec[6]
                eth[13] = dec[7]
                System.arraycopy(dec, 8, eth, 14, dec.size - 8)
                pkt.copy(
                    index = idx + 1,
                    linkType = DLT_EN10MB,
                    rawData = eth,
                    originalLength = eth.size,
                    comment = (pkt.comment.takeIf { it.isNotBlank() }?.let { "$it | " } ?: "") + "Decrypted (${pkt.decryptionMethod ?: "802.11"})"
                )
            } else {
                val patched = replacePacketPayload(pkt, dec, recalculateChecksums = true)
                patched.copy(
                    index = idx + 1,
                    comment = (pkt.comment.takeIf { it.isNotBlank() }?.let { "$it | " } ?: "") + "Decrypted (${pkt.decryptionMethod ?: "Key"})"
                )
            }
        }
        val linkType = exported.firstOrNull()?.linkType ?: document.defaultLinkType
        return document.copy(
            defaultLinkType = linkType,
            interfaces = listOf(PcapInterfaceInfo(0, linkType, 65535, "decrypted0")),
            packets = exported
        )
    }

    // -----------------------------------------------------------------------------------------
    // PACKET EDITING & CHECKSUM RECALCULATION
    // -----------------------------------------------------------------------------------------

    fun replacePacketPayload(
        packet: PcapPacketRecord,
        newPayload: ByteArray,
        recalculateChecksums: Boolean = true
    ): PcapPacketRecord {
        val d = dissectPacket(packet)
        if (d.payloadOffset < 0 || d.payloadOffset > packet.rawData.size) {
            return packet.copy(rawData = newPayload, originalLength = newPayload.size)
        }
        val prefix = packet.rawData.copyOfRange(0, d.payloadOffset)
        val combined = ByteArray(prefix.size + newPayload.size)
        System.arraycopy(prefix, 0, combined, 0, prefix.size)
        System.arraycopy(newPayload, 0, combined, prefix.size, newPayload.size)

        if (recalculateChecksums && d.ipHeaderOffset >= 0 && d.ipVersion == 4) {
            updateIpv4AndTransportLengthsAndChecksums(combined, d.ipHeaderOffset)
        }
        return packet.copy(
            rawData = combined,
            originalLength = combined.size
        )
    }

    fun editPacketNetworkFields(
        packet: PcapPacketRecord,
        newSrcIp: String?,
        newDstIp: String?,
        newSrcPort: Int?,
        newDstPort: Int?,
        newTimestampSeconds: Long,
        newTimestampMicros: Long,
        newComment: String,
        recalculateChecksums: Boolean = true
    ): PcapPacketRecord {
        val d = dissectPacket(packet)
        val bytes = packet.rawData.copyOf()
        if (d.ipVersion == 4 && d.ipHeaderOffset >= 0 && d.ipHeaderOffset + 20 <= bytes.size) {
            parseIpv4Bytes(newSrcIp)?.let { System.arraycopy(it, 0, bytes, d.ipHeaderOffset + 12, 4) }
            parseIpv4Bytes(newDstIp)?.let { System.arraycopy(it, 0, bytes, d.ipHeaderOffset + 16, 4) }
        }
        if ((d.ipProtocol == 6 || d.ipProtocol == 17) && d.transportHeaderOffset >= 0 && d.transportHeaderOffset + 4 <= bytes.size) {
            newSrcPort?.takeIf { it in 0..65535 }?.let { writeUShortBE(bytes, d.transportHeaderOffset, it) }
            newDstPort?.takeIf { it in 0..65535 }?.let { writeUShortBE(bytes, d.transportHeaderOffset + 2, it) }
        }
        if (recalculateChecksums && d.ipVersion == 4 && d.ipHeaderOffset >= 0) {
            updateIpv4AndTransportLengthsAndChecksums(bytes, d.ipHeaderOffset)
        }
        return packet.copy(
            rawData = bytes,
            originalLength = bytes.size,
            timestampSeconds = newTimestampSeconds.coerceAtLeast(0L),
            timestampMicros = newTimestampMicros.coerceIn(0L, 999_999L),
            comment = newComment
        )
    }

    fun craftNewIpv4Packet(
        index: Int,
        protocol: String, // "TCP" or "UDP"
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payloadBytes: ByteArray,
        comment: String = ""
    ): PcapPacketRecord {
        val isUdp = protocol.equals("UDP", ignoreCase = true)
        val transportLen = if (isUdp) 8 + payloadBytes.size else 20 + payloadBytes.size
        val ipTotalLen = 20 + transportLen
        val frame = ByteArray(14 + ipTotalLen)

        // Ethernet II header (IPv4)
        frame[0] = 0x00; frame[1] = 0x11; frame[2] = 0x22; frame[3] = 0x33; frame[4] = 0x44; frame[5] = 0x55
        frame[6] = 0x66; frame[7] = 0x77; frame[8] = 0x88.toByte(); frame[9] = 0x99.toByte(); frame[10] = 0xAA.toByte(); frame[11] = 0xBB.toByte()
        frame[12] = 0x08; frame[13] = 0x00

        // IPv4 header
        val ipOff = 14
        frame[ipOff] = 0x45 // v4, IHL=5 (20 bytes)
        writeUShortBE(frame, ipOff + 2, ipTotalLen)
        writeUShortBE(frame, ipOff + 4, (index and 0xFFFF))
        frame[ipOff + 8] = 64 // TTL
        frame[ipOff + 9] = if (isUdp) 17 else 6
        val srcBytes = parseIpv4Bytes(srcIp) ?: byteArrayOf(192.toByte(), 168.toByte(), 1, 10)
        val dstBytes = parseIpv4Bytes(dstIp) ?: byteArrayOf(192.toByte(), 168.toByte(), 1, 1)
        System.arraycopy(srcBytes, 0, frame, ipOff + 12, 4)
        System.arraycopy(dstBytes, 0, frame, ipOff + 16, 4)

        val l4Off = ipOff + 20
        writeUShortBE(frame, l4Off, srcPort.coerceIn(0, 65535))
        writeUShortBE(frame, l4Off + 2, dstPort.coerceIn(0, 65535))
        if (isUdp) {
            writeUShortBE(frame, l4Off + 4, transportLen)
            System.arraycopy(payloadBytes, 0, frame, l4Off + 8, payloadBytes.size)
        } else {
            frame[l4Off + 12] = 0x50 // Data offset = 5 (20 bytes)
            frame[l4Off + 13] = 0x18 // PSH + ACK
            writeUShortBE(frame, l4Off + 14, 65535)
            System.arraycopy(payloadBytes, 0, frame, l4Off + 20, payloadBytes.size)
        }

        updateIpv4AndTransportLengthsAndChecksums(frame, ipOff)
        val nowMs = System.currentTimeMillis()
        return PcapPacketRecord(
            index = index,
            interfaceId = 0,
            linkType = DLT_EN10MB,
            timestampSeconds = nowMs / 1000L,
            timestampMicros = (nowMs % 1000L) * 1000L,
            originalLength = frame.size,
            rawData = frame,
            comment = comment
        )
    }

    fun updateIpv4AndTransportLengthsAndChecksums(frame: ByteArray, ipOff: Int) {
        if (ipOff < 0 || ipOff + 20 > frame.size) return
        val ihl = (frame[ipOff].toInt() and 0x0F) * 4
        if (ihl < 20 || ipOff + ihl > frame.size) return

        val ipTotalLen = (frame.size - ipOff).coerceAtMost(65535)
        writeUShortBE(frame, ipOff + 2, ipTotalLen)
        // Recompute IPv4 header checksum
        frame[ipOff + 10] = 0
        frame[ipOff + 11] = 0
        val ipSum = internetChecksum(frame, ipOff, ihl)
        writeUShortBE(frame, ipOff + 10, ipSum)

        val proto = frame[ipOff + 9].toInt() and 0xFF
        val l4Off = ipOff + ihl
        val l4Len = (ipTotalLen - ihl).coerceAtLeast(0)
        if (l4Off + l4Len > frame.size) return

        if (proto == 17 && l4Len >= 8) { // UDP
            writeUShortBE(frame, l4Off + 4, l4Len)
            frame[l4Off + 6] = 0
            frame[l4Off + 7] = 0
            val udpSum = ipv4TransportChecksum(frame, ipOff, 17, l4Off, l4Len)
            writeUShortBE(frame, l4Off + 6, if (udpSum == 0) 0xFFFF else udpSum)
        } else if (proto == 6 && l4Len >= 20) { // TCP
            frame[l4Off + 16] = 0
            frame[l4Off + 17] = 0
            val tcpSum = ipv4TransportChecksum(frame, ipOff, 6, l4Off, l4Len)
            writeUShortBE(frame, l4Off + 16, tcpSum)
        }
    }

    fun detectWifiSsids(document: PcapCaptureDocument): List<String> {
        val ssids = LinkedHashSet<String>()
        for (pkt in document.packets) {
            if (pkt.linkType == DLT_IEEE802_11 || pkt.linkType == DLT_IEEE802_11_RADIO) {
                val d = dissectPacket(pkt)
                d.wifiSsid?.takeIf { it.isNotBlank() }?.let { ssids.add(it) }
            }
        }
        return ssids.toList()
    }

    fun countEapolHandshakes(document: PcapCaptureDocument): Int {
        var count = 0
        for (pkt in document.packets) {
            if (dissectPacket(pkt).isEapol) count++
        }
        return count
    }

    fun followStreamText(document: PcapCaptureDocument, streamKey: String): String {
        val sb = StringBuilder()
        for (pkt in document.packets) {
            val d = dissectPacket(pkt)
            if (d.streamKey != streamKey) continue
            val chunkBytes = pkt.decryptedBytes ?: if (d.payloadOffset >= 0 && d.payloadLength > 0 && d.payloadOffset + d.payloadLength <= pkt.rawData.size) {
                pkt.rawData.copyOfRange(d.payloadOffset, d.payloadOffset + d.payloadLength)
            } else null
            if (chunkBytes == null || chunkBytes.isEmpty()) continue
            val tag = if (pkt.decryptedBytes != null) "[DECRYPTED]" else "[RAW]"
            sb.append("--- #${pkt.index} ${d.srcAddress}:${d.srcPort ?: 0} -> ${d.dstAddress}:${d.dstPort ?: 0} $tag (${chunkBytes.size}B) ---\n")
            sb.append(extractPrintablePreview(chunkBytes, 0, minOf(chunkBytes.size, 2048)))
            sb.append("\n\n")
        }
        return sb.toString().trim()
    }

    // -----------------------------------------------------------------------------------------
    // CRYPTO PRIMITIVES (Standard RFC 3610 AES-CCM, WPA2 PBKDF2/PRF, TLS 1.2 PRF & TLS 1.3 HKDF)
    // -----------------------------------------------------------------------------------------

    private data class ParsedSslKeyLog(
        val clientRandomToMaster: Map<String, ByteArray>,
        val tls13TrafficSecrets: Map<String, ByteArray>
    )

    private fun parseSslKeyLog(text: String): ParsedSslKeyLog {
        val masters = HashMap<String, ByteArray>()
        val traffic = HashMap<String, ByteArray>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) return@forEach
            val label = parts[0].uppercase(Locale.US)
            val cRand = parts[1].lowercase(Locale.US)
            val secretHex = parts[2]
            if (cRand.length != 64 || !cRand.all { it in "0123456789abcdef" }) return@forEach
            val secretBytes = runCatching { hexToBytes(secretHex) }.getOrNull() ?: return@forEach
            when (label) {
                "CLIENT_RANDOM" -> if (secretBytes.size == 48) masters[cRand] = secretBytes
                "CLIENT_TRAFFIC_SECRET_0", "SERVER_TRAFFIC_SECRET_0" -> traffic["$label:$cRand"] = secretBytes
            }
        }
        return ParsedSslKeyLog(masters, traffic)
    }

    private fun decryptTls13AesGcmRecord(
        trafficSecret: ByteArray,
        cipherSuite: Int,
        recordHeader: ByteArray,
        ciphertextWithTag: ByteArray,
        seqNum: Long
    ): ByteArray? {
        if (ciphertextWithTag.size <= 16) return null
        val isSha384 = cipherSuite == 0x1302 || trafficSecret.size == 48
        val hashAlg = if (isSha384) "HmacSHA384" else "HmacSHA256"
        val keyLen = if (cipherSuite == 0x1302) 32 else 16
        val key = hkdfExpandLabel(hashAlg, trafficSecret, "key", ByteArray(0), keyLen)
        val iv = hkdfExpandLabel(hashAlg, trafficSecret, "iv", ByteArray(0), 12)

        val nonce = iv.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            val seqByte = ((seqNum ushr shift) and 0xFFL).toInt()
            nonce[4 + i] = (nonce[4 + i].toInt() xor seqByte).toByte()
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(recordHeader)
            val rawPlain = cipher.doFinal(ciphertextWithTag)
            // Strip TLS 1.3 trailing zero padding and inner content type byte
            var endIdx = rawPlain.size - 1
            while (endIdx >= 0 && rawPlain[endIdx] == 0.toByte()) endIdx--
            if (endIdx <= 0) rawPlain else rawPlain.copyOfRange(0, endIdx)
        }.getOrNull()
    }

    private fun decryptTls12AesGcmRecord(
        masterSecret: ByteArray,
        clientRandom: ByteArray,
        serverRandom: ByteArray,
        cipherSuite: Int,
        isFromClient: Boolean,
        recordVersionBytes: ByteArray,
        recordPayload: ByteArray,
        seqNum: Long
    ): ByteArray? {
        if (recordPayload.size <= 24) return null // 8B explicit nonce + 16B tag
        val isAes256 = cipherSuite in setOf(0x009D, 0xC02C, 0xC030, 0x009F)
        val keyLen = if (isAes256) 32 else 16
        val keyBlockLen = 2 * keyLen + 8 // clientKey + serverKey + clientIV(4) + serverIV(4)
        val keyBlock = tls12PrfSha256(masterSecret, "key expansion", serverRandom + clientRandom, keyBlockLen)
        val key = if (isFromClient) keyBlock.copyOfRange(0, keyLen) else keyBlock.copyOfRange(keyLen, 2 * keyLen)
        val fixedIv = if (isFromClient) keyBlock.copyOfRange(2 * keyLen, 2 * keyLen + 4) else keyBlock.copyOfRange(2 * keyLen + 4, 2 * keyLen + 8)
        val explicitNonce = recordPayload.copyOfRange(0, 8)
        val ctWithTag = recordPayload.copyOfRange(8, recordPayload.size)
        val nonce = fixedIv + explicitNonce
        val plainLen = ctWithTag.size - 16
        val aad = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .putLong(seqNum)
            .put(23.toByte())
            .put(recordVersionBytes[0])
            .put(recordVersionBytes[1])
            .putShort(plainLen.toShort())
            .array()

        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ctWithTag)
        }.getOrNull()
    }

    private fun hkdfExpandLabel(
        hmacAlg: String,
        secret: ByteArray,
        label: String,
        context: ByteArray,
        length: Int
    ): ByteArray {
        val fullLabel = ("tls13 $label").toByteArray(Charsets.US_ASCII)
        val info = ByteArrayOutputStream().apply {
            write((length ushr 8) and 0xFF)
            write(length and 0xFF)
            write(fullLabel.size and 0xFF)
            write(fullLabel)
            write(context.size and 0xFF)
            write(context)
        }.toByteArray()
        val mac = Mac.getInstance(hmacAlg)
        mac.init(SecretKeySpec(secret, hmacAlg))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOfRange(0, length)
    }

    private fun tls12PrfSha256(secret: ByteArray, label: String, seed: ByteArray, outLen: Int): ByteArray {
        val labelAndSeed = label.toByteArray(Charsets.US_ASCII) + seed
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var a = labelAndSeed
        while (out.size() < outLen) {
            a = mac.doFinal(a)
            mac.reset()
            mac.update(a)
            mac.update(labelAndSeed)
            out.write(mac.doFinal())
        }
        return out.toByteArray().copyOfRange(0, outLen)
    }

    fun deriveWpa2Pmk(passphrase: String, ssid: String): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), ssid.toByteArray(Charsets.UTF_8), 4096, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    fun deriveWpa2Ptk(
        pmk: ByteArray,
        apMac: ByteArray,
        staMac: ByteArray,
        aNonce: ByteArray,
        sNonce: ByteArray
    ): ByteArray {
        val (minMac, maxMac) = if (compareBytesUnsigned(apMac, staMac) < 0) apMac to staMac else staMac to apMac
        val (minNonce, maxNonce) = if (compareBytesUnsigned(aNonce, sNonce) < 0) aNonce to sNonce else sNonce to aNonce
        val data = minMac + maxMac + minNonce + maxNonce
        val label = "Pairwise key expansion".toByteArray(Charsets.US_ASCII)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(pmk, "HmacSHA1"))
        val out = ByteArrayOutputStream()
        for (i in 0 until 4) {
            mac.reset()
            mac.update(label)
            mac.update(0.toByte())
            mac.update(data)
            mac.update(i.toByte())
            out.write(mac.doFinal())
        }
        return out.toByteArray().copyOfRange(0, 64)
    }

    /**
     * Standard IEEE 802.11i AES-128-CCMP decryption using RFC 3610 AES-CCM
     * via AES/CTR/NoPadding (CTR stream) and AES/CBC/NoPadding with zero IV (CBC-MAC).
     */
    private fun decryptDot11Ccmp(frame: ByteArray, dOff: Int, hdrLen: Int, tk: ByteArray): ByteArray? {
        val ccmpOff = dOff + hdrLen
        if (ccmpOff + 16 > frame.size) return null
        val fc = readUShortLE(frame, dOff)
        val isQos = ((fc ushr 4) and 0x08) != 0
        val hasAddr4 = (fc and 0x0300) == 0x0300

        // Extract 6-byte Packet Number (PN5..PN0) from 8-byte CCMP header
        val pn = byteArrayOf(
            frame[ccmpOff + 7],
            frame[ccmpOff + 6],
            frame[ccmpOff + 5],
            frame[ccmpOff + 4],
            frame[ccmpOff + 1],
            frame[ccmpOff + 0]
        )
        val priority: Byte = if (isQos) {
            val qosOff = dOff + if (hasAddr4) 30 else 24
            (frame[qosOff].toInt() and 0x0F).toByte()
        } else 0

        // 13-byte Nonce: Priority (1) || A2 (6) || PN5..PN0 (6)
        val nonce = ByteArray(13)
        nonce[0] = priority
        System.arraycopy(frame, dOff + 10, nonce, 1, 6)
        System.arraycopy(pn, 0, nonce, 7, 6)

        // Construct IEEE 802.11 CCMP AAD
        val aadOut = ByteArrayOutputStream()
        val maskedFc0 = (frame[dOff].toInt() and 0x8F).toByte()
        val maskedFc1 = ((frame[dOff + 1].toInt() and 0xC7) or 0x40).toByte()
        aadOut.write(maskedFc0.toInt())
        aadOut.write(maskedFc1.toInt())
        aadOut.write(frame, dOff + 4, 18) // A1 + A2 + A3
        val seq0 = (frame[dOff + 22].toInt() and 0x0F).toByte()
        aadOut.write(seq0.toInt())
        aadOut.write(0)
        if (hasAddr4) {
            aadOut.write(frame, dOff + 24, 6)
        }
        if (isQos) {
            val qosOff = dOff + if (hasAddr4) 30 else 24
            aadOut.write(frame[qosOff].toInt() and 0x0F)
            aadOut.write(0)
        }
        val aad = aadOut.toByteArray()

        val cipherTextAndMic = frame.copyOfRange(ccmpOff + 8, frame.size)
        return aes128CcmDecrypt(tk, nonce, aad, cipherTextAndMic, micLen = 8)
    }

    fun aes128CcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertextWithMic: ByteArray,
        micLen: Int = 8
    ): ByteArray? {
        if (nonce.size != 13 || ciphertextWithMic.size < micLen) return null
        val ctLen = ciphertextWithMic.size - micLen
        if (ctLen > 0xFFFF) return null
        val keySpec = SecretKeySpec(key, "AES")

        // Counter block A_0 = Flags(0x01) || Nonce(13) || Counter(0x0000)
        val a0 = ByteArray(16)
        a0[0] = 0x01 // L' = L - 1 = 2 - 1 = 1
        System.arraycopy(nonce, 0, a0, 1, 13)

        // Single-pass CTR decryption: block 0 yields S_0 (for MIC unmasking), blocks 1..N decrypt payload
        val ctrCipher = Cipher.getInstance("AES/CTR/NoPadding")
        ctrCipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(a0))
        val ctrInput = ByteArray(16 + ctLen)
        if (ctLen > 0) {
            System.arraycopy(ciphertextWithMic, 0, ctrInput, 16, ctLen)
        }
        val ctrOut = ctrCipher.doFinal(ctrInput)
        val s0 = ctrOut.copyOfRange(0, 16)
        val plain = ctrOut.copyOfRange(16, 16 + ctLen)

        // RFC 3610 CBC-MAC over B_0 || padded(AAD) || padded(Plaintext) using zero-IV AES/CBC/NoPadding
        val macInput = ByteArrayOutputStream(16 + aad.size + ctLen + 32)
        val b0 = ByteArray(16)
        val aadFlag = if (aad.isNotEmpty()) 0x40 else 0x00
        val mPrime = ((micLen - 2) / 2) and 0x07
        b0[0] = (aadFlag or (mPrime shl 3) or 0x01).toByte()
        System.arraycopy(nonce, 0, b0, 1, 13)
        b0[14] = ((ctLen ushr 8) and 0xFF).toByte()
        b0[15] = (ctLen and 0xFF).toByte()
        macInput.write(b0)

        if (aad.isNotEmpty()) {
            val aadTotal = 2 + aad.size
            macInput.write((aad.size ushr 8) and 0xFF)
            macInput.write(aad.size and 0xFF)
            macInput.write(aad)
            val aadRem = aadTotal % 16
            if (aadRem != 0) {
                macInput.write(ByteArray(16 - aadRem))
            }
        }
        if (plain.isNotEmpty()) {
            macInput.write(plain)
            val plainRem = plain.size % 16
            if (plainRem != 0) {
                macInput.write(ByteArray(16 - plainRem))
            }
        }

        val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")
        cbcCipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ByteArray(16)))
        val cbcOut = cbcCipher.doFinal(macInput.toByteArray())
        val tagOffset = cbcOut.size - 16

        for (i in 0 until micLen) {
            val expectedMicByte = (ciphertextWithMic[ctLen + i].toInt() xor s0[i].toInt()).toByte()
            if (cbcOut[tagOffset + i] != expectedMicByte) return null
        }
        return plain
    }

    private fun rc4Crypt(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        val out = ByteArray(input.size)
        var i = 0; j = 0
        for (k in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            val ks = s[(s[i] + s[j]) and 0xFF]
            out[k] = (input[k].toInt() xor ks).toByte()
        }
        return out
    }

    private fun normalizeAesKey(key: ByteArray): ByteArray {
        val targetLen = when {
            key.size <= 16 -> 16
            key.size <= 24 -> 24
            else -> 32
        }
        return ByteArray(targetLen).also {
            System.arraycopy(key, 0, it, 0, minOf(key.size, targetLen))
        }
    }

    // -----------------------------------------------------------------------------------------
    // LOW-LEVEL BINARY & PROTOCOL HELPERS
    // -----------------------------------------------------------------------------------------

    private fun parsePcapNgOptions(bytes: ByteArray, start: Int, end: Int, order: ByteOrder): Map<Int, ByteArray> {
        val map = HashMap<Int, ByteArray>()
        var pos = start
        while (pos + 4 <= end) {
            val code = readUShort(bytes, pos, order)
            val len = readUShort(bytes, pos + 2, order)
            pos += 4
            if (code == 0) break
            if (pos + len > end) break
            map[code] = bytes.copyOfRange(pos, pos + len)
            pos += pad4(len)
        }
        return map
    }

    private fun encodePcapNgOptions(opts: List<Pair<Int, ByteArray>>, order: ByteOrder): ByteArray {
        if (opts.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        for ((code, value) in opts) {
            out.write(shortBytes(code, order))
            out.write(shortBytes(value.size, order))
            out.write(value)
            val pad = pad4(value.size) - value.size
            if (pad > 0) out.write(ByteArray(pad))
        }
        out.write(shortBytes(0, order)) // opt_endofopt
        out.write(shortBytes(0, order))
        return out.toByteArray()
    }

    private fun writePcapNgBlock(out: ByteArrayOutputStream, blockType: Int, body: ByteArray, order: ByteOrder) {
        val pad = pad4(body.size) - body.size
        val totalLen = 12 + body.size + pad
        out.write(intBytes(blockType, order))
        out.write(intBytes(totalLen, order))
        out.write(body)
        if (pad > 0) out.write(ByteArray(pad))
        out.write(intBytes(totalLen, order))
    }

    private fun decodePcapNgTimestamp(tsHigh: Long, tsLow: Long, tsResolByte: Byte): Pair<Long, Long> {
        val combined = (tsHigh shl 32) or (tsLow and 0xFFFFFFFFL)
        val resol = tsResolByte.toInt() and 0xFF
        val isBinary = (resol and 0x80) != 0
        val exp = resol and 0x7F
        val unitsPerSec: Long = if (isBinary) {
            1L shl exp.coerceIn(0, 30)
        } else {
            var p = 1L
            repeat(exp.coerceIn(0, 9)) { p *= 10L }
            p
        }
        if (unitsPerSec <= 0L) return 0L to 0L
        val sec = combined / unitsPerSec
        val rem = combined % unitsPerSec
        val usec = (rem * 1_000_000L) / unitsPerSec
        return sec to usec.coerceIn(0L, 999_999L)
    }

    private fun dot11OffsetOf(pkt: PcapPacketRecord): Int? {
        if (pkt.linkType == DLT_IEEE802_11) return 0
        if (pkt.linkType == DLT_IEEE802_11_RADIO && pkt.rawData.size >= 8) {
            val len = readUShortLE(pkt.rawData, 2)
            if (len in 8..pkt.rawData.size) return len
        }
        return null
    }

    private fun computeDot11HeaderLength(fc: Int): Int {
        var len = 24
        if ((fc and 0x0300) == 0x0300) len += 6 // Addr4
        if (((fc ushr 2) and 0x03) == 2 && ((fc ushr 4) and 0x08) != 0) len += 2 // QoS control
        return len
    }

    private fun extractWifiSsidFromIes(data: ByteArray, start: Int): String? {
        var pos = start
        while (pos + 2 <= data.size) {
            val id = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            pos += 2
            if (pos + len > data.size) break
            if (id == 0 && len > 0) {
                val ssid = String(data, pos, len, Charsets.UTF_8).replace("\u0000", "").trim()
                if (ssid.isNotEmpty()) return ssid
            }
            pos += len
        }
        return null
    }

    private fun parseDnsLayer(data: ByteArray, start: Int, end: Int, layers: MutableList<PcapProtocolLayer>): String? {
        if (start + 12 > end) return null
        val txId = readUShortBE(data, start)
        val flags = readUShortBE(data, start + 2)
        val isResponse = (flags and 0x8000) != 0
        val qdCount = readUShortBE(data, start + 4)
        val anCount = readUShortBE(data, start + 6)
        val qName = if (qdCount > 0) readDnsName(data, start + 12, start, end) else ""
        val summary = if (isResponse) {
            "Response 0x${txId.toString(16)} $qName ($anCount ans)"
        } else {
            "Query 0x${txId.toString(16)} $qName"
        }
        layers.add(
            PcapProtocolLayer(
                title = "DNS",
                summary = summary,
                fields = listOf(
                    PcapLayerField("Transaction ID", "0x${txId.toString(16).padStart(4, '0')}"),
                    PcapLayerField("Direction", if (isResponse) "Response" else "Query"),
                    PcapLayerField("Query Name", qName.ifBlank { "(none)" }),
                    PcapLayerField("Questions / Answers", "$qdCount / $anCount")
                )
            )
        )
        return summary
    }

    private fun readDnsName(data: ByteArray, posStart: Int, dnsBase: Int, end: Int): String {
        val sb = StringBuilder()
        var pos = posStart
        var jumps = 0
        while (pos < end && jumps < 10) {
            val len = data[pos].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0 && pos + 1 < end) {
                val ptr = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos = dnsBase + ptr
                jumps++
                continue
            }
            pos++
            if (pos + len > end) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, pos, len, Charsets.US_ASCII))
            pos += len
        }
        return sb.toString()
    }

    private fun extractTlsClientHelloSni(data: ByteArray, hsStart: Int, hsEnd: Int): String? {
        if (hsStart + 38 >= hsEnd) return null
        var pos = hsStart + 38 // type(1) + len(3) + ver(2) + random(32)
        val sidLen = data[pos].toInt() and 0xFF
        pos += 1 + sidLen
        if (pos + 2 > hsEnd) return null
        val csLen = readUShortBE(data, pos)
        pos += 2 + csLen
        if (pos + 1 > hsEnd) return null
        val compLen = data[pos].toInt() and 0xFF
        pos += 1 + compLen
        if (pos + 2 > hsEnd) return null
        val extTotalLen = readUShortBE(data, pos)
        pos += 2
        val extEnd = minOf(hsEnd, pos + extTotalLen)
        while (pos + 4 <= extEnd) {
            val extType = readUShortBE(data, pos)
            val extLen = readUShortBE(data, pos + 2)
            pos += 4
            if (pos + extLen > extEnd) break
            if (extType == 0 && extLen >= 5) { // server_name
                val nameLen = readUShortBE(data, pos + 3)
                if (pos + 5 + nameLen <= extEnd) {
                    return String(data, pos + 5, nameLen, Charsets.US_ASCII)
                }
            }
            pos += extLen
        }
        return null
    }

    private fun canonicalStreamKey(proto: String, ipA: String, portA: Int, ipB: String, portB: Int): String {
        val a = "$ipA:$portA"
        val b = "$ipB:$portB"
        return if (a <= b) "$proto|$a<->$b" else "$proto|$b<->$a"
    }

    private fun ipv4TransportChecksum(frame: ByteArray, ipOff: Int, proto: Int, l4Off: Int, l4Len: Int): Int {
        var sum = 0L
        // Pseudo header: srcIp (4) + dstIp (4) + zero+proto (2) + l4Len (2)
        sum += readUShortBE(frame, ipOff + 12)
        sum += readUShortBE(frame, ipOff + 14)
        sum += readUShortBE(frame, ipOff + 16)
        sum += readUShortBE(frame, ipOff + 18)
        sum += proto and 0xFF
        sum += l4Len and 0xFFFF
        var pos = l4Off
        val end = l4Off + l4Len
        while (pos + 1 < end) {
            sum += readUShortBE(frame, pos)
            pos += 2
        }
        if (pos < end) {
            sum += (frame[pos].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun internetChecksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var pos = offset
        val end = offset + len
        while (pos + 1 < end) {
            sum += readUShortBE(data, pos)
            pos += 2
        }
        if (pos < end) {
            sum += (data[pos].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    fun formatHexDump(bytes: ByteArray, maxBytes: Int = 2048): String {
        val limit = minOf(bytes.size, maxBytes)
        val sb = StringBuilder()
        var offset = 0
        while (offset < limit) {
            sb.append(String.format(Locale.US, "%04X  ", offset))
            val lineEnd = minOf(offset + 16, limit)
            for (i in offset until offset + 16) {
                if (i < lineEnd) {
                    sb.append(String.format(Locale.US, "%02X ", bytes[i].toInt() and 0xFF))
                } else {
                    sb.append("   ")
                }
                if (i == offset + 7) sb.append(' ')
            }
            sb.append(" |")
            for (i in offset until lineEnd) {
                val c = bytes[i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
            offset += 16
        }
        if (bytes.size > maxBytes) {
            sb.append("... (${bytes.size - maxBytes} more bytes truncated)")
        }
        return sb.toString().trimEnd()
    }

    fun bytesToSpacedHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }

    fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.replace(Regex("[^0-9a-fA-F]"), "")
        require(cleaned.length % 2 == 0) { "Hex input must have an even number of hex digits." }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray, offset: Int, len: Int): String {
        val end = (offset + len).coerceAtMost(bytes.size)
        val sb = StringBuilder((end - offset) * 2)
        for (i in offset until end) {
            sb.append(String.format(Locale.US, "%02x", bytes[i].toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun extractPrintablePreview(bytes: ByteArray, offset: Int, len: Int): String {
        val end = (offset + len).coerceAtMost(bytes.size)
        val sb = StringBuilder()
        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b in 32..126 -> sb.append(b.toChar())
                b == 10 || b == 13 || b == 9 -> sb.append(b.toChar())
                else -> sb.append('.')
            }
        }
        return sb.toString().trim()
    }

    private fun formatMac(data: ByteArray, off: Int): String =
        (0 until 6).joinToString(":") { String.format(Locale.US, "%02x", data[off + it].toInt() and 0xFF) }

    private fun parseMacBytes(mac: String): ByteArray? {
        val parts = mac.split(":")
        if (parts.size != 6) return null
        return runCatching { ByteArray(6) { parts[it].toInt(16).toByte() } }.getOrNull()
    }

    private fun formatIpv4(data: ByteArray, off: Int): String =
        "${data[off].toInt() and 0xFF}.${data[off + 1].toInt() and 0xFF}.${data[off + 2].toInt() and 0xFF}.${data[off + 3].toInt() and 0xFF}"

    private fun parseIpv4Bytes(ip: String?): ByteArray? {
        if (ip.isNullOrBlank()) return null
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        return runCatching {
            ByteArray(4) {
                val v = parts[it].toInt()
                require(v in 0..255)
                v.toByte()
            }
        }.getOrNull()
    }

    private fun formatIpv6(data: ByteArray, off: Int): String =
        (0 until 8).joinToString(":") { String.format(Locale.US, "%x", readUShortBE(data, off + it * 2)) }

    private fun formatTcpFlags(flags: Int): String {
        val list = mutableListOf<String>()
        if ((flags and 0x02) != 0) list.add("SYN")
        if ((flags and 0x10) != 0) list.add("ACK")
        if ((flags and 0x08) != 0) list.add("PSH")
        if ((flags and 0x01) != 0) list.add("FIN")
        if ((flags and 0x04) != 0) list.add("RST")
        if ((flags and 0x20) != 0) list.add("URG")
        return list.ifEmpty { listOf("NONE") }.joinToString(",")
    }

    private fun etherTypeLabel(et: Int): String = when (et) {
        0x0800 -> "IPv4 (0x0800)"
        0x86DD -> "IPv6 (0x86DD)"
        0x0806 -> "ARP (0x0806)"
        0x8100 -> "802.1Q VLAN (0x8100)"
        0x888E -> "EAPOL (0x888E)"
        else -> "0x${et.toString(16).padStart(4, '0')}"
    }

    private fun etherTypeShortName(et: Int): String = when (et) {
        0x0800 -> "IPv4"
        0x86DD -> "IPv6"
        0x0806 -> "ARP"
        0x888E -> "EAPOL"
        else -> "ETH"
    }

    private fun ipProtocolName(proto: Int): String = when (proto) {
        1 -> "ICMP"
        2 -> "IGMP"
        6 -> "TCP"
        17 -> "UDP"
        47 -> "GRE"
        50 -> "ESP"
        58 -> "ICMPv6"
        else -> "IP-$proto"
    }

    private fun compareBytesUnsigned(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val cmp = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    private fun pad4(n: Int): Int = (n + 3) and 3.inv()
    private fun intBytes(v: Int, order: ByteOrder): ByteArray = ByteBuffer.allocate(4).order(order).putInt(v).array()
    private fun shortBytes(v: Int, order: ByteOrder): ByteArray = ByteBuffer.allocate(2).order(order).putShort(v.toShort()).array()
    private fun longBytes(v: Long, order: ByteOrder): ByteArray = ByteBuffer.allocate(8).order(order).putLong(v).array()

    private fun readInt(b: ByteArray, off: Int, order: ByteOrder): Int =
        if (order == ByteOrder.LITTLE_ENDIAN) readIntLE(b, off) else readIntBE(b, off)

    private fun readUInt(b: ByteArray, off: Int, order: ByteOrder): Long =
        readInt(b, off, order).toLong() and 0xFFFFFFFFL

    private fun readUShort(b: ByteArray, off: Int, order: ByteOrder): Int =
        if (order == ByteOrder.LITTLE_ENDIAN) readUShortLE(b, off) else readUShortBE(b, off)

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readUIntBE(b: ByteArray, off: Int): Long = readIntBE(b, off).toLong() and 0xFFFFFFFFL

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readUIntLE(b: ByteArray, off: Int): Long = readIntLE(b, off).toLong() and 0xFFFFFFFFL

    private fun readLongLE(b: ByteArray, off: Int): Long =
        (readUIntLE(b, off)) or (readUIntLE(b, off + 4) shl 32)

    private fun readUShortBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readUShortLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun writeUShortBE(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }
}
