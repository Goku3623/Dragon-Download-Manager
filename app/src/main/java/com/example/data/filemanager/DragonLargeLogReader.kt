package com.example.data.filemanager

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale

enum class LogLineSeverity {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    PLAIN
}

enum class LogSeverityFilter(val label: String) {
    ALL("All"),
    ERROR("Errors"),
    WARN("Warn+"),
    INFO("Info+")
}

data class IndexedLogLine(
    val indexInChunk: Int,
    val approxLineNumber: Long,
    val byteOffset: Long,
    val text: String,
    val severity: LogLineSeverity
)

data class LogChunkPage(
    val startOffset: Long,
    val endOffset: Long,
    val totalBytes: Long,
    val chunkIndex: Int,
    val totalChunks: Int,
    val lines: List<IndexedLogLine>,
    val encodingName: String
) {
    val progressPercent: Int
        get() = if (totalBytes <= 0L) 100 else ((endOffset * 100L) / totalBytes).toInt().coerceIn(0, 100)
}

data class LogSearchMatch(
    val byteOffset: Long,
    val lineNumber: Long,
    val preview: String,
    val severity: LogLineSeverity
)

/**
 * High-performance chunked random-access reader for massive `.log` and large plain-text files
 * (from hundreds of KB up to multi-GB logs) with constant ~256 KB memory footprint and O(1) seek.
 */
object DragonLargeLogReader {

    const val DEFAULT_CHUNK_BYTES = 128 * 1024 // 128 KB per virtualized page (~1,200–2,000 lines)
    const val MAX_LINE_CHARS = 2048 // Prevent single 500 KB minified lines from stalling Compose Text layout

    fun shouldUseLargeLogViewer(fileExtension: String, fileSizeBytes: Long): Boolean {
        val ext = fileExtension.lowercase(Locale.US)
        if (ext == "log" && fileSizeBytes >= 24L * 1024L) return true
        if (fileSizeBytes >= 160L * 1024L) return true
        return false
    }

    fun getTotalFileBytes(context: Context, file: File): Long {
        if (file.exists() && file.canRead()) {
            val len = file.length()
            if (len >= 0L) return len
        }
        return AndroidDataObbBridge.estimateTotalBytesUniversal(context, file).coerceAtLeast(0L)
    }

    /**
     * Reads a clean newline-aligned chunk around [requestedStartOffset] in O(1) time via [RandomAccessFile],
     * falling back to stream skip for SAF / Android-data / root bridged paths.
     */
    fun readChunkAtOffset(
        context: Context,
        file: File,
        requestedStartOffset: Long,
        chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES,
        charsetName: String = "UTF-8"
    ): LogChunkPage {
        val totalBytes = getTotalFileBytes(context, file)
        val totalChunks = if (totalBytes <= 0L) 1 else ((totalBytes + chunkSizeBytes - 1L) / chunkSizeBytes).toInt().coerceAtLeast(1)

        if (totalBytes <= 0L) {
            return LogChunkPage(
                startOffset = 0L,
                endOffset = 0L,
                totalBytes = 0L,
                chunkIndex = 0,
                totalChunks = 1,
                lines = emptyList(),
                encodingName = charsetName
            )
        }

        val clampedStart = requestedStartOffset.coerceIn(0L, (totalBytes - 1L).coerceAtLeast(0L))
        val desiredLen = minOf(chunkSizeBytes.toLong(), totalBytes - clampedStart).toInt().coerceAtLeast(0)

        // Read slightly extra bytes so we can align cleanly to the previous/next newline boundary
        val lookback = if (clampedStart > 0L) minOf(4096L, clampedStart) else 0L
        val rawReadStart = clampedStart - lookback
        val lookahead = minOf(4096L, (totalBytes - (clampedStart + desiredLen)).coerceAtLeast(0L))
        val rawReadLen = (lookback + desiredLen + lookahead).toInt().coerceAtLeast(0)

        val rawBuffer = readByteSlice(context, file, rawReadStart, rawReadLen)
        if (rawBuffer.isEmpty()) {
            return LogChunkPage(
                startOffset = clampedStart,
                endOffset = clampedStart,
                totalBytes = totalBytes,
                chunkIndex = (clampedStart / chunkSizeBytes).toInt().coerceIn(0, totalChunks - 1),
                totalChunks = totalChunks,
                lines = emptyList(),
                encodingName = charsetName
            )
        }

        // Align start to newline boundary if we are mid-file
        var sliceStartInBuf = lookback.toInt().coerceIn(0, rawBuffer.size)
        if (clampedStart > 0L && sliceStartInBuf > 0) {
            // Find the newline right after or right before sliceStartInBuf so lines are never cut mid-line
            var nlForward = sliceStartInBuf
            val searchLimit = minOf(rawBuffer.size, sliceStartInBuf + 4096)
            while (nlForward < searchLimit && rawBuffer[nlForward] != '\n'.code.toByte()) {
                nlForward++
            }
            if (nlForward < searchLimit && nlForward + 1 < rawBuffer.size) {
                sliceStartInBuf = nlForward + 1
            } else {
                // Fallback: look backward for newline
                var nlBack = sliceStartInBuf - 1
                while (nlBack >= 0 && rawBuffer[nlBack] != '\n'.code.toByte()) {
                    nlBack--
                }
                if (nlBack >= 0) {
                    sliceStartInBuf = nlBack + 1
                }
            }
        }

        // Align end to newline boundary if not at EOF
        var sliceEndInBuf = (lookback.toInt() + desiredLen).coerceIn(sliceStartInBuf, rawBuffer.size)
        val isAtEof = (rawReadStart + sliceEndInBuf) >= totalBytes
        if (!isAtEof && sliceEndInBuf < rawBuffer.size) {
            var nlForward = sliceEndInBuf
            while (nlForward < rawBuffer.size && rawBuffer[nlForward] != '\n'.code.toByte()) {
                nlForward++
            }
            if (nlForward < rawBuffer.size) {
                sliceEndInBuf = nlForward + 1
            }
        }

        val alignedStartOffset = rawReadStart + sliceStartInBuf
        val alignedEndOffset = rawReadStart + sliceEndInBuf
        val actualSliceLen = (sliceEndInBuf - sliceStartInBuf).coerceAtLeast(0)

        val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
        val decoded = if (actualSliceLen > 0) {
            String(rawBuffer, sliceStartInBuf, actualSliceLen, charset).removePrefix("\uFEFF")
        } else ""

        val lines = parseDecodedChunkToLines(
            decoded = decoded,
            alignedStartOffset = alignedStartOffset,
            chunkSizeBytes = chunkSizeBytes
        )

        val chunkIdx = (alignedStartOffset / chunkSizeBytes).toInt().coerceIn(0, totalChunks - 1)
        return LogChunkPage(
            startOffset = alignedStartOffset,
            endOffset = alignedEndOffset,
            totalBytes = totalBytes,
            chunkIndex = chunkIdx,
            totalChunks = totalChunks,
            lines = lines,
            encodingName = charsetName
        )
    }

    /**
     * Reads the final [chunkSizeBytes] of the file (Tail view — ideal for viewing the newest crash / error entries).
     */
    fun readTailChunk(
        context: Context,
        file: File,
        chunkSizeBytes: Int = DEFAULT_CHUNK_BYTES,
        charsetName: String = "UTF-8"
    ): LogChunkPage {
        val totalBytes = getTotalFileBytes(context, file)
        val tailStart = (totalBytes - chunkSizeBytes).coerceAtLeast(0L)
        return readChunkAtOffset(
            context = context,
            file = file,
            requestedStartOffset = tailStart,
            chunkSizeBytes = chunkSizeBytes,
            charsetName = charsetName
        )
    }

    /**
     * Streams across the ENTIRE file on a background thread with constant memory (256 KB buffer)
     * and returns up to [maxResults] matching lines along with their exact byte offsets for instant jump.
     */
    fun searchAcrossWholeFile(
        context: Context,
        file: File,
        query: String,
        severityFilter: LogSeverityFilter = LogSeverityFilter.ALL,
        ignoreCase: Boolean = true,
        maxResults: Int = 350,
        isCancelled: () -> Boolean = { false }
    ): List<LogSearchMatch> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty() && severityFilter == LogSeverityFilter.ALL) return emptyList()

        val results = ArrayList<LogSearchMatch>(minOf(maxResults, 128))
        val stream = AndroidDataObbBridge.openInputStreamForFile(context, file) ?: return emptyList()
        stream.use { rawIn ->
            val bis = BufferedInputStream(rawIn, 256 * 1024)
            val buf = ByteArray(128 * 1024)
            val lineBytes = java.io.ByteArrayOutputStream(512)
            var fileOffset = 0L
            var currentLineStartOffset = 0L
            var lineNumber = 1L

            fun evaluateCurrentLine() {
                if (lineBytes.size() == 0) return
                val rawLine = lineBytes.toString("UTF-8").trimEnd('\r')
                val sev = classifyLogLineSeverity(rawLine)
                if (!matchesSeverityFilter(sev, severityFilter)) return
                val textMatches = if (cleanQuery.isEmpty()) true else rawLine.contains(cleanQuery, ignoreCase = ignoreCase)
                if (textMatches) {
                    results.add(
                        LogSearchMatch(
                            byteOffset = currentLineStartOffset,
                            lineNumber = lineNumber,
                            preview = if (rawLine.length > 260) rawLine.substring(0, 260) + "…" else rawLine,
                            severity = sev
                        )
                    )
                }
            }

            while (!isCancelled() && results.size < maxResults) {
                val read = bis.read(buf)
                if (read <= 0) break
                var i = 0
                while (i < read && results.size < maxResults) {
                    val b = buf[i]
                    if (b == '\n'.code.toByte()) {
                        evaluateCurrentLine()
                        lineBytes.reset()
                        lineNumber++
                        currentLineStartOffset = fileOffset + i + 1L
                    } else if (lineBytes.size() < 4096) {
                        lineBytes.write(b.toInt() and 0xFF)
                    }
                    i++
                }
                fileOffset += read
            }
            if (!isCancelled() && results.size < maxResults && lineBytes.size() > 0) {
                evaluateCurrentLine()
            }
        }
        return results
    }

    fun matchesSeverityFilter(severity: LogLineSeverity, filter: LogSeverityFilter): Boolean {
        return when (filter) {
            LogSeverityFilter.ALL -> true
            LogSeverityFilter.ERROR -> severity == LogLineSeverity.ERROR
            LogSeverityFilter.WARN -> severity == LogLineSeverity.ERROR || severity == LogLineSeverity.WARN
            LogSeverityFilter.INFO -> severity == LogLineSeverity.ERROR ||
                severity == LogLineSeverity.WARN ||
                severity == LogLineSeverity.INFO
        }
    }

    fun classifyLogLineSeverity(line: String): LogLineSeverity {
        if (line.isBlank()) return LogLineSeverity.PLAIN
        val upper = if (line.length > 160) line.substring(0, 160).uppercase(Locale.US) else line.uppercase(Locale.US)
        return when {
            upper.contains(" E/") || upper.startsWith("E/") ||
                upper.contains(" F/") || upper.startsWith("F/") ||
                upper.contains(" E ") || upper.contains(" F ") ||
                upper.contains("[ERROR]") || upper.contains(" ERROR ") || upper.contains("ERROR:") ||
                upper.contains("FATAL") || upper.contains("EXCEPTION") ||
                upper.contains("CRASH") || upper.contains("SEGFAULT") ||
                upper.contains("ANR IN ") || upper.contains("CAUSED BY:") -> LogLineSeverity.ERROR

            upper.contains(" W/") || upper.startsWith("W/") ||
                upper.contains(" W ") ||
                upper.contains("[WARN]") || upper.contains(" WARN ") || upper.contains("WARNING") -> LogLineSeverity.WARN

            upper.contains(" I/") || upper.startsWith("I/") ||
                upper.contains(" I ") ||
                upper.contains("[INFO]") || upper.contains(" INFO ") -> LogLineSeverity.INFO

            upper.contains(" D/") || upper.startsWith("D/") ||
                upper.contains(" V/") || upper.startsWith("V/") ||
                upper.contains(" D ") || upper.contains(" V ") ||
                upper.contains("[DEBUG]") || upper.contains(" DEBUG ") -> LogLineSeverity.DEBUG

            else -> LogLineSeverity.PLAIN
        }
    }

    private fun parseDecodedChunkToLines(
        decoded: String,
        alignedStartOffset: Long,
        chunkSizeBytes: Int
    ): List<IndexedLogLine> {
        if (decoded.isEmpty()) return emptyList()
        val rawLines = decoded.split('\n')
        val result = ArrayList<IndexedLogLine>(rawLines.size)
        // Approximate starting line number when mid-file (~90 bytes/line avg) for display context; exact when starting at 0
        val baseLineNum = if (alignedStartOffset == 0L) 1L else (alignedStartOffset / 90L).coerceAtLeast(1L)
        var runningByteOffset = alignedStartOffset

        for (idx in rawLines.indices) {
            val raw = rawLines[idx].trimEnd('\r')
            if (idx == rawLines.lastIndex && raw.isEmpty()) continue
            val safeText = if (raw.length > MAX_LINE_CHARS) {
                raw.substring(0, MAX_LINE_CHARS) + " … (+${raw.length - MAX_LINE_CHARS} chars)"
            } else {
                raw
            }
            result.add(
                IndexedLogLine(
                    indexInChunk = idx,
                    approxLineNumber = baseLineNum + idx,
                    byteOffset = runningByteOffset,
                    text = safeText,
                    severity = classifyLogLineSeverity(safeText)
                )
            )
            runningByteOffset += raw.length + 1
        }
        return result
    }

    private fun readByteSlice(context: Context, file: File, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        // Fast O(1) RandomAccessFile path for standard local files
        if (file.exists() && file.canRead()) {
            val rafResult = runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    val fileLen = raf.length()
                    if (offset >= fileLen) return@use ByteArray(0)
                    val toRead = minOf(length.toLong(), fileLen - offset).toInt().coerceAtLeast(0)
                    val out = ByteArray(toRead)
                    raf.seek(offset)
                    var totalRead = 0
                    while (totalRead < toRead) {
                        val r = raf.read(out, totalRead, toRead - totalRead)
                        if (r <= 0) break
                        totalRead += r
                    }
                    if (totalRead == toRead) out else out.copyOf(totalRead)
                }
            }.getOrNull()
            if (rafResult != null) return rafResult
        }

        // Fallback streaming skip for SAF / Android-data / root paths
        return runCatching {
            val stream = AndroidDataObbBridge.openInputStreamForFile(context, file) ?: return@runCatching ByteArray(0)
            stream.use { input ->
                var remainingSkip = offset
                val skipBuf = ByteArray(64 * 1024)
                while (remainingSkip > 0L) {
                    val skipped = input.skip(remainingSkip)
                    if (skipped > 0L) {
                        remainingSkip -= skipped
                    } else {
                        val r = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remainingSkip).toInt())
                        if (r <= 0) return@use ByteArray(0)
                        remainingSkip -= r
                    }
                }
                val out = ByteArray(length)
                var totalRead = 0
                while (totalRead < length) {
                    val r = input.read(out, totalRead, length - totalRead)
                    if (r <= 0) break
                    totalRead += r
                }
                if (totalRead == length) out else out.copyOf(totalRead)
            }
        }.getOrDefault(ByteArray(0))
    }
}
