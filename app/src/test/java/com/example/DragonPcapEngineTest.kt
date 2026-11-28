package com.example

import com.example.data.filemanager.CustomPayloadCipherMode
import com.example.data.filemanager.DragonPcapEngine
import com.example.data.filemanager.PcapCaptureDocument
import com.example.data.filemanager.PcapFileFormat
import com.example.data.filemanager.PcapInterfaceInfo
import com.example.data.filemanager.PcapPacketRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DragonPcapEngineTest {

    @Test
    fun `pcap and pcapng round trip preserves packets comments and embedded keylog`() {
        val httpPayload = "GET /api/status HTTP/1.1\r\nHost: dragon.local\r\n\r\n".toByteArray(Charsets.UTF_8)
        val pkt = DragonPcapEngine.craftNewIpv4Packet(
            index = 1,
            protocol = "TCP",
            srcIp = "10.0.0.5",
            dstIp = "10.0.0.1",
            srcPort = 51234,
            dstPort = 80,
            payloadBytes = httpPayload,
            comment = "Initial HTTP check"
        )
        val doc = PcapCaptureDocument(
            format = PcapFileFormat.PCAPNG,
            defaultLinkType = DragonPcapEngine.DLT_EN10MB,
            interfaces = listOf(PcapInterfaceInfo(0, DragonPcapEngine.DLT_EN10MB, 65535, "wlan0")),
            packets = listOf(pkt),
            sectionComment = "Dragon Test Capture",
            embeddedKeyLogText = "CLIENT_RANDOM " + "11".repeat(32) + " " + "22".repeat(48)
        )

        // 1. Classic PCAP (.pcap / .cap) round-trip
        val pcapBytes = DragonPcapEngine.encodeClassicPcap(doc)
        val parsedPcap = DragonPcapEngine.parseBytes(pcapBytes)
        assertEquals(PcapFileFormat.PCAP_LE, parsedPcap.format)
        assertEquals(1, parsedPcap.packets.size)
        val dis = parsedPcap.packets.first().dissect()
        assertEquals("HTTP", dis.protocol)
        assertEquals("10.0.0.5", dis.srcAddress)
        assertEquals("10.0.0.1", dis.dstAddress)
        assertEquals(51234, dis.srcPort)
        assertEquals(80, dis.dstPort)
        assertTrue(dis.info.contains("GET /api/status HTTP/1.1"))

        // 2. PCAPNG round-trip (preserves comment & embedded DSB secrets)
        val ngBytes = DragonPcapEngine.encodePcapNg(doc)
        val parsedNg = DragonPcapEngine.parseBytes(ngBytes)
        assertEquals(PcapFileFormat.PCAPNG, parsedNg.format)
        assertEquals("Dragon Test Capture", parsedNg.sectionComment)
        assertEquals(1, parsedNg.packets.size)
        assertEquals("Initial HTTP check", parsedNg.packets.first().comment)
        assertTrue(parsedNg.embeddedKeyLogText.contains("CLIENT_RANDOM"))
    }

    @Test
    fun `packet editing updates ip ports payload and checksums cleanly`() {
        val original = DragonPcapEngine.craftNewIpv4Packet(
            index = 1,
            protocol = "UDP",
            srcIp = "192.168.1.10",
            dstIp = "192.168.1.20",
            srcPort = 4000,
            dstPort = 5000,
            payloadBytes = "hello-world".toByteArray(Charsets.UTF_8)
        )
        val editedHeaders = DragonPcapEngine.editPacketNetworkFields(
            packet = original,
            newSrcIp = "172.16.0.9",
            newDstIp = "172.16.0.1",
            newSrcPort = 6001,
            newDstPort = 8080,
            newTimestampSeconds = 1700000123L,
            newTimestampMicros = 456789L,
            newComment = "Patched UDP",
            recalculateChecksums = true
        )
        val editedPayload = DragonPcapEngine.replacePacketPayload(
            packet = editedHeaders,
            newPayload = "updated-dragon-payload".toByteArray(Charsets.UTF_8),
            recalculateChecksums = true
        )
        val dis = editedPayload.dissect()
        assertEquals("172.16.0.9", dis.srcAddress)
        assertEquals("172.16.0.1", dis.dstAddress)
        assertEquals(6001, dis.srcPort)
        assertEquals(8080, dis.dstPort)
        assertEquals("Patched UDP", editedPayload.comment)
        assertTrue(dis.info.contains("updated-dragon-payload"))
    }

    @Test
    fun `custom aes gcm and tls 13 keylog decryption recover exact plaintext`() {
        // 1. Custom AES-GCM payload decryption
        val aesKey = ByteArray(16) { (it + 1).toByte() }
        val nonce = ByteArray(12) { (it + 10).toByte() }
        val secretMsg = "SECRET_TELEMETRY_OK_2026".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
        val encPayload = nonce + cipher.doFinal(secretMsg)

        val encPkt = DragonPcapEngine.craftNewIpv4Packet(
            index = 1,
            protocol = "UDP",
            srcIp = "10.1.1.2",
            dstIp = "10.1.1.3",
            srcPort = 9000,
            dstPort = 9001,
            payloadBytes = encPayload
        )
        val doc = PcapCaptureDocument(
            format = PcapFileFormat.PCAP_LE,
            interfaces = listOf(PcapInterfaceInfo(0, DragonPcapEngine.DLT_EN10MB, 65535)),
            packets = listOf(encPkt)
        )
        val customResult = DragonPcapEngine.decryptCustomPayloads(
            document = doc,
            mode = CustomPayloadCipherMode.AES_GCM,
            keyText = DragonPcapEngine.bytesToHex(aesKey, 0, aesKey.size),
            ivText = "",
            keyIsHex = true
        )
        assertEquals(1, customResult.decryptedCount)
        assertEquals("SECRET_TELEMETRY_OK_2026", customResult.updatedDocument.packets.first().decryptedTextPreview)

        // Verify decrypted export document embeds plaintext payload
        val exported = DragonPcapEngine.buildDecryptedExportDocument(customResult.updatedDocument)
        assertTrue(exported.packets.first().dissect().info.contains("SECRET_TELEMETRY_OK_2026"))

        // 2. TLS 1.3 SSLKEYLOGFILE decryption
        val clientRandom = ByteArray(32) { (0xA0 + it).toByte() }
        val trafficSecret = ByteArray(32) { (0x30 + it).toByte() }

        // Build minimal ClientHello record containing clientRandom
        val chBody = ByteArray(40)
        chBody[0] = 0x03; chBody[1] = 0x03
        System.arraycopy(clientRandom, 0, chBody, 2, 32)
        val chRecord = ByteArray(5 + 4 + chBody.size)
        chRecord[0] = 22; chRecord[1] = 0x03; chRecord[2] = 0x03
        chRecord[3] = (((4 + chBody.size) ushr 8) and 0xFF).toByte()
        chRecord[4] = ((4 + chBody.size) and 0xFF).toByte()
        chRecord[5] = 1 // ClientHello
        chRecord[8] = chBody.size.toByte()
        System.arraycopy(chBody, 0, chRecord, 9, chBody.size)

        // Encrypt TLS 1.3 Application Data record (seq 0) with HKDF-derived key/iv
        val tlsKey = testHkdfExpandLabel(trafficSecret, "key", 16)
        val tlsIv = testHkdfExpandLabel(trafficSecret, "iv", 12)
        val innerPlain = "GET /secret HTTP/1.1\r\nHost: secure.test\r\n\r\n".toByteArray(Charsets.UTF_8) + byteArrayOf(23)
        val appLen = innerPlain.size + 16
        val recHdr = byteArrayOf(23, 0x03, 0x03, ((appLen ushr 8) and 0xFF).toByte(), (appLen and 0xFF).toByte())
        val tlsCipher = Cipher.getInstance("AES/GCM/NoPadding")
        tlsCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(tlsKey, "AES"), GCMParameterSpec(128, tlsIv))
        tlsCipher.updateAAD(recHdr)
        val tlsAppRecord = recHdr + tlsCipher.doFinal(innerPlain)

        val p1 = DragonPcapEngine.craftNewIpv4Packet(1, "TCP", "10.0.0.2", "10.0.0.9", 50000, 443, chRecord)
        val p2 = DragonPcapEngine.craftNewIpv4Packet(2, "TCP", "10.0.0.2", "10.0.0.9", 50000, 443, tlsAppRecord)
        val tlsDoc = PcapCaptureDocument(
            format = PcapFileFormat.PCAPNG,
            interfaces = listOf(PcapInterfaceInfo(0, DragonPcapEngine.DLT_EN10MB, 65535)),
            packets = listOf(p1, p2)
        )
        val keyLog = "CLIENT_TRAFFIC_SECRET_0 ${DragonPcapEngine.bytesToHex(clientRandom, 0, 32)} ${DragonPcapEngine.bytesToHex(trafficSecret, 0, 32)}"
        val tlsOut = DragonPcapEngine.decryptWithTlsKeyLog(tlsDoc, keyLog)
        assertEquals(1, tlsOut.decryptedCount)
        assertNotNull(tlsOut.updatedDocument.packets[1].decryptedTextPreview)
        assertTrue(tlsOut.updatedDocument.packets[1].decryptedTextPreview!!.contains("GET /secret HTTP/1.1"))
    }

    @Test
    fun `wep 80211 frame decrypts with known key`() {
        val wepKey = "12345".toByteArray(Charsets.US_ASCII)
        val iv = byteArrayOf(0x11, 0x22, 0x33)
        val llcPlain = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0, 0, 0, 0x08, 0x00) + "WIFI_PLAINTEXT_OK".toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(llcPlain) }.value
        val icv = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt()).array()
        val encrypted = testRc4(iv + wepKey, llcPlain + icv)

        // 24-byte 802.11 Data frame header with Protected bit (0x4000) set
        val hdr = ByteArray(24)
        hdr[0] = 0x08 // Data frame (type=2, subtype=0)
        hdr[1] = 0x41 // ToDS + Protected
        val frame = hdr + iv + byteArrayOf(0x00) + encrypted

        val pkt = PcapPacketRecord(
            index = 1,
            linkType = DragonPcapEngine.DLT_IEEE802_11,
            timestampSeconds = 1700000000L,
            timestampMicros = 0L,
            originalLength = frame.size,
            rawData = frame
        )
        val doc = PcapCaptureDocument(
            format = PcapFileFormat.PCAP_LE,
            defaultLinkType = DragonPcapEngine.DLT_IEEE802_11,
            interfaces = listOf(PcapInterfaceInfo(0, DragonPcapEngine.DLT_IEEE802_11, 65535)),
            packets = listOf(pkt)
        )
        val out = DragonPcapEngine.decryptWifiWepWithKnownKey(doc, "12345")
        assertEquals(1, out.decryptedCount)
        assertTrue(out.updatedDocument.packets.first().decryptedTextPreview!!.contains("WIFI_PLAINTEXT_OK"))
    }

    @Test
    fun `aes128CcmDecrypt matches RFC 3610 packet vector 1`() {
        fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = hex("C0C1C2C3C4C5C6C7C8C9CACBCCCDCECF")
        val nonce = hex("00000003020100A0A1A2A3A4A5")
        val aad = hex("0001020304050607")
        val expectedPlain = hex("08090A0B0C0D0E0F101112131415161718191A1B1C1D1E")
        val ctWithMic = hex("588C979A61C663D2F066D0C2C0F989806D5F6B61DAC38417E8D12CFDF926E0")

        val decrypted = DragonPcapEngine.aes128CcmDecrypt(key, nonce, aad, ctWithMic, micLen = 8)
        assertNotNull(decrypted)
        assertTrue(expectedPlain.contentEquals(decrypted))

        // Tampered MIC must fail authentication
        val tampered = ctWithMic.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex].toInt() xor 0x01).toByte()
        assertEquals(null, DragonPcapEngine.aes128CcmDecrypt(key, nonce, aad, tampered, micLen = 8))
    }

    private fun testHkdfExpandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val fullLabel = ("tls13 $label").toByteArray(Charsets.US_ASCII)
        val info = ByteArrayOutputStream().apply {
            write((length ushr 8) and 0xFF)
            write(length and 0xFF)
            write(fullLabel.size and 0xFF)
            write(fullLabel)
            write(0)
        }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal().copyOfRange(0, length)
    }

    private fun testRc4(key: ByteArray, input: ByteArray): ByteArray {
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
        }
        val out = ByteArray(input.size)
        var i = 0; j = 0
        for (k in input.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[k] = (input[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }
}
