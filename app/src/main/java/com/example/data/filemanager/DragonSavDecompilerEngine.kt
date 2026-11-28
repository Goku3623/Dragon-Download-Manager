package com.example.data.filemanager

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream

enum class SavFormatKind(val badgeLabel: String) {
    UE_GVAS("UE4/5 GVAS"),
    ZLIB_TEXT("ZLIB SAV"),
    GZIP_TEXT("GZIP SAV"),
    BASE64_TEXT("BASE64 SAV"),
    PLAIN_JSON("JSON SAV"),
    PLAIN_XML("XML SAV"),
    PLAIN_INI("INI / CFG SAV"),
    BINARY_STRUCTURED("BINARY SAV")
}

enum class SavFieldValueKind {
    STRING,
    INT32,
    INT64,
    FLOAT32,
    FLOAT64,
    BOOLEAN,
    BYTES_HEX
}

data class SavDecompiledField(
    val id: String,
    val name: String,
    val typeLabel: String,
    val kind: SavFieldValueKind,
    val value: String,
    val byteOffset: Int = -1,
    val maxByteLength: Int = -1,
    val rawPropertyIndex: Int = -1
)

data class SavDecompileResult(
    val format: SavFormatKind,
    val headerSummary: List<Pair<String, String>>,
    val fields: List<SavDecompiledField>,
    val decompiledText: String,
    val rawBytes: ByteArray,
    val gvasDocument: GvasDocument? = null
)

data class GvasPropertyRecord(
    val name: String,
    val typeName: String,
    val valueKind: SavFieldValueKind,
    var value: String,
    val enumTypeName: String = "",
    val rawHeaderPrefix: ByteArray = ByteArray(0),
    val rawPayloadBytes: ByteArray = ByteArray(0),
    val isEditable: Boolean = true
)

data class GvasDocument(
    val headerRawPrefix: ByteArray,
    val saveGameVersion: Int,
    val packageVersion: Int,
    val engineVersionText: String,
    val saveGameClassName: String,
    val properties: MutableList<GvasPropertyRecord>,
    val trailingBytes: ByteArray
)

/**
 * Professional Universal `.sav` File Decompiler, Structured Editor & Recompiler Engine.
 *
 * Automatically detects and decompiles:
 * 1. Unreal Engine 4/5 GVAS binary save files (`GVAS` header + full FProperty tree + lossless binary recompilation)
 * 2. Zlib / GZip / Base64-wrapped JSON, XML, or INI game saves (decompress -> edit -> recompress cleanly)
 * 3. Plain-text JSON, XML, and INI/CFG save files
 * 4. Generic binary `.sav` files (PUBG/Unity/Console saves: length-prefixed & null-terminated keys + Int32/Float32 values + Hex)
 */
object DragonSavDecompilerEngine {

    fun decompileSav(bytes: ByteArray): SavDecompileResult {
        if (bytes.isEmpty()) {
            return SavDecompileResult(
                format = SavFormatKind.PLAIN_INI,
                headerSummary = listOf("Size" to "0 B"),
                fields = emptyList(),
                decompiledText = "",
                rawBytes = bytes
            )
        }

        // 1. Check Unreal Engine GVAS magic ("GVAS")
        if (bytes.size >= 32 &&
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'V'.code.toByte() &&
            bytes[2] == 'A'.code.toByte() &&
            bytes[3] == 'S'.code.toByte()
        ) {
            parseGvas(bytes)?.let { gvas ->
                return buildGvasDecompileResult(bytes, gvas)
            }
        }

        // 2. Check GZip header (0x1F 0x8B)
        if (bytes.size >= 18 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
            val inflated = runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrNull()
            if (inflated != null && looksLikePrintableText(inflated)) {
                val text = String(inflated, Charsets.UTF_8)
                return buildTextWrappedResult(SavFormatKind.GZIP_TEXT, bytes, text)
            }
        }

        // 3. Check Zlib header (0x78 0x01 / 0x78 0x5E / 0x78 0x9C / 0x78 0xDA)
        if (bytes.size >= 8 && (bytes[0].toInt() and 0xFF) == 0x78 &&
            ((bytes[1].toInt() and 0xFF) in setOf(0x01, 0x5E, 0x9C, 0xDA))
        ) {
            val inflated = runCatching {
                InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrNull()
            if (inflated != null && looksLikePrintableText(inflated)) {
                val text = String(inflated, Charsets.UTF_8)
                return buildTextWrappedResult(SavFormatKind.ZLIB_TEXT, bytes, text)
            }
        }

        // 4. Plain text (JSON / XML / INI / Base64-wrapped text)
        if (looksLikePrintableText(bytes)) {
            val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            val trimmed = text.trim()

            // Check Base64-wrapped JSON/XML/INI
            if (trimmed.length >= 16 &&
                !trimmed.startsWith("{") &&
                !trimmed.startsWith("[") &&
                !trimmed.startsWith("<") &&
                trimmed.matches(Regex("^[A-Za-z0-9+/=\\r\\n]+$"))
            ) {
                val decoded = runCatching { Base64.decode(trimmed, Base64.DEFAULT) }.getOrNull()
                if (decoded != null && decoded.size >= 8 && looksLikePrintableText(decoded)) {
                    val decodedStr = String(decoded, Charsets.UTF_8)
                    val dt = decodedStr.trim()
                    if (dt.startsWith("{") || dt.startsWith("[") || dt.startsWith("<") || dt.contains("=")) {
                        return buildTextWrappedResult(SavFormatKind.BASE64_TEXT, bytes, decodedStr)
                    }
                }
            }

            return when {
                trimmed.startsWith("{") || trimmed.startsWith("[") -> {
                    val pretty = DragonDocumentEngine.formatJsonPretty(trimmed).first ?: text
                    SavDecompileResult(
                        format = SavFormatKind.PLAIN_JSON,
                        headerSummary = listOf("Format" to "JSON Save", "Size" to "${bytes.size} bytes"),
                        fields = extractJsonFields(trimmed),
                        decompiledText = pretty,
                        rawBytes = bytes
                    )
                }
                trimmed.startsWith("<") -> {
                    val pretty = DragonDocumentEngine.formatXmlPretty(trimmed).first ?: text
                    SavDecompileResult(
                        format = SavFormatKind.PLAIN_XML,
                        headerSummary = listOf("Format" to "XML Save", "Size" to "${bytes.size} bytes"),
                        fields = extractIniOrXmlFields(pretty),
                        decompiledText = pretty,
                        rawBytes = bytes
                    )
                }
                else -> {
                    SavDecompileResult(
                        format = SavFormatKind.PLAIN_INI,
                        headerSummary = listOf("Format" to "Text / INI Save", "Size" to "${bytes.size} bytes"),
                        fields = extractIniOrXmlFields(text),
                        decompiledText = text,
                        rawBytes = bytes
                    )
                }
            }
        }

        // 5. Generic Binary .sav (Unreal/Unity/PUBG Active.sav/Console binary save)
        val fields = extractGenericBinarySavFields(bytes)
        val summaryText = buildString {
            appendLine("// Decompiled Binary .SAV Structure (${bytes.size} bytes)")
            appendLine("// Detected ${fields.size} structured record(s)")
            appendLine()
            fields.forEach { f ->
                appendLine("[0x%06X] (%s) %s = %s".format(f.byteOffset, f.typeLabel, f.name, f.value))
            }
        }
        return SavDecompileResult(
            format = SavFormatKind.BINARY_STRUCTURED,
            headerSummary = listOf(
                "Format" to "Binary .SAV",
                "Size" to "${bytes.size} bytes",
                "Magic" to bytes.take(4).joinToString(" ") { "%02X".format(it) },
                "Records" to "${fields.size}"
            ),
            fields = fields,
            decompiledText = summaryText,
            rawBytes = bytes
        )
    }

    // =========================================================================
    // RECOMPILATION / SAVING FOR ALL .SAV FORMATS
    // =========================================================================

    fun recompileFromEditedText(
        original: SavDecompileResult,
        editedText: String
    ): Result<ByteArray> {
        return runCatching {
            when (original.format) {
                SavFormatKind.GZIP_TEXT -> {
                    val baos = ByteArrayOutputStream()
                    GZIPOutputStream(baos).use { it.write(editedText.toByteArray(Charsets.UTF_8)) }
                    baos.toByteArray()
                }
                SavFormatKind.ZLIB_TEXT -> {
                    val baos = ByteArrayOutputStream()
                    DeflaterOutputStream(baos, Deflater(Deflater.DEFAULT_COMPRESSION)).use {
                        it.write(editedText.toByteArray(Charsets.UTF_8))
                    }
                    baos.toByteArray()
                }
                SavFormatKind.BASE64_TEXT -> {
                    Base64.encodeToString(editedText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                        .toByteArray(Charsets.UTF_8)
                }
                SavFormatKind.PLAIN_JSON, SavFormatKind.PLAIN_XML, SavFormatKind.PLAIN_INI -> {
                    editedText.toByteArray(Charsets.UTF_8)
                }
                SavFormatKind.UE_GVAS -> {
                    // Apply any property edits parsed from JSON back into GVAS binary!
                    val doc = original.gvasDocument
                        ?: throw IllegalStateException("GVAS structure unavailable")
                    val root = JSONObject(editedText)
                    val propsObj = root.optJSONObject("properties")
                    if (propsObj != null) {
                        doc.properties.forEach { prop ->
                            if (prop.isEditable && propsObj.has(prop.name)) {
                                prop.value = propsObj.opt(prop.name)?.toString().orEmpty()
                            }
                        }
                    }
                    serializeGvas(doc)
                }
                SavFormatKind.BINARY_STRUCTURED -> original.rawBytes
            }
        }
    }

    fun applyFieldEdit(
        current: SavDecompileResult,
        field: SavDecompiledField,
        newValue: String
    ): Result<SavDecompileResult> {
        return runCatching {
            when (current.format) {
                SavFormatKind.UE_GVAS -> {
                    val doc = current.gvasDocument ?: throw IllegalStateException("Missing GVAS doc")
                    val prop = doc.properties.getOrNull(field.rawPropertyIndex)
                        ?: throw IllegalArgumentException("Property not found")
                    validateTypedValue(prop.valueKind, newValue)
                    prop.value = newValue
                    val rebuilt = serializeGvas(doc)
                    decompileSav(rebuilt)
                }
                SavFormatKind.PLAIN_JSON, SavFormatKind.ZLIB_TEXT, SavFormatKind.GZIP_TEXT, SavFormatKind.BASE64_TEXT -> {
                    val updatedText = patchTextKeyValue(current.decompiledText, field, newValue)
                    val recompiled = recompileFromEditedText(current, updatedText).getOrThrow()
                    decompileSav(recompiled)
                }
                SavFormatKind.PLAIN_INI, SavFormatKind.PLAIN_XML -> {
                    val updatedText = patchTextKeyValue(current.decompiledText, field, newValue)
                    val recompiled = updatedText.toByteArray(Charsets.UTF_8)
                    decompileSav(recompiled)
                }
                SavFormatKind.BINARY_STRUCTURED -> {
                    val patched = patchBinarySavField(current.rawBytes, field, newValue)
                    decompileSav(patched)
                }
            }
        }
    }

    // =========================================================================
    // UNREAL ENGINE 4/5 GVAS PARSER & BINARY SERIALIZER
    // =========================================================================

    private fun parseGvas(bytes: ByteArray): GvasDocument? {
        return runCatching {
            var pos = 4 // skip "GVAS"
            val saveGameVersion = readIntLE(bytes, pos); pos += 4
            val packageVersion = readIntLE(bytes, pos); pos += 4
            val engMajor = readShortLE(bytes, pos); pos += 2
            val engMinor = readShortLE(bytes, pos); pos += 2
            val engPatch = readShortLE(bytes, pos); pos += 2
            val engBuild = readIntLE(bytes, pos); pos += 4
            val (branch, afterBranch) = readUeFString(bytes, pos) ?: return null
            pos = afterBranch
            val customFormatVer = readIntLE(bytes, pos); pos += 4
            val customVerCount = readIntLE(bytes, pos); pos += 4
            if (customVerCount < 0 || customVerCount > 5000) return null
            // Each custom version entry = 16-byte GUID + 4-byte int32 version
            pos += customVerCount * 20
            if (pos >= bytes.size) return null
            val (saveClass, afterClass) = readUeFString(bytes, pos) ?: return null
            pos = afterClass

            val headerRawPrefix = bytes.copyOfRange(0, pos)
            val properties = mutableListOf<GvasPropertyRecord>()

            var guard = 0
            while (pos < bytes.size && guard++ < 4000) {
                val propStart = pos
                val (propName, afterName) = readUeFString(bytes, pos) ?: break
                pos = afterName
                if (propName == "None") {
                    // End of root property list
                    val trailing = bytes.copyOfRange(propStart, bytes.size)
                    return GvasDocument(
                        headerRawPrefix = headerRawPrefix,
                        saveGameVersion = saveGameVersion,
                        packageVersion = packageVersion,
                        engineVersionText = "UE $engMajor.$engMinor.$engPatch-$engBuild ($branch)",
                        saveGameClassName = saveClass,
                        properties = properties,
                        trailingBytes = trailing
                    )
                }

                val (typeName, afterType) = readUeFString(bytes, pos) ?: break
                pos = afterType
                if (pos + 8 > bytes.size) break
                val valueSize = readLongLE(bytes, pos).toInt()
                pos += 8
                if (valueSize < 0 || pos + valueSize + 1 > bytes.size) {
                    pos = propStart
                    break
                }

                when (typeName) {
                    "IntProperty" -> {
                        if (pos + 5 > bytes.size) break
                        pos += 1 // null guid marker
                        val v = readIntLE(bytes, pos); pos += 4
                        properties.add(GvasPropertyRecord(propName, typeName, SavFieldValueKind.INT32, v.toString()))
                    }
                    "UInt32Property" -> {
                        if (pos + 5 > bytes.size) break
                        pos += 1
                        val v = readIntLE(bytes, pos).toUInt(); pos += 4
                        properties.add(GvasPropertyRecord(propName, typeName, SavFieldValueKind.INT64, v.toString()))
                    }
                    "Int64Property" -> {
                        if (pos + 9 > bytes.size) break
                        pos += 1
                        val v = readLongLE(bytes, pos); pos += 8
                        properties.add(GvasPropertyRecord(propName, typeName, SavFieldValueKind.INT64, v.toString()))
                    }
                    "FloatProperty" -> {
                        if (pos + 5 > bytes.size) break
                        pos += 1
                        val v = java.lang.Float.intBitsToFloat(readIntLE(bytes, pos)); pos += 4
                        properties.add(GvasPropertyRecord(propName, typeName, SavFieldValueKind.FLOAT32, v.toString()))
                    }
                    "DoubleProperty" -> {
                        if (pos + 9 > bytes.size) break
                        pos += 1
                        val v = java.lang.Double.longBitsToDouble(readLongLE(bytes, pos)); pos += 8
                        properties.add(GvasPropertyRecord(propName, typeName, SavFieldValueKind.FLOAT64, v.toString()))
                    }
                    "BoolProperty" -> {
                        if (pos + 2 > bytes.size) break
                        val boolVal = bytes[pos] != 0.toByte()
                        pos += 2 // 1 byte bool + 1 byte null guid marker
                        properties.add(GvasPropertyRecord(propName, typeName, SavFieldValueKind.BOOLEAN, boolVal.toString()))
                    }
                    "StrProperty", "NameProperty" -> {
                        if (pos + 1 + valueSize > bytes.size) break
                        pos += 1 // null guid marker
                        val strVal = readUeFString(bytes, pos)?.first.orEmpty()
                        pos += valueSize
                        properties.add(GvasPropertyRecord(propName, typeName, SavFieldValueKind.STRING, strVal))
                    }
                    "EnumProperty" -> {
                        val (enumType, afterEnumType) = readUeFString(bytes, pos) ?: break
                        pos = afterEnumType
                        if (pos + 1 + valueSize > bytes.size) break
                        pos += 1 // null guid marker
                        val enumVal = readUeFString(bytes, pos)?.first.orEmpty()
                        pos += valueSize
                        properties.add(
                            GvasPropertyRecord(
                                name = propName,
                                typeName = typeName,
                                valueKind = SavFieldValueKind.STRING,
                                value = enumVal,
                                enumTypeName = enumType
                            )
                        )
                    }
                    "ByteProperty" -> {
                        val (enumType, afterEnumType) = readUeFString(bytes, pos) ?: break
                        pos = afterEnumType
                        if (pos + 1 + valueSize > bytes.size) break
                        pos += 1
                        val v = if (enumType == "None" && valueSize == 1) {
                            (bytes[pos].toInt() and 0xFF).toString()
                        } else {
                            readUeFString(bytes, pos)?.first ?: bytes.copyOfRange(pos, pos + valueSize).joinToString(" ") { "%02X".format(it) }
                        }
                        pos += valueSize
                        properties.add(
                            GvasPropertyRecord(
                                name = propName,
                                typeName = typeName,
                                valueKind = if (enumType == "None" && valueSize == 1) SavFieldValueKind.INT32 else SavFieldValueKind.STRING,
                                value = v,
                                enumTypeName = enumType
                            )
                        )
                    }
                    "StructProperty" -> {
                        // Preserve exact struct header & payload for lossless round-trip
                        val headerStart = pos
                        val (_, afterStructType) = readUeFString(bytes, pos) ?: break
                        pos = afterStructType + 17 // 16-byte struct GUID + 1-byte null terminator
                        if (pos + valueSize > bytes.size) {
                            pos = propStart
                            break
                        }
                        val prefix = bytes.copyOfRange(headerStart, pos)
                        val payload = bytes.copyOfRange(pos, pos + valueSize)
                        pos += valueSize
                        properties.add(
                            GvasPropertyRecord(
                                name = propName,
                                typeName = typeName,
                                valueKind = SavFieldValueKind.BYTES_HEX,
                                value = "[Struct ${payload.size} B]",
                                rawHeaderPrefix = prefix,
                                rawPayloadBytes = payload,
                                isEditable = false
                            )
                        )
                    }
                    "ArrayProperty" -> {
                        val headerStart = pos
                        val (_, afterInnerType) = readUeFString(bytes, pos) ?: break
                        pos = afterInnerType + 1 // null terminator
                        if (pos + valueSize > bytes.size) {
                            pos = propStart
                            break
                        }
                        val prefix = bytes.copyOfRange(headerStart, pos)
                        val payload = bytes.copyOfRange(pos, pos + valueSize)
                        pos += valueSize
                        properties.add(
                            GvasPropertyRecord(
                                name = propName,
                                typeName = typeName,
                                valueKind = SavFieldValueKind.BYTES_HEX,
                                value = "[Array ${payload.size} B]",
                                rawHeaderPrefix = prefix,
                                rawPayloadBytes = payload,
                                isEditable = false
                            )
                        )
                    }
                    else -> {
                        // Stop parsing unknown complex property and keep the rest verbatim in trailingBytes!
                        pos = propStart
                        break
                    }
                }
            }

            if (properties.isEmpty()) return null
            GvasDocument(
                headerRawPrefix = headerRawPrefix,
                saveGameVersion = saveGameVersion,
                packageVersion = packageVersion,
                engineVersionText = "UE $engMajor.$engMinor.$engPatch-$engBuild ($branch)",
                saveGameClassName = saveClass,
                properties = properties,
                trailingBytes = bytes.copyOfRange(pos, bytes.size)
            )
        }.getOrNull()
    }

    private fun serializeGvas(doc: GvasDocument): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(doc.headerRawPrefix)

        for (prop in doc.properties) {
            writeUeFString(out, prop.name)
            writeUeFString(out, prop.typeName)
            when (prop.typeName) {
                "IntProperty" -> {
                    writeLongLE(out, 4L)
                    out.write(0)
                    writeIntLE(out, prop.value.trim().toInt())
                }
                "UInt32Property" -> {
                    writeLongLE(out, 4L)
                    out.write(0)
                    writeIntLE(out, prop.value.trim().toUInt().toInt())
                }
                "Int64Property" -> {
                    writeLongLE(out, 8L)
                    out.write(0)
                    writeLongLE(out, prop.value.trim().toLong())
                }
                "FloatProperty" -> {
                    writeLongLE(out, 4L)
                    out.write(0)
                    writeIntLE(out, java.lang.Float.floatToRawIntBits(prop.value.trim().toFloat()))
                }
                "DoubleProperty" -> {
                    writeLongLE(out, 8L)
                    out.write(0)
                    writeLongLE(out, java.lang.Double.doubleToRawLongBits(prop.value.trim().toDouble()))
                }
                "BoolProperty" -> {
                    writeLongLE(out, 0L)
                    out.write(if (prop.value.trim().equals("true", ignoreCase = true)) 1 else 0)
                    out.write(0)
                }
                "StrProperty", "NameProperty" -> {
                    val strBytes = encodeUeFStringBytes(prop.value)
                    writeLongLE(out, strBytes.size.toLong())
                    out.write(0)
                    out.write(strBytes)
                }
                "EnumProperty" -> {
                    val valBytes = encodeUeFStringBytes(prop.value)
                    writeLongLE(out, valBytes.size.toLong())
                    writeUeFString(out, prop.enumTypeName)
                    out.write(0)
                    out.write(valBytes)
                }
                "ByteProperty" -> {
                    if (prop.enumTypeName == "None") {
                        writeLongLE(out, 1L)
                        writeUeFString(out, "None")
                        out.write(0)
                        out.write(prop.value.trim().toInt() and 0xFF)
                    } else {
                        val valBytes = encodeUeFStringBytes(prop.value)
                        writeLongLE(out, valBytes.size.toLong())
                        writeUeFString(out, prop.enumTypeName)
                        out.write(0)
                        out.write(valBytes)
                    }
                }
                else -> {
                    writeLongLE(out, prop.rawPayloadBytes.size.toLong())
                    out.write(prop.rawHeaderPrefix)
                    out.write(prop.rawPayloadBytes)
                }
            }
        }

        out.write(doc.trailingBytes)
        return out.toByteArray()
    }

    private fun buildGvasDecompileResult(rawBytes: ByteArray, doc: GvasDocument): SavDecompileResult {
        val fields = doc.properties.mapIndexed { idx, p ->
            SavDecompiledField(
                id = "gvas_${idx}_${p.name}",
                name = p.name,
                typeLabel = p.typeName.removeSuffix("Property"),
                kind = p.valueKind,
                value = p.value,
                rawPropertyIndex = idx
            )
        }
        val json = JSONObject().apply {
            put("format", "Unreal Engine GVAS")
            put("saveGameVersion", doc.saveGameVersion)
            put("packageVersion", doc.packageVersion)
            put("engine", doc.engineVersionText)
            put("saveGameClass", doc.saveGameClassName)
            val propsObj = JSONObject()
            doc.properties.forEach { p ->
                when (p.valueKind) {
                    SavFieldValueKind.BOOLEAN -> propsObj.put(p.name, p.value.equals("true", ignoreCase = true))
                    SavFieldValueKind.INT32 -> propsObj.put(p.name, p.value.toIntOrNull() ?: p.value)
                    SavFieldValueKind.INT64 -> propsObj.put(p.name, p.value.toLongOrNull() ?: p.value)
                    SavFieldValueKind.FLOAT32, SavFieldValueKind.FLOAT64 -> propsObj.put(p.name, p.value.toDoubleOrNull() ?: p.value)
                    else -> propsObj.put(p.name, p.value)
                }
            }
            put("properties", propsObj)
        }
        return SavDecompileResult(
            format = SavFormatKind.UE_GVAS,
            headerSummary = listOf(
                "Engine" to doc.engineVersionText,
                "Class" to doc.saveGameClassName.substringAfterLast('.'),
                "SaveVer" to "v${doc.saveGameVersion}",
                "Properties" to "${doc.properties.size}"
            ),
            fields = fields,
            decompiledText = json.toString(2),
            rawBytes = rawBytes,
            gvasDocument = doc
        )
    }

    // =========================================================================
    // TEXT / JSON / XML / INI / BINARY FIELD EXTRACTORS & PATCHERS
    // =========================================================================

    private fun buildTextWrappedResult(format: SavFormatKind, rawBytes: ByteArray, decodedText: String): SavDecompileResult {
        val trimmed = decodedText.trim()
        val pretty = when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> DragonDocumentEngine.formatJsonPretty(trimmed).first ?: decodedText
            trimmed.startsWith("<") -> DragonDocumentEngine.formatXmlPretty(trimmed).first ?: decodedText
            else -> decodedText
        }
        val fields = when {
            trimmed.startsWith("{") || trimmed.startsWith("[") -> extractJsonFields(trimmed)
            else -> extractIniOrXmlFields(pretty)
        }
        return SavDecompileResult(
            format = format,
            headerSummary = listOf(
                "Container" to format.badgeLabel,
                "Compressed" to "${rawBytes.size} B",
                "Decompiled" to "${decodedText.length} chars",
                "Fields" to "${fields.size}"
            ),
            fields = fields,
            decompiledText = pretty,
            rawBytes = rawBytes
        )
    }

    private fun extractJsonFields(jsonText: String): List<SavDecompiledField> {
        val out = mutableListOf<SavDecompiledField>()
        runCatching {
            val trimmed = jsonText.trim()
            if (trimmed.startsWith("{")) {
                flattenJsonObject(JSONObject(trimmed), "", out)
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until minOf(arr.length(), 100)) {
                    val v = arr.opt(i)
                    if (v is JSONObject) flattenJsonObject(v, "[$i]", out)
                    else out.add(SavDecompiledField("json_$i", "[$i]", inferJsonKindLabel(v), inferJsonKind(v), v?.toString().orEmpty()))
                }
            }
        }
        return out
    }

    private fun flattenJsonObject(obj: JSONObject, prefix: String, out: MutableList<SavDecompiledField>) {
        if (out.size >= 400) return
        val keys = obj.keys()
        while (keys.hasNext() && out.size < 400) {
            val k = keys.next()
            val fullKey = if (prefix.isEmpty()) k else "$prefix.$k"
            val v = obj.opt(k)
            when (v) {
                is JSONObject -> flattenJsonObject(v, fullKey, out)
                is JSONArray -> {
                    out.add(SavDecompiledField("json_$fullKey", fullKey, "Array(${v.length()})", SavFieldValueKind.STRING, v.toString()))
                }
                else -> {
                    out.add(SavDecompiledField("json_$fullKey", fullKey, inferJsonKindLabel(v), inferJsonKind(v), v?.toString().orEmpty()))
                }
            }
        }
    }

    private fun inferJsonKind(v: Any?): SavFieldValueKind = when (v) {
        is Boolean -> SavFieldValueKind.BOOLEAN
        is Int -> SavFieldValueKind.INT32
        is Long -> SavFieldValueKind.INT64
        is Float -> SavFieldValueKind.FLOAT32
        is Double -> SavFieldValueKind.FLOAT64
        else -> SavFieldValueKind.STRING
    }

    private fun inferJsonKindLabel(v: Any?): String = when (v) {
        is Boolean -> "Bool"
        is Int -> "Int"
        is Long -> "Long"
        is Float, is Double -> "Number"
        else -> "String"
    }

    private fun extractIniOrXmlFields(text: String): List<SavDecompiledField> {
        val out = mutableListOf<SavDecompiledField>()
        var currentSection = ""
        text.lines().forEachIndexed { idx, rawLine ->
            if (out.size >= 400) return@forEachIndexed
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEachIndexed
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.substring(1, line.length - 1)
                return@forEachIndexed
            }
            val eqIdx = line.indexOf('=')
            if (eqIdx > 0 && !line.startsWith("<")) {
                val key = line.substring(0, eqIdx).trim()
                val value = line.substring(eqIdx + 1).trim()
                val fullName = if (currentSection.isNotEmpty()) "$currentSection.$key" else key
                val kind = when {
                    value.equals("true", true) || value.equals("false", true) -> SavFieldValueKind.BOOLEAN
                    value.toIntOrNull() != null -> SavFieldValueKind.INT32
                    value.toFloatOrNull() != null -> SavFieldValueKind.FLOAT32
                    else -> SavFieldValueKind.STRING
                }
                out.add(SavDecompiledField("ini_${idx}_$fullName", fullName, kind.name, kind, value, byteOffset = idx))
            }
        }
        return out
    }

    private fun patchTextKeyValue(text: String, field: SavDecompiledField, newValue: String): String {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            setJsonPath(root, field.name.split('.'), field.kind, newValue)
            return root.toString(2)
        }
        val lines = text.lines().toMutableList()
        if (field.byteOffset in lines.indices) {
            val orig = lines[field.byteOffset]
            val eqIdx = orig.indexOf('=')
            if (eqIdx > 0) {
                lines[field.byteOffset] = orig.substring(0, eqIdx + 1) + newValue
            }
        }
        return lines.joinToString("\n")
    }

    private fun setJsonPath(obj: JSONObject, parts: List<String>, kind: SavFieldValueKind, newValue: String) {
        var current = obj
        for (i in 0 until parts.lastIndex) {
            val next = current.optJSONObject(parts[i]) ?: return
            current = next
        }
        val leaf = parts.last()
        when (kind) {
            SavFieldValueKind.BOOLEAN -> current.put(leaf, newValue.trim().equals("true", ignoreCase = true))
            SavFieldValueKind.INT32 -> current.put(leaf, newValue.trim().toIntOrNull() ?: newValue)
            SavFieldValueKind.INT64 -> current.put(leaf, newValue.trim().toLongOrNull() ?: newValue)
            SavFieldValueKind.FLOAT32, SavFieldValueKind.FLOAT64 -> current.put(leaf, newValue.trim().toDoubleOrNull() ?: newValue)
            else -> current.put(leaf, newValue)
        }
    }

    private fun extractGenericBinarySavFields(bytes: ByteArray): List<SavDecompiledField> {
        val out = mutableListOf<SavDecompiledField>()
        val rawStrings = DragonDocumentEngine.extractSavStringsAndValues(bytes, maxEntries = 450)
        rawStrings.forEachIndexed { idx, entry ->
            out.add(
                SavDecompiledField(
                    id = "bin_str_${entry.offset}_$idx",
                    name = "String @ 0x${Integer.toHexString(entry.offset)}",
                    typeLabel = "ASCII(${entry.length}B)",
                    kind = SavFieldValueKind.STRING,
                    value = entry.text,
                    byteOffset = entry.offset,
                    maxByteLength = entry.length
                )
            )
            val intOff = entry.trailingIntOffset
            val intVal = entry.trailingInt32
            if (intOff != null && intVal != null) {
                out.add(
                    SavDecompiledField(
                        id = "bin_int_${intOff}_$idx",
                        name = "${entry.text.take(28)} -> Int32",
                        typeLabel = "Int32 LE",
                        kind = SavFieldValueKind.INT32,
                        value = intVal.toString(),
                        byteOffset = intOff,
                        maxByteLength = 4
                    )
                )
                val flt = java.lang.Float.intBitsToFloat(intVal)
                if (!flt.isNaN() && !flt.isInfinite() && kotlin.math.abs(flt) in 0.001f..1_000_000f) {
                    out.add(
                        SavDecompiledField(
                            id = "bin_flt_${intOff}_$idx",
                            name = "${entry.text.take(28)} -> Float32",
                            typeLabel = "Float32 LE",
                            kind = SavFieldValueKind.FLOAT32,
                            value = flt.toString(),
                            byteOffset = intOff,
                            maxByteLength = 4
                        )
                    )
                }
            }
        }
        return out
    }

    private fun patchBinarySavField(bytes: ByteArray, field: SavDecompiledField, newValue: String): ByteArray {
        val copy = bytes.copyOf()
        val off = field.byteOffset
        if (off < 0 || off >= copy.size) throw IllegalArgumentException("Invalid offset")
        when (field.kind) {
            SavFieldValueKind.INT32 -> {
                val n = newValue.trim().toInt()
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
                System.arraycopy(b, 0, copy, off, 4)
            }
            SavFieldValueKind.FLOAT32 -> {
                val f = newValue.trim().toFloat()
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array()
                System.arraycopy(b, 0, copy, off, 4)
            }
            SavFieldValueKind.STRING -> {
                val maxLen = field.maxByteLength.coerceAtLeast(1)
                val encoded = newValue.toByteArray(Charsets.ISO_8859_1)
                val writeLen = minOf(encoded.size, maxLen)
                for (j in 0 until maxLen) {
                    if (off + j < copy.size) {
                        copy[off + j] = if (j < writeLen) encoded[j] else 0.toByte()
                    }
                }
            }
            else -> throw IllegalArgumentException("Unsupported binary field type")
        }
        return copy
    }

    private fun validateTypedValue(kind: SavFieldValueKind, v: String) {
        val t = v.trim()
        when (kind) {
            SavFieldValueKind.INT32 -> t.toInt()
            SavFieldValueKind.INT64 -> t.toLong()
            SavFieldValueKind.FLOAT32 -> t.toFloat()
            SavFieldValueKind.FLOAT64 -> t.toDouble()
            SavFieldValueKind.BOOLEAN -> {
                if (!t.equals("true", true) && !t.equals("false", true)) {
                    throw IllegalArgumentException("Boolean must be 'true' or 'false'")
                }
            }
            else -> Unit
        }
    }

    // =========================================================================
    // LOW-LEVEL UE FSTRING & BINARY HELPERS
    // =========================================================================

    private fun readUeFString(bytes: ByteArray, offset: Int): Pair<String, Int>? {
        if (offset + 4 > bytes.size) return null
        val len = readIntLE(bytes, offset)
        val pos = offset + 4
        if (len == 0) return "" to pos
        return if (len > 0) {
            if (len > 1_000_000 || pos + len > bytes.size) return null
            val strLen = if (bytes[pos + len - 1] == 0.toByte()) len - 1 else len
            String(bytes, pos, strLen, Charsets.UTF_8) to (pos + len)
        } else {
            val charCount = -len
            val byteLen = charCount * 2
            if (charCount > 500_000 || pos + byteLen > bytes.size) return null
            val trimmedByteLen = if (byteLen >= 2 && bytes[pos + byteLen - 2] == 0.toByte() && bytes[pos + byteLen - 1] == 0.toByte()) {
                byteLen - 2
            } else byteLen
            String(bytes, pos, trimmedByteLen, Charsets.UTF_16LE) to (pos + byteLen)
        }
    }

    private fun encodeUeFStringBytes(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeUeFString(out, s)
        return out.toByteArray()
    }

    private fun writeUeFString(out: ByteArrayOutputStream, s: String) {
        if (s.isEmpty()) {
            writeIntLE(out, 0)
            return
        }
        val asciiSafe = s.all { it.code in 1..127 }
        if (asciiSafe) {
            val raw = s.toByteArray(Charsets.UTF_8)
            writeIntLE(out, raw.size + 1)
            out.write(raw)
            out.write(0)
        } else {
            val raw = s.toByteArray(Charsets.UTF_16LE)
            val charCountWithNull = s.length + 1
            writeIntLE(out, -charCountWithNull)
            out.write(raw)
            out.write(0)
            out.write(0)
        }
    }

    private fun looksLikePrintableText(bytes: ByteArray): Boolean {
        val sampleLen = minOf(bytes.size, 4096)
        if (sampleLen == 0) return true
        var nonText = 0
        for (i in 0 until sampleLen) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0) return false
            if (b < 0x09 || (b in 0x0E..0x1F && b != 0x1B)) nonText++
        }
        return (nonText.toFloat() / sampleLen.toFloat()) < 0.08f
    }

    private fun readShortLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readLongLE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((b[off + i].toLong() and 0xFFL) shl (i * 8))
        }
        return v
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun writeLongLE(out: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) {
            out.write(((v ushr (i * 8)) and 0xFFL).toInt())
        }
    }
}
