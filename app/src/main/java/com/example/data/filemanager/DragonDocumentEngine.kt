package com.example.data.filemanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

enum class DocumentKind(val badgeLabel: String) {
    TEXT_OR_CODE("TEXT"),
    XML_OR_HTML("XML"),
    JSON("JSON"),
    CSV_OR_TSV("CSV"),
    MARKDOWN("MD"),
    PDF("PDF"),
    SAVE_FILE_TEXT("SAV • TEXT"),
    SAVE_FILE_BINARY("SAV • BIN"),
    OFFICE_DOCX("DOCX"),
    OFFICE_ODT("ODT"),
    OFFICE_XLSX_PPTX("OFFICE"),
    RTF("RTF")
}

data class TextLoadResult(
    val text: String,
    val totalBytes: Long,
    val isTruncated: Boolean,
    val detectedEncoding: String
)

data class SavStringEntry(
    val offset: Int,
    val length: Int,
    val text: String,
    val trailingInt32: Int? = null,
    val trailingIntOffset: Int? = null
)

data class PdfTextAnnotation(
    val id: Long = System.nanoTime(),
    val pageIndex: Int,
    val text: String,
    val xFraction: Float = 0.08f,
    val yFraction: Float = 0.12f,
    val fontSizeSp: Float = 16f,
    val colorArgb: Int = Color.parseColor("#DC2626")
)

data class PdfEditSessionPage(
    val id: Long = System.nanoTime(),
    val originalPageIndex: Int?,
    val rotationDegrees: Int = 0,
    val customPageTitle: String? = null,
    val customPageBody: String? = null
)

/**
 * Universal Document Engine for Dragon Pro File Manager.
 *
 * Supports native viewing and editing of:
 * - Plain text, code, markup & config files (.txt, .xml, .json, .html, .md, .csv, .ini, .cfg, .log, .yaml, .sql, .sh, etc.)
 * - PDF documents (.pdf) with PdfRenderer multi-page rendering, zoom, rotation, page insertion/deletion,
 *   text annotation compositing, and PdfDocument save/export.
 * - Game & App Save files (.sav, .dat, .srm, .ue4sav, .es3, etc.) with auto-detection of text vs binary format,
 *   safe in-place ASCII/Int32 patching, and Hex/ASCII byte editing with optional `.bak` backups.
 * - Office documents (.docx, .odt, .rtf, .xlsx, .pptx) via native ZIP+XML reading & lossless-container saving.
 */
object DragonDocumentEngine {

    private const val MAX_TEXT_READ_BYTES = 2 * 1024 * 1024 // 2 MB
    private const val MAX_BINARY_EDIT_BYTES = 4 * 1024 * 1024 // 4 MB

    val TEXT_AND_CODE_EXTENSIONS = setOf(
        "txt", "md", "markdown", "xml", "json", "html", "htm", "xhtml", "svg",
        "csv", "tsv", "log", "ini", "cfg", "conf", "config", "properties", "prop",
        "env", "yaml", "yml", "toml", "plist", "srt", "vtt", "ass", "ssa", "lrc",
        "sh", "bat", "cmd", "ps1", "sql", "kt", "java", "py", "js", "ts", "tsx", "jsx",
        "css", "scss", "c", "cpp", "h", "hpp", "rs", "go", "php", "rb", "lua", "smali",
        "gradle", "kts", "pro", "mk", "rc", "fstab", "hosts", "gitignore", "reg",
        "inf", "nfo", "tex"
    )

    val SAVE_FILE_EXTENSIONS = setOf(
        "sav", "ue4sav", "es3", "srm", "sol", "eep", "fla", "state", "dat"
    )

    val OFFICE_AND_DOC_EXTENSIONS = setOf(
        "pdf", "docx", "odt", "rtf", "xlsx", "pptx"
    )

    val DECOMPILABLE_BINARY_EXTENSIONS = setOf(
        "dex"
    )

    val ALL_SUPPORTED_DOCUMENT_EXTENSIONS: Set<String> =
        TEXT_AND_CODE_EXTENSIONS + SAVE_FILE_EXTENSIONS + OFFICE_AND_DOC_EXTENSIONS + DECOMPILABLE_BINARY_EXTENSIONS

    fun isSupportedDocumentExtension(ext: String): Boolean {
        return ext.lowercase(Locale.US) in ALL_SUPPORTED_DOCUMENT_EXTENSIONS
    }

    fun isDocumentCategoryExtension(ext: String): Boolean {
        val lower = ext.lowercase(Locale.US)
        return lower in OFFICE_AND_DOC_EXTENSIONS ||
            lower in SAVE_FILE_EXTENSIONS ||
            lower in setOf(
                "txt", "md", "markdown", "xml", "json", "html", "htm", "csv", "tsv",
                "log", "ini", "cfg", "conf", "config", "properties", "prop", "yaml", "yml",
                "toml", "sql", "doc", "xls", "ppt", "ods", "odp", "epub", "mobi", "azw3"
            )
    }

    fun classifyDocument(context: Context, file: File): DocumentKind {
        val ext = file.extension.lowercase(Locale.US)
        return when {
            ext == "pdf" -> DocumentKind.PDF
            ext == "docx" -> DocumentKind.OFFICE_DOCX
            ext == "odt" -> DocumentKind.OFFICE_ODT
            ext == "xlsx" || ext == "pptx" -> DocumentKind.OFFICE_XLSX_PPTX
            ext == "rtf" -> DocumentKind.RTF
            ext == "xml" || ext == "html" || ext == "htm" || ext == "xhtml" || ext == "svg" || ext == "plist" ->
                DocumentKind.XML_OR_HTML
            ext == "json" -> DocumentKind.JSON
            ext == "csv" || ext == "tsv" -> DocumentKind.CSV_OR_TSV
            ext == "md" || ext == "markdown" -> DocumentKind.MARKDOWN
            ext in SAVE_FILE_EXTENSIONS -> {
                if (looksLikeBinaryFile(context, file)) DocumentKind.SAVE_FILE_BINARY
                else DocumentKind.SAVE_FILE_TEXT
            }
            ext in TEXT_AND_CODE_EXTENSIONS -> DocumentKind.TEXT_OR_CODE
            else -> {
                if (looksLikeBinaryFile(context, file)) DocumentKind.SAVE_FILE_BINARY
                else DocumentKind.TEXT_OR_CODE
            }
        }
    }

    fun looksLikeBinaryFile(context: Context, file: File): Boolean {
        return runCatching {
            val stream = AndroidDataObbBridge.openInputStreamForFile(context, file) ?: return@runCatching false
            stream.use { input ->
                val sample = ByteArray(4096)
                val read = input.read(sample)
                if (read <= 0) return@runCatching false
                // Check UTF-16 BOM (treat UTF-16 text files as text, not binary)
                if (read >= 2 && ((sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) ||
                        (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()))
                ) {
                    return@runCatching false
                }
                var nonTextCount = 0
                for (i in 0 until read) {
                    val b = sample[i].toInt() and 0xFF
                    if (b == 0) return@runCatching true
                    if (b < 0x09 || (b in 0x0E..0x1F && b != 0x1B)) {
                        nonTextCount++
                    }
                }
                (nonTextCount.toFloat() / read.toFloat()) > 0.12f
            }
        }.getOrDefault(false)
    }

    fun readTextDocument(
        context: Context,
        file: File,
        charsetName: String = "UTF-8"
    ): TextLoadResult {
        val totalBytes = AndroidDataObbBridge.estimateTotalBytesUniversal(context, file)
        val rawBytes = readBinaryBytes(context, file, maxBytes = MAX_TEXT_READ_BYTES)
        val effectiveCharsetName = detectBomCharset(rawBytes) ?: charsetName
        val charset = runCatching { Charset.forName(effectiveCharsetName) }.getOrDefault(Charsets.UTF_8)
        val decoded = String(rawBytes, charset).removePrefix("\uFEFF")
        return TextLoadResult(
            text = decoded,
            totalBytes = totalBytes.coerceAtLeast(rawBytes.size.toLong()),
            isTruncated = totalBytes > MAX_TEXT_READ_BYTES,
            detectedEncoding = effectiveCharsetName
        )
    }

    private fun detectBomCharset(bytes: ByteArray): String? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) return "UTF-8"
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        }
        return null
    }

    fun writeTextDocument(
        context: Context,
        file: File,
        text: String,
        charsetName: String = "UTF-8",
        createBackup: Boolean = false
    ): Boolean {
        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        return writeBinaryBytes(context, file, text.toByteArray(charset), createBackup = createBackup)
    }

    fun readBinaryBytes(
        context: Context,
        file: File,
        maxBytes: Int = MAX_BINARY_EDIT_BYTES
    ): ByteArray {
        return runCatching {
            val stream = AndroidDataObbBridge.openInputStreamForFile(context, file) ?: return@runCatching ByteArray(0)
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(32 * 1024)
                var remaining = maxBytes
                while (remaining > 0) {
                    val toRead = minOf(buf.size, remaining)
                    val r = input.read(buf, 0, toRead)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                    remaining -= r
                }
                out.toByteArray()
            }
        }.getOrDefault(ByteArray(0))
    }

    fun writeBinaryBytes(
        context: Context,
        file: File,
        bytes: ByteArray,
        createBackup: Boolean = false
    ): Boolean {
        return runCatching {
            if (createBackup && AndroidDataObbBridge.existsUniversal(context, file)) {
                val backupFile = File(file.parentFile, "${file.name}.bak")
                AndroidDataObbBridge.copyFileOrDirectoryUniversal(
                    context = context,
                    source = file,
                    dest = backupFile,
                    isDirectoryHint = false
                )
            }
            val out = AndroidDataObbBridge.openOutputStreamForFile(context, file) ?: return@runCatching false
            out.use { stream ->
                stream.write(bytes)
                stream.flush()
            }
            file.parentFile?.absolutePath?.let {
                DragonFileManagerTaskEngine.invalidateDirectorySnapshotCache(it)
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Pretty-prints and validates XML documents (.xml, .svg, .plist, .xhtml).
     * Returns Pair(formattedXml, errorMessage).
     */
    fun formatXmlPretty(rawXml: String): Pair<String?, String?> {
        return try {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
            }
            val doc = dbf.newDocumentBuilder().parse(InputSource(StringReader(rawXml)))
            val tf = TransformerFactory.newInstance()
            val transformer = tf.newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }
            val sw = StringWriter()
            transformer.transform(DOMSource(doc), StreamResult(sw))
            sw.toString() to null
        } catch (e: Exception) {
            null to (e.message ?: "Invalid XML syntax")
        }
    }

    /**
     * Pretty-prints and validates JSON documents (.json).
     */
    fun formatJsonPretty(rawJson: String): Pair<String?, String?> {
        val trimmed = rawJson.trim()
        return try {
            val formatted = when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
                trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
                else -> return null to "JSON must start with '{' or '['"
            }
            formatted to null
        } catch (e: Exception) {
            null to (e.message ?: "Invalid JSON syntax")
        }
    }

    /**
     * Parses CSV or TSV text into rows for clean table preview.
     */
    fun parseCsvRows(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            cell.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        cell.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == delimiter -> {
                    currentRow.add(cell.toString().trim())
                    cell.setLength(0)
                }
                c == '\n' -> {
                    currentRow.add(cell.toString().trim())
                    cell.setLength(0)
                    if (currentRow.any { it.isNotEmpty() }) {
                        rows.add(currentRow.toList())
                    }
                    currentRow.clear()
                }
                c == '\r' -> Unit
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(cell.toString().trim())
            if (currentRow.any { it.isNotEmpty() }) {
                rows.add(currentRow.toList())
            }
        }
        return rows
    }

    // -------------------------------------------------------------------------
    // Binary .SAV File Inspection & Safe In-Place Patching Engine
    // -------------------------------------------------------------------------

    fun extractSavStringsAndValues(bytes: ByteArray, maxEntries: Int = 500): List<SavStringEntry> {
        val results = mutableListOf<SavStringEntry>()
        var i = 0
        while (i < bytes.size && results.size < maxEntries) {
            val b = bytes[i].toInt() and 0xFF
            if (b in 0x20..0x7E) {
                val start = i
                val sb = StringBuilder()
                while (i < bytes.size) {
                    val ch = bytes[i].toInt() and 0xFF
                    if (ch in 0x20..0x7E) {
                        sb.append(ch.toChar())
                        i++
                    } else {
                        break
                    }
                }
                val len = i - start
                if (len >= 3) {
                    // Check if followed by a NUL terminator and a 4-byte little-endian integer (common in UE4/Unity/binary .sav)
                    var intOffset: Int? = null
                    var intVal: Int? = null
                    val afterNull = if (i < bytes.size && bytes[i] == 0.toByte()) i + 1 else i
                    if (afterNull + 4 <= bytes.size) {
                        val candidate = ByteBuffer.wrap(bytes, afterNull, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                        if (candidate in -1_000_000_000..1_000_000_000) {
                            intOffset = afterNull
                            intVal = candidate
                        }
                    }
                    results.add(
                        SavStringEntry(
                            offset = start,
                            length = len,
                            text = sb.toString(),
                            trailingInt32 = intVal,
                            trailingIntOffset = intOffset
                        )
                    )
                }
            } else {
                i++
            }
        }
        return results
    }

    /**
     * Safely patches a string inside a binary `.sav` file in place without shifting binary offsets.
     * Remaining bytes in the slot are zero-padded (`0x00`), keeping headers/checksums/offsets aligned.
     */
    fun patchSavStringInPlace(
        originalBytes: ByteArray,
        entry: SavStringEntry,
        newText: String
    ): ByteArray {
        val copy = originalBytes.copyOf()
        val encoded = newText.toByteArray(Charsets.ISO_8859_1)
        val writeLen = minOf(encoded.size, entry.length)
        for (j in 0 until entry.length) {
            val targetIdx = entry.offset + j
            if (targetIdx in copy.indices) {
                copy[targetIdx] = if (j < writeLen) encoded[j] else 0.toByte()
            }
        }
        return copy
    }

    fun patchSavInt32LE(
        originalBytes: ByteArray,
        offset: Int,
        newValue: Int
    ): ByteArray {
        if (offset < 0 || offset + 4 > originalBytes.size) return originalBytes
        val copy = originalBytes.copyOf()
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(newValue).array()
        System.arraycopy(bb, 0, copy, offset, 4)
        return copy
    }

    fun patchHexRow(
        originalBytes: ByteArray,
        rowOffset: Int,
        hexString: String
    ): Pair<ByteArray?, String?> {
        val cleaned = hexString.trim().replace(Regex("[^0-9a-fA-F]"), "")
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) {
            return null to "Enter valid pairs of hex digits (e.g., 47 56 41 53)"
        }
        val byteCount = cleaned.length / 2
        if (rowOffset + byteCount > originalBytes.size) {
            return null to "Input exceeds file size (${originalBytes.size - rowOffset} bytes remaining)"
        }
        val copy = originalBytes.copyOf()
        for (i in 0 until byteCount) {
            val byteVal = cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            copy[rowOffset + i] = byteVal
        }
        return copy to null
    }

    // -------------------------------------------------------------------------
    // Native Office Document Engine (.docx, .odt, .xlsx, .pptx, .rtf)
    // -------------------------------------------------------------------------

    fun readDocxPlainText(context: Context, file: File): String {
        val localFile = stageReadableLocalFile(context, file) ?: return ""
        return try {
            ZipFile(localFile).use { zip ->
                val docEntry = zip.getEntry("word/document.xml") ?: return ""
                val xmlBytes = zip.getInputStream(docEntry).use { it.readBytes() }
                extractParagraphsFromWordXml(xmlBytes)
            }
        } catch (_: Exception) {
            ""
        } finally {
            cleanupStagedFile(localFile, file)
        }
    }

    private fun extractParagraphsFromWordXml(xmlBytes: ByteArray): String {
        val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        val paragraphs = doc.getElementsByTagNameNS("*", "p")
        val lines = mutableListOf<String>()
        for (i in 0 until paragraphs.length) {
            val pNode = paragraphs.item(i) as? Element ?: continue
            val texts = pNode.getElementsByTagNameNS("*", "t")
            val sb = StringBuilder()
            for (j in 0 until texts.length) {
                sb.append(texts.item(j).textContent.orEmpty())
            }
            lines.add(sb.toString())
        }
        return lines.joinToString("\n")
    }

    /**
     * Saves updated plain-text paragraphs back into an existing `.docx` container,
     * preserving styles, relationships, themes, and media inside the archive.
     */
    fun saveDocxPlainText(context: Context, targetFile: File, updatedText: String): Boolean {
        val localSource = stageReadableLocalFile(context, targetFile)
        val stageOut = File(context.cacheDir, "docx_edit_${System.nanoTime()}.docx")
        return try {
            val escapedXmlBody = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
                updatedText.split("\n").forEach { line ->
                    val esc = xmlEscape(line)
                    append("""<w:p><w:r><w:t xml:space="preserve">""").append(esc).append("""</w:t></w:r></w:p>""")
                }
                append("""<w:sectPr/></w:body></w:document>""")
            }.toByteArray(Charsets.UTF_8)

            if (localSource != null && localSource.exists() && localSource.length() > 0L) {
                ZipFile(localSource).use { zipIn ->
                    ZipOutputStream(BufferedOutputStream(FileOutputStream(stageOut))).use { zos ->
                        var replaced = false
                        val entries = zipIn.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val newEntry = ZipEntry(entry.name)
                            zos.putNextEntry(newEntry)
                            if (entry.name == "word/document.xml") {
                                zos.write(escapedXmlBody)
                                replaced = true
                            } else if (!entry.isDirectory) {
                                zipIn.getInputStream(entry).use { it.copyTo(zos, 32 * 1024) }
                            }
                            zos.closeEntry()
                        }
                        if (!replaced) {
                            zos.putNextEntry(ZipEntry("word/document.xml"))
                            zos.write(escapedXmlBody)
                            zos.closeEntry()
                        }
                    }
                }
            } else {
                // Create a valid minimal .docx archive from scratch
                ZipOutputStream(BufferedOutputStream(FileOutputStream(stageOut))).use { zos ->
                    fun putText(name: String, content: String) {
                        zos.putNextEntry(ZipEntry(name))
                        zos.write(content.toByteArray(Charsets.UTF_8))
                        zos.closeEntry()
                    }
                    putText(
                        "[Content_Types].xml",
                        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
                    )
                    putText(
                        "_rels/.rels",
                        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
                    )
                    zos.putNextEntry(ZipEntry("word/document.xml"))
                    zos.write(escapedXmlBody)
                    zos.closeEntry()
                }
            }

            val bytes = stageOut.readBytes()
            writeBinaryBytes(context, targetFile, bytes, createBackup = false)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { stageOut.delete() }
            if (localSource != null) cleanupStagedFile(localSource, targetFile)
        }
    }

    fun readOdtPlainText(context: Context, file: File): String {
        val localFile = stageReadableLocalFile(context, file) ?: return ""
        return try {
            ZipFile(localFile).use { zip ->
                val contentEntry = zip.getEntry("content.xml") ?: return ""
                val xmlBytes = zip.getInputStream(contentEntry).use { it.readBytes() }
                val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                val doc = dbf.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
                val pNodes = doc.getElementsByTagNameNS("*", "p")
                val lines = mutableListOf<String>()
                for (i in 0 until pNodes.length) {
                    lines.add(pNodes.item(i).textContent.orEmpty())
                }
                lines.joinToString("\n")
            }
        } catch (_: Exception) {
            ""
        } finally {
            cleanupStagedFile(localFile, file)
        }
    }

    fun saveOdtPlainText(context: Context, targetFile: File, updatedText: String): Boolean {
        val localSource = stageReadableLocalFile(context, targetFile) ?: return false
        val stageOut = File(context.cacheDir, "odt_edit_${System.nanoTime()}.odt")
        return try {
            val contentXml = buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?>""")
                append("""<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2"><office:body><office:text>""")
                updatedText.split("\n").forEach { line ->
                    append("<text:p>").append(xmlEscape(line)).append("</text:p>")
                }
                append("</office:text></office:body></office:document-content>")
            }.toByteArray(Charsets.UTF_8)

            ZipFile(localSource).use { zipIn ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(stageOut))).use { zos ->
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        zos.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == "content.xml") {
                            zos.write(contentXml)
                        } else if (!entry.isDirectory) {
                            zipIn.getInputStream(entry).use { it.copyTo(zos, 32 * 1024) }
                        }
                        zos.closeEntry()
                    }
                }
            }
            writeBinaryBytes(context, targetFile, stageOut.readBytes(), createBackup = false)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { stageOut.delete() }
            cleanupStagedFile(localSource, targetFile)
        }
    }

    fun readXlsxOrPptxPlainText(context: Context, file: File): String {
        val localFile = stageReadableLocalFile(context, file) ?: return ""
        return try {
            ZipFile(localFile).use { zip ->
                val sb = StringBuilder()
                val ext = file.extension.lowercase(Locale.US)
                val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                if (ext == "xlsx") {
                    val sharedEntry = zip.getEntry("xl/sharedStrings.xml")
                    if (sharedEntry != null) {
                        val doc = dbf.newDocumentBuilder().parse(zip.getInputStream(sharedEntry))
                        val siNodes = doc.getElementsByTagNameNS("*", "si")
                        for (i in 0 until siNodes.length) {
                            val txt = siNodes.item(i).textContent?.trim().orEmpty()
                            if (txt.isNotEmpty()) sb.appendLine(txt)
                        }
                    }
                } else {
                    val slideEntries = zip.entries().toList()
                        .filter { it.name.startsWith("ppt/slides/slide") && it.name.endsWith(".xml") }
                        .sortedBy { it.name }
                    slideEntries.forEachIndexed { idx, entry ->
                        sb.appendLine("--- Slide ${idx + 1} ---")
                        val doc = dbf.newDocumentBuilder().parse(zip.getInputStream(entry))
                        val tNodes = doc.getElementsByTagNameNS("*", "t")
                        for (i in 0 until tNodes.length) {
                            val txt = tNodes.item(i).textContent.orEmpty()
                            if (txt.isNotBlank()) sb.appendLine(txt)
                        }
                        sb.appendLine()
                    }
                }
                sb.toString().trim()
            }
        } catch (_: Exception) {
            ""
        } finally {
            cleanupStagedFile(localFile, file)
        }
    }

    fun stripRtfToPlainText(rawRtf: String): String {
        if (!rawRtf.trimStart().startsWith("{\\rtf")) return rawRtf
        return rawRtf
            .replace(Regex("""\\par\b\s?"""), "\n")
            .replace(Regex("""\\line\b\s?"""), "\n")
            .replace(Regex("""\\tab\b\s?"""), "\t")
            .replace(Regex("""\\'([0-9a-fA-F]{2})""")) { m ->
                m.groupValues[1].toInt(16).toChar().toString()
            }
            .replace(Regex("""\{\\[*a-zA-Z0-9\s;-]+\}"""), "")
            .replace(Regex("""\\[a-zA-Z]+-?\d*\s?"""), "")
            .replace("{", "")
            .replace("}", "")
            .trim()
    }

    fun wrapPlainTextAsRtf(plainText: String): String {
        val body = plainText
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\r\n", "\n")
            .replace("\n", "\\par\n")
        return "{\\rtf1\\ansi\\deff0\n$body\n}"
    }

    // -------------------------------------------------------------------------
    // Native PDF Rendering, Editing, Annotation & Creation Engine
    // -------------------------------------------------------------------------

    fun stageReadableLocalFile(context: Context, file: File): File? {
        if (file.exists() && file.canRead()) return file
        val temp = File(context.cacheDir, "doc_stage_${System.nanoTime()}_${file.name}")
        val ok = AndroidDataObbBridge.copyFileOrDirectoryUniversal(
            context = context,
            source = file,
            dest = temp,
            isDirectoryHint = false
        )
        return if (ok && temp.exists()) temp else null
    }

    fun cleanupStagedFile(staged: File?, original: File) {
        if (staged != null && staged.absolutePath != original.absolutePath) {
            runCatching { staged.delete() }
        }
    }

    fun getPdfPageCount(pdfFile: File): Int {
        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer -> renderer.pageCount }
            }
        }.getOrDefault(0)
    }

    fun renderPdfPageBitmap(
        pdfFile: File,
        pageIndex: Int,
        targetWidthPx: Int = 1080,
        rotationDegrees: Int = 0
    ): Bitmap? {
        return runCatching {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return@runCatching null
                    renderer.openPage(pageIndex).use { page ->
                        val scale = targetWidthPx.toFloat() / page.width.coerceAtLeast(1).toFloat()
                        val w = (page.width * scale).toInt().coerceIn(320, 2400)
                        val h = (page.height * scale).toInt().coerceIn(320, 3400)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val normRot = ((rotationDegrees % 360) + 360) % 360
                        if (normRot != 0) {
                            val matrix = Matrix().apply { postRotate(normRot.toFloat()) }
                            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                            if (rotated != bmp) bmp.recycle()
                            rotated
                        } else {
                            bmp
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * Renders an edited PDF (kept/rotated pages, composited text annotations, and newly added text pages)
     * into a valid PDF file using Android's native `PdfDocument` API.
     */
    fun exportEditedPdf(
        context: Context,
        sourceLocalPdf: File?,
        outputPdfFile: File,
        pages: List<PdfEditSessionPage>,
        annotations: List<PdfTextAnnotation>
    ): Boolean {
        if (pages.isEmpty()) return false
        val outDoc = PdfDocument()
        val stageFile = File(context.cacheDir, "pdf_export_${System.nanoTime()}.pdf")
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        return try {
            if (sourceLocalPdf != null && sourceLocalPdf.exists() && sourceLocalPdf.length() > 0L) {
                pfd = ParcelFileDescriptor.open(sourceLocalPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
            }

            pages.forEachIndexed { sessionIdx, pageSpec ->
                val origIdx = pageSpec.originalPageIndex
                if (origIdx != null && renderer != null && origIdx in 0 until renderer.pageCount) {
                    renderer.openPage(origIdx).use { srcPage ->
                        val normRot = ((pageSpec.rotationDegrees % 360) + 360) % 360
                        val swapDims = normRot == 90 || normRot == 270
                        val outWidth = if (swapDims) srcPage.height else srcPage.width
                        val outHeight = if (swapDims) srcPage.width else srcPage.height

                        // Render source page at 2x print quality for sharp text
                        val renderScale = 2.0f
                        val bmpW = (srcPage.width * renderScale).toInt().coerceIn(400, 2200)
                        val bmpH = (srcPage.height * renderScale).toInt().coerceIn(400, 3000)
                        val rawBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                        rawBmp.eraseColor(Color.WHITE)
                        srcPage.render(rawBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        val finalBmp = if (normRot != 0) {
                            val m = Matrix().apply { postRotate(normRot.toFloat()) }
                            Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true).also {
                                if (it != rawBmp) rawBmp.recycle()
                            }
                        } else {
                            rawBmp
                        }

                        val pageInfo = PdfDocument.PageInfo.Builder(
                            outWidth.coerceAtLeast(300),
                            outHeight.coerceAtLeast(300),
                            sessionIdx + 1
                        ).create()
                        val outPage = outDoc.startPage(pageInfo)
                        val canvas = outPage.canvas
                        val destRect = android.graphics.Rect(0, 0, pageInfo.pageWidth, pageInfo.pageHeight)
                        canvas.drawBitmap(finalBmp, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
                        finalBmp.recycle()

                        // Composite user text annotations onto this page
                        drawAnnotationsOnPdfCanvas(
                            canvas = canvas,
                            pageWidth = pageInfo.pageWidth,
                            pageHeight = pageInfo.pageHeight,
                            annotations = annotations.filter { it.pageIndex == sessionIdx }
                        )

                        outDoc.finishPage(outPage)
                    }
                } else {
                    // Newly added custom text page (standard A4 595 x 842 pt)
                    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, sessionIdx + 1).create()
                    val outPage = outDoc.startPage(pageInfo)
                    drawCustomTextPageOnCanvas(
                        canvas = outPage.canvas,
                        pageWidth = 595,
                        pageHeight = 842,
                        title = pageSpec.customPageTitle.orEmpty(),
                        body = pageSpec.customPageBody.orEmpty()
                    )
                    drawAnnotationsOnPdfCanvas(
                        canvas = outPage.canvas,
                        pageWidth = 595,
                        pageHeight = 842,
                        annotations = annotations.filter { it.pageIndex == sessionIdx }
                    )
                    outDoc.finishPage(outPage)
                }
            }

            FileOutputStream(stageFile).use { fos ->
                outDoc.writeTo(fos)
                fos.flush()
            }
            val bytes = stageFile.readBytes()
            writeBinaryBytes(context, outputPdfFile, bytes, createBackup = false)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            runCatching { outDoc.close() }
            runCatching { stageFile.delete() }
        }
    }

    /**
     * Converts any plain text document into a multi-page A4 PDF file.
     */
    fun createPdfFromPlainText(
        context: Context,
        outputPdfFile: File,
        title: String,
        bodyText: String
    ): Boolean {
        val outDoc = PdfDocument()
        val stageFile = File(context.cacheDir, "pdf_create_${System.nanoTime()}.pdf")
        return try {
            val pageW = 595
            val pageH = 842
            val margin = 42
            val usableW = pageW - margin * 2
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#111827")
                textSize = 11.5f
            }
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 16f
                isFakeBoldText = true
            }

            val fullText = bodyText.ifBlank { " " }
            val layout = StaticLayout.Builder
                .obtain(fullText, 0, fullText.length, textPaint, usableW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(3f, 1.15f)
                .build()

            var lineIdx = 0
            var pageNum = 1
            while (lineIdx < layout.lineCount || pageNum == 1) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
                val page = outDoc.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                var y = margin.toFloat()
                if (pageNum == 1 && title.isNotBlank()) {
                    canvas.drawText(title, margin.toFloat(), y + 16f, titlePaint)
                    y += 32f
                }

                while (lineIdx < layout.lineCount) {
                    val lineStart = layout.getLineStart(lineIdx)
                    val lineEnd = layout.getLineEnd(lineIdx)
                    val lineHeight = (layout.getLineBottom(lineIdx) - layout.getLineTop(lineIdx)).toFloat()
                    if (y + lineHeight > pageH - margin) break
                    val lineStr = fullText.substring(lineStart, lineEnd).trimEnd('\n', '\r')
                    canvas.drawText(lineStr, margin.toFloat(), y + lineHeight * 0.8f, textPaint)
                    y += lineHeight
                    lineIdx++
                }
                outDoc.finishPage(page)
                pageNum++
                if (lineIdx >= layout.lineCount) break
            }

            FileOutputStream(stageFile).use { fos ->
                outDoc.writeTo(fos)
                fos.flush()
            }
            writeBinaryBytes(context, outputPdfFile, stageFile.readBytes(), createBackup = false)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { outDoc.close() }
            runCatching { stageFile.delete() }
        }
    }

    private fun drawCustomTextPageOnCanvas(
        canvas: Canvas,
        pageWidth: Int,
        pageHeight: Int,
        title: String,
        body: String
    ) {
        canvas.drawColor(Color.WHITE)
        val margin = 42f
        var y = margin
        if (title.isNotBlank()) {
            val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0F172A")
                textSize = 18f
                isFakeBoldText = true
            }
            canvas.drawText(title, margin, y + 18f, titlePaint)
            y += 34f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 12f
        }
        val usableW = (pageWidth - margin * 2).toInt().coerceAtLeast(100)
        val layout = StaticLayout.Builder
            .obtain(body, 0, body.length, bodyPaint, usableW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1.15f)
            .build()
        canvas.save()
        canvas.translate(margin, y)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawAnnotationsOnPdfCanvas(
        canvas: Canvas,
        pageWidth: Int,
        pageHeight: Int,
        annotations: List<PdfTextAnnotation>
    ) {
        annotations.forEach { ann ->
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ann.colorArgb
                textSize = ann.fontSizeSp
                isFakeBoldText = true
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(215, 255, 255, 255)
            }
            val x = (ann.xFraction.coerceIn(0.03f, 0.85f) * pageWidth)
            val y = (ann.yFraction.coerceIn(0.05f, 0.92f) * pageHeight)
            val maxW = (pageWidth - x - 20f).toInt().coerceAtLeast(120)
            val layout = StaticLayout.Builder
                .obtain(ann.text, 0, ann.text.length, paint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            val pad = 6f
            canvas.drawRoundRect(
                x - pad,
                y - pad,
                x + layout.width.toFloat() + pad,
                y + layout.height.toFloat() + pad,
                6f,
                6f,
                bgPaint
            )
            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private fun xmlEscape(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
