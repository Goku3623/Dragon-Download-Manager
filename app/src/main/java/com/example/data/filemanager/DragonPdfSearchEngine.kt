package com.example.data.filemanager

import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/**
 * Normalized highlight rectangle (0f..1f in page width/height space) so overlays stay
 * perfectly aligned across any screen size, rotation, or zoom level.
 */
data class PdfNormalizedHighlightRect(
    val leftFrac: Float,
    val topFrac: Float,
    val rightFrac: Float,
    val bottomFrac: Float
)

data class PdfSearchPageResult(
    val pageIndex: Int,
    val matchCount: Int,
    val snippet: String,
    val normalizedRects: List<PdfNormalizedHighlightRect> = emptyList()
)

/**
 * Hybrid High-Speed PDF Search Engine for Dragon Archiver's Pro E-Book Reader.
 *
 * - On Android 15+ (API 35+): leverages native `PdfRenderer.Page.searchText()` for exact
 *   on-page bounding-box highlights + native `getTextContents()` snippets.
 * - On ALL Android versions (API 24+): includes a pure-Kotlin cached PDF page-stream
 *   text extractor (supporting `/Pages` `/Kids` traversal, FlateDecode decompression,
 *   `Tj`/`TJ` literal & hex strings, UTF-16BE, and `/ToUnicode` CMap decoding), plus
 *   instant search across custom added pages and user text annotations.
 */
object DragonPdfSearchEngine {

    private val extractedPageTextCache = ConcurrentHashMap<String, List<String>>()

    suspend fun searchPdf(
        pdfFile: File?,
        pages: List<PdfEditSessionPage>,
        annotations: List<PdfTextAnnotation>,
        query: String,
        matchCase: Boolean = false,
        onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }
    ): List<PdfSearchPageResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty() || pages.isEmpty()) return emptyList()

        val results = mutableListOf<PdfSearchPageResult>()
        val total = pages.size

        // Open native PdfRenderer once on API 35+ for ultra-accurate native text search + highlight bounds
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val useNativeApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            pdfFile != null && pdfFile.exists() && pdfFile.length() > 0L

        if (useNativeApi35 && pdfFile != null) {
            runCatching {
                pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd!!)
            }
        }

        // Lazy pure-Kotlin page text fallback cache (used on API < 35 or if native search yields nothing)
        var fallbackPageTexts: List<String>? = null
        fun getFallbackTexts(): List<String> {
            fallbackPageTexts?.let { return it }
            val loaded = if (pdfFile != null && pdfFile.exists()) {
                extractAllPagesTextCached(pdfFile, pages.count { it.originalPageIndex != null })
            } else {
                emptyList()
            }
            fallbackPageTexts = loaded
            return loaded
        }

        try {
            pages.forEachIndexed { sessionIdx, pageSpec ->
                currentCoroutineContext().ensureActive()
                if (sessionIdx % 5 == 0 || sessionIdx == total - 1) {
                    onProgress(sessionIdx + 1, total)
                }

                var hitCount = 0
                var snippet = ""
                var rects = emptyList<PdfNormalizedHighlightRect>()

                val origIdx = pageSpec.originalPageIndex
                if (origIdx != null) {
                    var nativeHandled = false
                    if (useNativeApi35 && renderer != null && origIdx in 0 until renderer!!.pageCount) {
                        val nativeRes = runCatching {
                            searchSinglePageNativeApi35(
                                renderer = renderer!!,
                                origPageIdx = origIdx,
                                rotationDegrees = pageSpec.rotationDegrees,
                                query = cleanQuery,
                                matchCase = matchCase
                            )
                        }.getOrNull()
                        if (nativeRes != null) {
                            nativeHandled = true
                            hitCount += nativeRes.matchCount
                            if (nativeRes.snippet.isNotBlank()) snippet = nativeRes.snippet
                            rects = nativeRes.normalizedRects
                        }
                    }

                    if (!nativeHandled) {
                        val pageText = getFallbackTexts().getOrNull(origIdx).orEmpty()
                        if (pageText.isNotBlank()) {
                            val (count, foundSnippet) = findTextMatchesAndSnippet(pageText, cleanQuery, matchCase)
                            if (count > 0) {
                                hitCount += count
                                snippet = foundSnippet
                            }
                        }
                    }
                } else {
                    // Custom added text page
                    val combined = buildString {
                        pageSpec.customPageTitle?.let { append(it).append('\n') }
                        pageSpec.customPageBody?.let { append(it) }
                    }
                    val (count, foundSnippet) = findTextMatchesAndSnippet(combined, cleanQuery, matchCase)
                    if (count > 0) {
                        hitCount += count
                        snippet = foundSnippet
                    }
                }

                // Also search user text annotations on this page
                val pageAnns = annotations.filter { it.pageIndex == sessionIdx }
                for (ann in pageAnns) {
                    val (count, foundSnippet) = findTextMatchesAndSnippet(ann.text, cleanQuery, matchCase)
                    if (count > 0) {
                        hitCount += count
                        if (snippet.isBlank()) snippet = "Note: $foundSnippet"
                    }
                }

                if (hitCount > 0) {
                    results.add(
                        PdfSearchPageResult(
                            pageIndex = sessionIdx,
                            matchCount = hitCount,
                            snippet = snippet.ifBlank { "Match found on page ${sessionIdx + 1}" },
                            normalizedRects = rects
                        )
                    )
                }
            }
        } finally {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
        }

        return results
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun searchSinglePageNativeApi35(
        renderer: PdfRenderer,
        origPageIdx: Int,
        rotationDegrees: Int,
        query: String,
        matchCase: Boolean
    ): PdfSearchPageResult {
        renderer.openPage(origPageIdx).use { page ->
            val pw = page.width.coerceAtLeast(1).toFloat()
            val ph = page.height.coerceAtLeast(1).toFloat()
            val matches = page.searchText(query)
            if (matches.isEmpty()) {
                return PdfSearchPageResult(origPageIdx, 0, "", emptyList())
            }

            // Extract readable snippet from native page text content when available
            val pageText = runCatching {
                page.textContents.joinToString(" ") { it.text }
            }.getOrDefault("")

            if (matchCase && pageText.isNotBlank() && !pageText.contains(query, ignoreCase = false)) {
                return PdfSearchPageResult(origPageIdx, 0, "", emptyList())
            }

            val (_, snippet) = if (pageText.isNotBlank()) {
                findTextMatchesAndSnippet(pageText, query, matchCase)
            } else {
                matches.size to "Found \"$query\" on page ${origPageIdx + 1}"
            }

            val normRot = ((rotationDegrees % 360) + 360) % 360
            val rects = mutableListOf<PdfNormalizedHighlightRect>()
            for (match in matches) {
                for (r: RectF in match.bounds) {
                    val rawL = (r.left / pw).coerceIn(0f, 1f)
                    val rawT = (r.top / ph).coerceIn(0f, 1f)
                    val rawR = (r.right / pw).coerceIn(0f, 1f)
                    val rawB = (r.bottom / ph).coerceIn(0f, 1f)
                    rects.add(rotateNormalizedRect(rawL, rawT, rawR, rawB, normRot))
                }
            }

            return PdfSearchPageResult(
                pageIndex = origPageIdx,
                matchCount = matches.size,
                snippet = snippet,
                normalizedRects = rects
            )
        }
    }

    private fun rotateNormalizedRect(
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        normRot: Int
    ): PdfNormalizedHighlightRect {
        return when (normRot) {
            90 -> PdfNormalizedHighlightRect(
                leftFrac = (1f - b).coerceIn(0f, 1f),
                topFrac = l.coerceIn(0f, 1f),
                rightFrac = (1f - t).coerceIn(0f, 1f),
                bottomFrac = r.coerceIn(0f, 1f)
            )
            180 -> PdfNormalizedHighlightRect(
                leftFrac = (1f - r).coerceIn(0f, 1f),
                topFrac = (1f - b).coerceIn(0f, 1f),
                rightFrac = (1f - l).coerceIn(0f, 1f),
                bottomFrac = (1f - t).coerceIn(0f, 1f)
            )
            270 -> PdfNormalizedHighlightRect(
                leftFrac = t.coerceIn(0f, 1f),
                topFrac = (1f - r).coerceIn(0f, 1f),
                rightFrac = b.coerceIn(0f, 1f),
                bottomFrac = (1f - l).coerceIn(0f, 1f)
            )
            else -> PdfNormalizedHighlightRect(l, t, r, b)
        }
    }

    private fun findTextMatchesAndSnippet(
        text: String,
        query: String,
        matchCase: Boolean
    ): Pair<Int, String> {
        if (text.isBlank() || query.isBlank()) return 0 to ""
        val haystack = if (matchCase) text else text.lowercase(Locale.US)
        val needle = if (matchCase) query else query.lowercase(Locale.US)
        var count = 0
        var firstIdx = -1
        var cursor = 0
        while (true) {
            val found = haystack.indexOf(needle, cursor)
            if (found < 0) break
            if (firstIdx < 0) firstIdx = found
            count++
            cursor = found + needle.length.coerceAtLeast(1)
        }
        if (count == 0) return 0 to ""
        val start = (firstIdx - 38).coerceAtLeast(0)
        val end = (firstIdx + query.length + 58).coerceAtMost(text.length)
        val rawSnippet = text.substring(start, end)
            .replace(Regex("\\s+"), " ")
            .trim()
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return count to "$prefix$rawSnippet$suffix"
    }

    // =========================================================================
    // UNIVERSAL PURE-KOTLIN PDF PAGE TEXT EXTRACTOR (API 24+ FALLBACK)
    // =========================================================================

    private fun extractAllPagesTextCached(pdfFile: File, expectedPages: Int): List<String> {
        val cacheKey = "${pdfFile.absolutePath}:${pdfFile.length()}:${pdfFile.lastModified()}"
        extractedPageTextCache[cacheKey]?.let { return it }

        val extracted = runCatching {
            extractAllPagesTextRaw(pdfFile, expectedPages)
        }.getOrDefault(emptyList())

        if (extractedPageTextCache.size > 6) {
            extractedPageTextCache.clear()
        }
        if (extracted.isNotEmpty()) {
            extractedPageTextCache[cacheKey] = extracted
        }
        return extracted
    }

    private fun extractAllPagesTextRaw(pdfFile: File, expectedPages: Int): List<String> {
        // Safety cap for huge scanned image-only PDFs (over 45 MB) to avoid OOM
        if (pdfFile.length() > 45L * 1024L * 1024L) return emptyList()
        val rawBytes = pdfFile.readBytes()
        val rawStr = String(rawBytes, StandardCharsets.ISO_8859_1)

        // Parse indirect objects: "N G obj ... endobj"
        val objPattern = Regex("""(\d+)\s+\d+\s+obj\b(.*?)endobj""", setOf(RegexOption.DOT_MATCHES_ALL))
        val objects = HashMap<Int, String>(256)
        val objectRanges = HashMap<Int, IntRange>(256)
        for (m in objPattern.findAll(rawStr)) {
            val id = m.groupValues[1].toIntOrNull() ?: continue
            objects[id] = m.groupValues[2]
            objectRanges[id] = m.groups[2]!!.range
        }

        // Build global ToUnicode CMap lookup (best-effort across fonts)
        val cmap = HashMap<Int, String>(256)
        for ((id, body) in objects) {
            if (body.contains("beginbfchar") || body.contains("beginbfrange") || body.contains("/CMapName")) {
                val streamData = extractStreamBytesForObject(rawBytes, rawStr, objectRanges[id] ?: continue, body)
                if (streamData != null) {
                    parseToUnicodeCMap(String(streamData, StandardCharsets.ISO_8859_1), cmap)
                }
            }
        }

        // Find ordered list of /Page object IDs by walking /Pages /Kids tree
        val orderedPageObjIds = mutableListOf<Int>()
        val rootCatalogId = Regex("""/Root\s+(\d+)\s+\d+\s+R""").find(rawStr)?.groupValues?.get(1)?.toIntOrNull()
        val rootPagesId = rootCatalogId?.let { catId ->
            objects[catId]?.let { Regex("""/Pages\s+(\d+)\s+\d+\s+R""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        }
        if (rootPagesId != null) {
            val visited = HashSet<Int>()
            fun walkPagesNode(nodeId: Int) {
                if (!visited.add(nodeId)) return
                val body = objects[nodeId] ?: return
                val kidsMatch = Regex("""/Kids\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(body)
                if (kidsMatch != null) {
                    val refs = Regex("""(\d+)\s+\d+\s+R""").findAll(kidsMatch.groupValues[1])
                    for (r in refs) {
                        r.groupValues[1].toIntOrNull()?.let { walkPagesNode(it) }
                    }
                } else if (body.contains("/Type") && Regex("""/Type\s*/Page\b""").containsMatchIn(body)) {
                    orderedPageObjIds.add(nodeId)
                }
            }
            walkPagesNode(rootPagesId)
        }

        // Fallback if /Root /Pages tree couldn't be traversed (e.g. compressed XRef streams)
        if (orderedPageObjIds.isEmpty()) {
            for ((id, body) in objects.entries.sortedBy { it.key }) {
                if (Regex("""/Type\s*/Page\b""").containsMatchIn(body) &&
                    !Regex("""/Type\s*/Pages\b""").containsMatchIn(body)
                ) {
                    orderedPageObjIds.add(id)
                }
            }
        }

        if (orderedPageObjIds.isEmpty()) return emptyList()

        val pageTexts = ArrayList<String>(orderedPageObjIds.size.coerceAtLeast(expectedPages))
        for (pageId in orderedPageObjIds) {
            val pageBody = objects[pageId].orEmpty()
            val contentIds = extractContentRefIds(pageBody)
            val sb = StringBuilder()
            for (cid in contentIds) {
                val cBody = objects[cid] ?: continue
                val cRange = objectRanges[cid] ?: continue
                val bytes = extractStreamBytesForObject(rawBytes, rawStr, cRange, cBody) ?: continue
                val contentText = extractTextFromPdfContentStream(String(bytes, StandardCharsets.ISO_8859_1), cmap)
                if (contentText.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(contentText)
                }
            }
            pageTexts.add(sb.toString().replace(Regex("\\s+"), " ").trim())
        }
        return pageTexts
    }

    private fun extractContentRefIds(pageBody: String): List<Int> {
        val arrayMatch = Regex("""/Contents\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(pageBody)
        if (arrayMatch != null) {
            return Regex("""(\d+)\s+\d+\s+R""").findAll(arrayMatch.groupValues[1])
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toList()
        }
        val singleMatch = Regex("""/Contents\s+(\d+)\s+\d+\s+R""").find(pageBody)
        val singleId = singleMatch?.groupValues?.get(1)?.toIntOrNull()
        return if (singleId != null) listOf(singleId) else emptyList()
    }

    private fun extractStreamBytesForObject(
        rawBytes: ByteArray,
        rawStr: String,
        objRange: IntRange,
        objBody: String
    ): ByteArray? {
        val streamRelIdx = objBody.indexOf("stream")
        if (streamRelIdx < 0) return null
        val endStreamRelIdx = objBody.indexOf("endstream", streamRelIdx + 6)
        if (endStreamRelIdx < 0) return null

        var startByte = objRange.first + streamRelIdx + 6
        if (startByte < rawBytes.size && rawBytes[startByte] == '\r'.code.toByte()) startByte++
        if (startByte < rawBytes.size && rawBytes[startByte] == '\n'.code.toByte()) startByte++

        var endByte = objRange.first + endStreamRelIdx
        while (endByte > startByte && (rawBytes[endByte - 1] == '\n'.code.toByte() || rawBytes[endByte - 1] == '\r'.code.toByte())) {
            endByte--
        }
        if (endByte <= startByte || endByte > rawBytes.size) return null

        val slice = rawBytes.copyOfRange(startByte, endByte)
        return if (objBody.substring(0, streamRelIdx).contains("/FlateDecode")) {
            inflatePdfStream(slice)
        } else {
            slice
        }
    }

    private fun inflatePdfStream(compressed: ByteArray): ByteArray? {
        fun tryInflate(nowrap: Boolean): ByteArray? {
            val inflater = Inflater(nowrap)
            return try {
                inflater.setInput(compressed)
                val out = ByteArrayOutputStream(compressed.size * 2)
                val buf = ByteArray(8192)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buf)
                    if (count > 0) {
                        out.write(buf, 0, count)
                    } else if (inflater.needsInput() || inflater.needsDictionary()) {
                        break
                    }
                }
                val bytes = out.toByteArray()
                if (bytes.isNotEmpty()) bytes else null
            } catch (_: Exception) {
                null
            } finally {
                inflater.end()
            }
        }
        return tryInflate(false) ?: tryInflate(true)
    }

    private fun parseToUnicodeCMap(cmapText: String, target: MutableMap<Int, String>) {
        // Parse <XXXX> <YYYY> entries inside beginbfchar .. endbfchar
        val bfCharBlock = Regex("""beginbfchar(.*?)endbfchar""", RegexOption.DOT_MATCHES_ALL)
        val pairRegex = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""")
        for (block in bfCharBlock.findAll(cmapText)) {
            for (m in pairRegex.findAll(block.groupValues[1])) {
                val src = m.groupValues[1].toIntOrNull(16) ?: continue
                val dst = decodeHexToUnicode(m.groupValues[2])
                if (dst.isNotEmpty()) target[src] = dst
            }
        }
        // Parse <START> <END> <DEST> entries inside beginbfrange .. endbfrange
        val bfRangeBlock = Regex("""beginbfrange(.*?)endbfrange""", RegexOption.DOT_MATCHES_ALL)
        val rangeRegex = Regex("""<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>""")
        for (block in bfRangeBlock.findAll(cmapText)) {
            for (m in rangeRegex.findAll(block.groupValues[1])) {
                val start = m.groupValues[1].toIntOrNull(16) ?: continue
                val end = m.groupValues[2].toIntOrNull(16) ?: continue
                var dstCode = m.groupValues[3].toIntOrNull(16) ?: continue
                if (end >= start && end - start <= 512) {
                    for (code in start..end) {
                        target[code] = dstCode.toChar().toString()
                        dstCode++
                    }
                }
            }
        }
    }

    private fun extractTextFromPdfContentStream(content: String, cmap: Map<Int, String>): String {
        val sb = StringBuilder()
        var i = 0
        val n = content.length

        while (i < n) {
            val ch = content[i]
            when (ch) {
                '(' -> {
                    val (literal, nextIdx) = readPdfLiteralString(content, i)
                    // Check if followed shortly by Tj, ', ", or inside a TJ array
                    sb.append(literal)
                    i = nextIdx
                }
                '<' -> {
                    if (i + 1 < n && content[i + 1] == '<') {
                        // Dictionary start <<
                        i += 2
                    } else {
                        val endHex = content.indexOf('>', i + 1)
                        if (endHex > i) {
                            val hex = content.substring(i + 1, endHex).replace(Regex("\\s+"), "")
                            if (hex.isNotEmpty() && hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                                sb.append(decodePdfHexString(hex, cmap))
                            }
                            i = endHex + 1
                        } else {
                            i++
                        }
                    }
                }
                'T' -> {
                    if (i + 1 < n && (content[i + 1] == 'd' || content[i + 1] == 'D' || content[i + 1] == '*')) {
                        sb.append(' ')
                        i += 2
                    } else {
                        i++
                    }
                }
                'E' -> {
                    if (i + 1 < n && content[i + 1] == 'T') {
                        sb.append(' ')
                        i += 2
                    } else {
                        i++
                    }
                }
                else -> i++
            }
        }
        return sb.toString()
    }

    private fun readPdfLiteralString(src: String, startIdx: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startIdx + 1
        var depth = 1
        val n = src.length
        while (i < n && depth > 0) {
            val c = src[i]
            when {
                c == '\\' && i + 1 < n -> {
                    val next = src[i + 1]
                    when (next) {
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'b' -> { sb.append('\b'); i += 2 }
                        'f' -> { sb.append('\u000C'); i += 2 }
                        '(', ')', '\\' -> { sb.append(next); i += 2 }
                        '\r', '\n' -> {
                            i += 2
                            if (next == '\r' && i < n && src[i] == '\n') i++
                        }
                        in '0'..'7' -> {
                            var oct = 0
                            var digits = 0
                            var j = i + 1
                            while (j < n && digits < 3 && src[j] in '0'..'7') {
                                oct = oct * 8 + (src[j] - '0')
                                j++
                                digits++
                            }
                            sb.append(oct.toChar())
                            i = j
                        }
                        else -> { sb.append(next); i += 2 }
                    }
                }
                c == '(' -> {
                    depth++
                    sb.append(c)
                    i++
                }
                c == ')' -> {
                    depth--
                    if (depth > 0) sb.append(c)
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString() to i
    }

    private fun decodePdfHexString(hex: String, cmap: Map<Int, String>): String {
        val padded = if (hex.length % 2 == 1) "${hex}0" else hex
        // UTF-16BE BOM check
        if (padded.length >= 4 && padded.startsWith("FEFF", ignoreCase = true)) {
            return decodeHexToUnicode(padded.substring(4))
        }
        // Try 2-byte CMap lookup first when length is multiple of 4 and CMap has entries
        if (cmap.isNotEmpty() && padded.length % 4 == 0) {
            val mapped = StringBuilder()
            var hits = 0
            var idx = 0
            while (idx + 4 <= padded.length) {
                val code = padded.substring(idx, idx + 4).toIntOrNull(16)
                val replacement = code?.let { cmap[it] }
                if (replacement != null) {
                    mapped.append(replacement)
                    hits++
                }
                idx += 4
            }
            if (hits > 0) return mapped.toString()
        }
        // Try 1-byte CMap or printable ASCII fallback
        val sb = StringBuilder()
        var idx = 0
        while (idx + 2 <= padded.length) {
            val code = padded.substring(idx, idx + 2).toIntOrNull(16) ?: 0
            val mapped = cmap[code]
            if (mapped != null) {
                sb.append(mapped)
            } else if (code in 32..126) {
                sb.append(code.toChar())
            }
            idx += 2
        }
        return sb.toString()
    }

    private fun decodeHexToUnicode(hex: String): String {
        val sb = StringBuilder()
        var idx = 0
        while (idx + 4 <= hex.length) {
            val code = hex.substring(idx, idx + 4).toIntOrNull(16)
            if (code != null && code > 0) {
                sb.append(code.toChar())
            }
            idx += 4
        }
        return sb.toString()
    }
}
