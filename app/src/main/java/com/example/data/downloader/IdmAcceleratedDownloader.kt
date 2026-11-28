package com.example.data.downloader

import android.os.SystemClock
import com.example.data.model.DownloaderErrorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToLong

/**
 * IDM-grade multi-connection HTTP Range & parallel HLS download accelerator.
 *
 * Features:
 * - High-concurrency OkHttp Dispatcher (up to 64 total, 32 per host) and 32-socket ConnectionPool.
 * - Dynamic 1-to-16 parallel HTTP Range chunking (`bytes=start-end`) for files >= 512 KB.
 * - Per-chunk resumable `.part` files with metadata verification so Pause/Resume picks up mid-chunk.
 * - Sliding-window + EMA real-time network bandwidth estimator (`SpeedWindowTracker`) that excludes
 *   DNS/TLS handshake latency, decouples socket reads from disk flushes via 256 KB buffered streams,
 *   and updates live every 350ms.
 * - Automatic retry per chunk and seamless fallback to single-connection 256 KB buffered streaming if
 *   a server ignores Range headers or returns 200 OK instead of 206 Partial Content.
 * - Ordered parallel HLS segment downloader (up to 8 concurrent segment workers + AES-128 decryption)
 *   with live per-chunk byte counting.
 */
class IdmAcceleratedDownloader(
    private val httpClient: OkHttpClient
) {
    /**
     * Dedicated HTTP/1.1 multi-socket client for parallel segmented Range workers.
     * Forcing HTTP/1.1 prevents OkHttp from multiplexing all 8-16 Range workers onto a single
     * HTTP/2 TCP connection, unlocking full aggregate throughput on per-socket throttled CDNs
     * such as Telegram CDN (telesco.pe / api.telegram.org) and file-to-link proxies.
     */
    private val multiSocketHttp1Client: OkHttpClient by lazy {
        val workerDispatcher = Dispatcher().apply {
            maxRequests = 128
            maxRequestsPerHost = 64
        }
        httpClient.newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .dispatcher(workerDispatcher)
            .connectionPool(ConnectionPool(64, 90, TimeUnit.SECONDS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
    data class IdmProgressUpdate(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
        val activeConnections: Int,
        val segmentProgress: List<Float>,
        val supportsResume: Boolean
    )

    sealed class DownloadOutcome {
        data class Completed(
            val downloadedBytes: Long,
            val activeConnectionsUsed: Int,
            val contentType: String,
            val resolvedUrl: String,
            val supportsResume: Boolean
        ) : DownloadOutcome()

        data class HlsManifestDetected(
            val resolvedUrl: String,
            val playlistText: String
        ) : DownloadOutcome()
    }

    data class HlsSegmentSpec(
        val url: String,
        val keyUri: String?,
        val ivHex: String?,
        val sequenceNumber: Long,
        val byteRangeStart: Long = -1L,
        val byteRangeLength: Long = -1L
    )

    private data class UrlProbeMeta(
        val resolvedUrl: String,
        val totalBytes: Long,
        val hasAuthoritativeTotalBytes: Boolean,
        val acceptRanges: Boolean,
        val contentType: String
    )

    private class RangeNotHonoredException(message: String = "Server did not honor multi-connection Range request") :
        IOException(message)

    /**
     * Thread-safe sliding-window + EMA bandwidth tracker.
     * - Starts the clock only when the first actual payload byte arrives so DNS/TLS/HTTP connection
     *   setup time never drags down the initial speed reading.
     * - Computes throughput over a rolling [windowMs] (default 2000ms) window and smooths transient
     *   kernel socket burst jitter while remaining immediately responsive to real speed changes.
     * - Decays smoothly toward 0 B/s if no bytes are received for > 1200ms.
     */
    internal class SpeedWindowTracker(
        initialBytes: Long,
        private val windowMs: Long = 2_000L
    ) {
        private data class Sample(val timeMs: Long, val totalBytes: Long)

        private val lock = Any()
        private val samples = ArrayDeque<Sample>(32)
        private var baselineBytes: Long = initialBytes
        private var lastZeroBytesTimeMs: Long = 0L
        private var lastByteReceivedMs: Long = 0L
        private var lastKnownBytes: Long = initialBytes
        private var smoothedBps: Double = 0.0

        fun onStreamConnected(nowMs: Long = SystemClock.elapsedRealtime()) {
            synchronized(lock) {
                if (samples.isEmpty()) {
                    lastZeroBytesTimeMs = nowMs
                }
            }
        }

        fun sampleSpeedBps(currentTotalBytes: Long, nowMs: Long = SystemClock.elapsedRealtime()): Long {
            synchronized(lock) {
                val safeBytes = currentTotalBytes.coerceAtLeast(baselineBytes)
                if (safeBytes > lastKnownBytes) {
                    if (samples.isEmpty()) {
                        val gapFromZero = if (lastZeroBytesTimeMs > 0L) nowMs - lastZeroBytesTimeMs else -1L
                        val anchorMs = if (gapFromZero in 1L..1_500L) {
                            lastZeroBytesTimeMs
                        } else {
                            (nowMs - 250L).coerceAtLeast(0L)
                        }
                        samples.addLast(Sample(anchorMs, lastKnownBytes))
                    }
                    lastKnownBytes = safeBytes
                    lastByteReceivedMs = nowMs
                    samples.addLast(Sample(nowMs, safeBytes))
                } else if (samples.isEmpty()) {
                    lastZeroBytesTimeMs = nowMs
                    return 0L
                }

                // Prune samples older than windowMs while keeping at least one anchor sample
                val cutoffMs = nowMs - windowMs
                while (samples.size > 2 && samples.first.timeMs < cutoffMs) {
                    samples.removeFirst()
                }

                val stallDurationMs = if (lastByteReceivedMs > 0L) nowMs - lastByteReceivedMs else 0L
                if (stallDurationMs > 2_500L) {
                    smoothedBps = 0.0
                    samples.clear()
                    baselineBytes = safeBytes
                    lastKnownBytes = safeBytes
                    lastZeroBytesTimeMs = nowMs
                    return 0L
                }

                val oldest = samples.first
                val elapsedMs = (nowMs - oldest.timeMs).coerceAtLeast(50L)
                val deltaBytes = (safeBytes - oldest.totalBytes).coerceAtLeast(0L)
                val windowBps = (deltaBytes * 1000.0) / elapsedMs.toDouble()

                smoothedBps = when {
                    smoothedBps <= 0.0 -> windowBps
                    stallDurationMs > 1_100L -> smoothedBps * 0.55
                    else -> (windowBps * 0.80) + (smoothedBps * 0.20)
                }

                return smoothedBps.roundToLong().coerceAtLeast(0L)
            }
        }
    }

    companion object {
        private const val CHUNK_THRESHOLD_BYTES = 512L * 1024L // 512 KB
        private const val TELEGRAM_CHUNK_THRESHOLD_BYTES = 128L * 1024L // 128 KB for Telegram CDNs
        private const val CHUNK_BUFFER_BYTES = 256 * 1024 // 256 KB socket read buffer per worker
        private const val WRITE_COALESCE_BYTES = 512 * 1024 // 512 KB lock-free FileChannel pwrite coalescing
        private const val SINGLE_BUFFER_BYTES = 256 * 1024 // 256 KB single stream
        private const val MICRO_CHUNK_TARGET_BYTES = 2L * 1024L * 1024L // 2 MB dynamic work-stealing units
        private const val TELEGRAM_MICRO_CHUNK_TARGET_BYTES = 1L * 1024L * 1024L // 1 MB fine-grained Telegram work-stealing
        private const val MAX_MICRO_CHUNKS = 128
        private const val LARGE_FILE_MAX_MICRO_CHUNKS = 256
        private const val TELEGRAM_MAX_MICRO_CHUNKS = 128
        private const val STALL_WATCHDOG_MS = 12_000L // Cancel a single hung socket after 12s if peers are active
        private const val CHUNK_STATE_PENDING = 0
        private const val CHUNK_STATE_ACTIVE = 1
        private const val CHUNK_STATE_DONE = 2
        internal const val REPORT_INTERVAL_MS = 350L

        fun isTelegramAcceleratedHost(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("telesco.pe") ||
                lower.contains("telegram-cdn.org") ||
                lower.contains("api.telegram.org") ||
                lower.contains("t.me/") ||
                lower.contains("telegram.org") ||
                lower.contains("telegram.me") ||
                lower.contains("telegram.dog")
        }

        fun chunkDirFor(workDir: File, destFile: File): File {
            val safeName = destFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "stream" }
            return File(workDir, "idm_chunks_$safeName")
        }

        fun buildSegmentMeta(totalBytes: Long, segmentCount: Int, url: String): String {
            return "$totalBytes|$segmentCount|${url.hashCode()}"
        }

        internal fun parseSavedChunkCountIfCompatible(metaFile: File, expectedTotalBytes: Long): Int? {
            if (!metaFile.exists() || expectedTotalBytes <= 0L) return null
            return runCatching {
                val parts = metaFile.readText().trim().split("|")
                val savedTotalBytes = parts.getOrNull(0)?.toLongOrNull() ?: return null
                val savedChunks = parts.getOrNull(1)?.toIntOrNull() ?: return null
                if (savedTotalBytes == expectedTotalBytes && savedChunks in 1..256) {
                    savedChunks
                } else {
                    null
                }
            }.getOrNull()
        }

        internal fun parseRetryAfterMs(resp: okhttp3.Response, defaultBackoffMs: Long): Long {
            val header = resp.header("Retry-After")?.trim()
            val seconds = header?.toLongOrNull()
            return if (seconds != null && seconds >= 0L) {
                (seconds * 1000L).coerceIn(400L, 12_000L)
            } else {
                defaultBackoffMs
            }
        }

        internal fun applySmartRequestHeaders(
            url: String,
            headers: Map<String, String>,
            attempt: Int,
            reqBuilder: Request.Builder
        ) {
            var hasUserAgent = false
            var hasAccept = false
            var hasReferer = false
            headers.forEach { (k, v) ->
                if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                if (k.equals("Accept", ignoreCase = true)) hasAccept = true
                if (k.equals("Referer", ignoreCase = true)) hasReferer = true
                reqBuilder.header(k, v)
            }
            if (!hasUserAgent) {
                reqBuilder.header("User-Agent", MediaExtractionEngine.MOBILE_USER_AGENT)
            }
            if (!hasAccept) {
                reqBuilder.header("Accept", "*/*")
            }
            if (attempt >= 1 && !hasReferer && !url.contains("googlevideo.com", ignoreCase = true)) {
                runCatching {
                    val uri = java.net.URI(url)
                    if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                        reqBuilder.header("Referer", "${uri.scheme}://${uri.host}/")
                    }
                }
            }
        }

        fun createFastOkHttpClient(dns: okhttp3.Dns = okhttp3.Dns.SYSTEM): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 64
            }
            val connectionPool = ConnectionPool(64, 120, TimeUnit.SECONDS)
            return OkHttpClient.Builder()
                .dns(dns)
                .proxySelector(com.example.data.browser.BrowserProxyManager.okHttpProxySelector)
                .proxyAuthenticator(com.example.data.browser.BrowserProxyManager.okHttpProxyAuthenticator)
                .dispatcher(dispatcher)
                .connectionPool(connectionPool)
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }

        fun calculateOptimalConnections(
            totalBytes: Long,
            maxConnections: Int,
            isTelegramHost: Boolean = false
        ): Int {
            val cap = maxConnections.coerceIn(1, 16)
            val threshold = if (isTelegramHost) TELEGRAM_CHUNK_THRESHOLD_BYTES else CHUNK_THRESHOLD_BYTES
            if (cap <= 1 || totalBytes < threshold) return 1
            if (isTelegramHost) {
                return when {
                    totalBytes < 512L * 1024L -> cap.coerceAtMost(4)
                    totalBytes < 2L * 1024L * 1024L -> cap.coerceAtMost(8)
                    else -> cap
                }.coerceAtLeast(1)
            }
            return when {
                totalBytes < 2L * 1024L * 1024L -> cap.coerceAtMost(4)
                totalBytes < 20L * 1024L * 1024L -> cap.coerceAtMost(8)
                else -> cap
            }.coerceAtLeast(1)
        }
    }

    suspend fun downloadWithAcceleration(
        url: String,
        destFile: File,
        workDir: File,
        maxConnections: Int,
        knownTotalBytes: Long,
        rejectHtml: Boolean,
        requestHeadersProvider: (targetUrl: String) -> Map<String, String>,
        isPausedOrCancelled: () -> Boolean,
        onSmartRetry: ((attempt: Int, maxAttempts: Int, reason: String) -> Unit)? = null,
        onAssembling: (() -> Unit)? = null,
        onProgress: (IdmProgressUpdate) -> Unit
    ): DownloadOutcome {
        if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")

        val probe = probeRangeSupport(
            url = url,
            knownTotalBytes = knownTotalBytes,
            maxConnections = maxConnections,
            requestHeadersProvider = requestHeadersProvider
        )

        val lowerCt = probe.contentType.lowercase(Locale.US)
        if (lowerCt.contains("mpegurl") || lowerCt.contains("vnd.apple.mpegurl")) {
            val text = fetchTextBody(probe.resolvedUrl, requestHeadersProvider(probe.resolvedUrl))
            if (text.contains("#EXTM3U")) {
                return DownloadOutcome.HlsManifestDetected(probe.resolvedUrl, text)
            }
        }
        if (rejectHtml && lowerCt.contains("text/html")) {
            throw ExtractorException(
                DownloaderErrorType.UNSUPPORTED_MEDIA,
                "Server returned an HTML page instead of a binary file."
            )
        }

        val isTelegramHost = isTelegramAcceleratedHost(probe.resolvedUrl) || isTelegramAcceleratedHost(url)
        val effectiveMaxConns = if (isTelegramHost) 16 else maxConnections
        val minChunkThreshold = if (isTelegramHost) TELEGRAM_CHUNK_THRESHOLD_BYTES else CHUNK_THRESHOLD_BYTES

        val initialSegmentCount = if (probe.acceptRanges &&
            probe.hasAuthoritativeTotalBytes &&
            probe.totalBytes >= minChunkThreshold
        ) {
            calculateOptimalConnections(probe.totalBytes, effectiveMaxConns, isTelegramHost = isTelegramHost)
        } else {
            1
        }

        if (initialSegmentCount > 1) {
            // Smart Retry connection step-down ladder (e.g., 16x -> 8x -> 4x -> 2x)
            val candidateCounts = LinkedHashSet<Int>().apply {
                add(initialSegmentCount)
                if (initialSegmentCount > 4) add((initialSegmentCount / 2).coerceAtLeast(4))
                if (initialSegmentCount > 2) add(2)
            }.toList()

            for ((tierIdx, segCount) in candidateCounts.withIndex()) {
                try {
                    if (tierIdx > 0) {
                        onSmartRetry?.invoke(
                            tierIdx,
                            candidateCounts.size,
                            "Stepping down to ${segCount}x DDM threads for server stability"
                        )
                        delay(450L * tierIdx)
                    }
                    val segResult = downloadSegmentedParallel(
                        url = probe.resolvedUrl,
                        destFile = destFile,
                        workDir = workDir,
                        totalBytes = probe.totalBytes,
                        segmentCount = segCount,
                        requestHeadersProvider = requestHeadersProvider,
                        isPausedOrCancelled = isPausedOrCancelled,
                        onSmartRetry = onSmartRetry,
                        onAssembling = onAssembling,
                        onProgress = onProgress
                    )
                    return DownloadOutcome.Completed(
                        downloadedBytes = segResult.downloadedBytes,
                        activeConnectionsUsed = segResult.effectiveConnectionsUsed,
                        contentType = probe.contentType,
                        resolvedUrl = probe.resolvedUrl,
                        supportsResume = true
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (rnhe: RangeNotHonoredException) {
                    if (isPausedOrCancelled()) {
                        throw CancellationException("Download paused or cancelled")
                    }
                    // Server rejected Range headers before any bytes were downloaded; break to single-stream fallback
                    try {
                        chunkDirFor(workDir, destFile).deleteRecursively()
                    } catch (_: Exception) {}
                    if (destFile.exists()) {
                        try { destFile.delete() } catch (_: Exception) {}
                    }
                    break
                } catch (_: Exception) {
                    if (isPausedOrCancelled()) {
                        throw CancellationException("Download paused or cancelled")
                    }
                    // Preserve chunkDir (payload.part + progress.bin + segments.meta) so 100% of
                    // downloaded bytes resume seamlessly on the next step-down tier!
                    if (destFile.exists()) {
                        try { destFile.delete() } catch (_: Exception) {}
                    }
                }
            }
        }

        val singleDownloaded = downloadSingleStream(
            url = probe.resolvedUrl,
            destFile = destFile,
            knownTotalBytes = probe.totalBytes,
            rejectHtml = rejectHtml,
            requestHeadersProvider = requestHeadersProvider,
            isPausedOrCancelled = isPausedOrCancelled,
            onSmartRetry = onSmartRetry,
            onProgress = onProgress
        )
        return when (singleDownloaded) {
            is SingleStreamResult.HlsDetected -> DownloadOutcome.HlsManifestDetected(
                resolvedUrl = singleDownloaded.resolvedUrl,
                playlistText = singleDownloaded.playlistText
            )
            is SingleStreamResult.Bytes -> DownloadOutcome.Completed(
                downloadedBytes = singleDownloaded.count,
                activeConnectionsUsed = 1,
                contentType = singleDownloaded.contentType.ifBlank { probe.contentType },
                resolvedUrl = singleDownloaded.resolvedUrl,
                supportsResume = singleDownloaded.supportsResume
            )
        }
    }

    private fun probeRangeSupport(
        url: String,
        knownTotalBytes: Long,
        maxConnections: Int,
        requestHeadersProvider: (String) -> Map<String, String>
    ): UrlProbeMeta {
        val headers = requestHeadersProvider(url)
        var resolvedUrl = url
        var totalBytes = knownTotalBytes
        var hasAuthoritativeTotal = false
        var acceptRanges = false
        var contentType = ""

        // 1. Fast HEAD probe
        try {
            val headReq = Request.Builder()
                .url(url)
                .head()
                .header("Accept-Encoding", "identity")
            headers.forEach { (k, v) -> headReq.header(k, v) }

            httpClient.newCall(headReq.build()).execute().use { resp ->
                if (resp.isSuccessful || resp.code == 206) {
                    resolvedUrl = resp.request.url.toString()
                    contentType = resp.header("Content-Type") ?: ""
                    val contentEncoding = resp.header("Content-Encoding")?.lowercase(Locale.US) ?: ""
                    val isCompressed = contentEncoding.isNotBlank() && contentEncoding != "identity"
                    val cl = if (!isCompressed) resp.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
                    if (cl > 0L) {
                        totalBytes = cl
                        hasAuthoritativeTotal = true
                    }
                    val ar = resp.header("Accept-Ranges") ?: ""
                    acceptRanges = !isCompressed && ar.contains("bytes", ignoreCase = true)
                    if (contentType.contains("mpegurl", true)) {
                        return UrlProbeMeta(resolvedUrl, totalBytes, hasAuthoritativeTotal, false, contentType)
                    }
                    if (contentType.contains("text/html", true)) {
                        // Some file hosts reject HEAD with an HTML body; clear contentType so GET probe verifies
                        contentType = ""
                        acceptRanges = false
                        hasAuthoritativeTotal = false
                    } else if (acceptRanges && hasAuthoritativeTotal && totalBytes > 0L) {
                        return UrlProbeMeta(resolvedUrl, totalBytes, true, true, contentType)
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. If HEAD didn't give authoritative totalBytes & Accept-Ranges (and user wants >1 connection), probe with Range: bytes=0-0
        if (maxConnections > 1 && (!acceptRanges || !hasAuthoritativeTotal || totalBytes <= 0L)) {
            try {
                val rangeHeaders = requestHeadersProvider(resolvedUrl)
                val rangeReq = Request.Builder()
                    .url(resolvedUrl)
                    .get()
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=0-0")
                rangeHeaders.forEach { (k, v) -> rangeReq.header(k, v) }

                httpClient.newCall(rangeReq.build()).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 206) {
                        resolvedUrl = resp.request.url.toString()
                        val ct = resp.header("Content-Type") ?: ""
                        if (ct.isNotBlank()) contentType = ct
                        val contentEncoding = resp.header("Content-Encoding")?.lowercase(Locale.US) ?: ""
                        val isCompressed = contentEncoding.isNotBlank() && contentEncoding != "identity"

                        if (resp.code == 206 && !isCompressed) {
                            acceptRanges = true
                            val cr = resp.header("Content-Range")
                            val crTotal = cr?.substringAfterLast("/")?.trim()?.toLongOrNull() ?: -1L
                            if (crTotal > 0L) {
                                totalBytes = crTotal
                                hasAuthoritativeTotal = true
                            }
                        } else if (resp.code == 200) {
                            // Server ignored Range: bytes=0-0 and began streaming the whole file
                            acceptRanges = false
                            if (!isCompressed) {
                                val cl = resp.header("Content-Length")?.toLongOrNull() ?: -1L
                                if (cl > 0L) {
                                    totalBytes = cl
                                    hasAuthoritativeTotal = true
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return UrlProbeMeta(
            resolvedUrl = resolvedUrl,
            totalBytes = totalBytes,
            hasAuthoritativeTotalBytes = hasAuthoritativeTotal,
            acceptRanges = acceptRanges,
            contentType = contentType
        )
    }

    private fun fetchTextBody(url: String, headers: Map<String, String>): String {
        val req = Request.Builder().url(url)
        headers.forEach { (k, v) -> req.header(k, v) }
        return httpClient.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) "" else (resp.body?.string() ?: "")
        }
    }

    private data class SegmentedDownloadResult(
        val downloadedBytes: Long,
        val effectiveConnectionsUsed: Int
    )

    private fun saveProgressLedgerAtomic(
        chunkDir: File,
        progressFile: File,
        chunkDiskBytes: AtomicLongArray
    ) {
        runCatching {
            if (!chunkDir.exists()) chunkDir.mkdirs()
            val tmpFile = File(chunkDir, "progress.bin.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmpFile, false), 8 * 1024)).use { dos ->
                dos.writeInt(chunkDiskBytes.length())
                for (i in 0 until chunkDiskBytes.length()) {
                    dos.writeLong(chunkDiskBytes.get(i))
                }
                dos.flush()
            }
            if (!tmpFile.renameTo(progressFile)) {
                tmpFile.copyTo(progressFile, overwrite = true)
                tmpFile.delete()
            }
        }
    }

    private fun loadProgressLedgerIfValid(
        progressFile: File,
        expectedChunkCount: Int,
        chunkExpectedLengths: LongArray
    ): LongArray? {
        if (!progressFile.exists() || progressFile.length() < 4L + expectedChunkCount * 8L) return null
        return runCatching {
            DataInputStream(FileInputStream(progressFile)).use { dis ->
                val count = dis.readInt()
                if (count != expectedChunkCount) return null
                LongArray(expectedChunkCount) { i ->
                    dis.readLong().coerceIn(0L, chunkExpectedLengths[i])
                }
            }
        }.getOrNull()
    }

    private suspend fun downloadSegmentedParallel(
        url: String,
        destFile: File,
        workDir: File,
        totalBytes: Long,
        segmentCount: Int,
        requestHeadersProvider: (String) -> Map<String, String>,
        isPausedOrCancelled: () -> Boolean,
        onSmartRetry: ((attempt: Int, maxAttempts: Int, reason: String) -> Unit)? = null,
        onAssembling: (() -> Unit)? = null,
        onProgress: (IdmProgressUpdate) -> Unit
    ): SegmentedDownloadResult {
        val isTelegramHost = isTelegramAcceleratedHost(url)
        val minSizeForMicroChunking = if (isTelegramHost) 1L * 1024L * 1024L else 6L * 1024L * 1024L
        val targetChunkSize = when {
            isTelegramHost -> TELEGRAM_MICRO_CHUNK_TARGET_BYTES
            totalBytes >= 1024L * 1024L * 1024L -> 24L * 1024L * 1024L // ~24 MB chunks for >= 1 GB files
            totalBytes >= 128L * 1024L * 1024L -> 8L * 1024L * 1024L   // ~8 MB chunks for 128 MB..1 GB files
            else -> MICRO_CHUNK_TARGET_BYTES                           // 2 MB chunks for 6 MB..128 MB files
        }
        val maxChunksCap = when {
            isTelegramHost -> TELEGRAM_MAX_MICRO_CHUNKS
            totalBytes >= 512L * 1024L * 1024L -> LARGE_FILE_MAX_MICRO_CHUNKS
            else -> MAX_MICRO_CHUNKS
        }

        val chunkDir = chunkDirFor(workDir, destFile).apply { mkdirs() }
        val metaFile = File(chunkDir, "segments.meta")
        val progressFile = File(chunkDir, "progress.bin")
        val payloadPartFile = File(chunkDir, "payload.part")

        // Preserve existing chunk layout if totalBytes matches so connection step-downs,
        // pause/resume, or refreshed CDN URLs NEVER discard already-downloaded bytes!
        val compatibleSavedChunks = parseSavedChunkCountIfCompatible(metaFile, totalBytes)
        val totalChunks = compatibleSavedChunks ?: if (totalBytes >= minSizeForMicroChunking && segmentCount >= 2) {
            val byTargetSize = ((totalBytes + targetChunkSize - 1L) / targetChunkSize).toInt()
            byTargetSize.coerceIn(segmentCount, minOf(maxChunksCap, segmentCount * 32))
        } else {
            segmentCount
        }

        val expectedMeta = buildSegmentMeta(totalBytes, totalChunks, url)
        if (compatibleSavedChunks == null) {
            chunkDir.listFiles()?.forEach { it.delete() }
            chunkDir.mkdirs()
            metaFile.writeText(expectedMeta)
        } else if (!metaFile.exists()) {
            metaFile.writeText(expectedMeta)
        }

        val chunkRanges = (0 until totalChunks).map { i ->
            val start = (totalBytes * i) / totalChunks
            val end = if (i == totalChunks - 1) totalBytes - 1L else ((totalBytes * (i + 1)) / totalChunks) - 1L
            start to end
        }

        val chunkExpectedLengths = LongArray(totalChunks) { i ->
            chunkRanges[i].second - chunkRanges[i].first + 1L
        }
        val chunkDiskBytes = AtomicLongArray(totalChunks)
        val chunkLiveBytes = AtomicLongArray(totalChunks)
        val chunkState = AtomicIntegerArray(totalChunks)
        val allowedConcurrency = AtomicInteger(segmentCount)

        // Open single direct-write file for lock-free positional FileChannel.write(buf, offset)
        val payloadRaf = RandomAccessFile(payloadPartFile, "rw")
        try {
            val fileChannel = payloadRaf.channel
            val existingPayloadLen = payloadPartFile.length()

            // 1. Restore from atomic progress.bin ledger if payload.part is intact
            val savedLedger = loadProgressLedgerIfValid(progressFile, totalChunks, chunkExpectedLengths)
            if (savedLedger != null && existingPayloadLen > 0L) {
                for (i in 0 until totalChunks) {
                    val segStart = chunkRanges[i].first
                    val saved = savedLedger[i]
                    // Only trust bytes physically present within payload.part bounds
                    val verified = if (saved > 0L && segStart + saved <= existingPayloadLen) saved else 0L
                    chunkDiskBytes.set(i, verified)
                    chunkLiveBytes.set(i, verified)
                }
            }

            // 2. Seamless backward-compatibility migration for any legacy part_i.bin files
            var migratedLegacyParts = false
            val migrationBuf = ByteArray(256 * 1024)
            for (i in 0 until totalChunks) {
                val legacyPart = File(chunkDir, "part_$i.bin")
                if (legacyPart.exists()) {
                    val legacyLen = legacyPart.length().coerceIn(0L, chunkExpectedLengths[i])
                    if (legacyLen > chunkDiskBytes.get(i)) {
                        var writePos = chunkRanges[i].first
                        var remaining = legacyLen
                        FileInputStream(legacyPart).use { fis ->
                            while (remaining > 0L) {
                                val r = fis.read(migrationBuf, 0, minOf(migrationBuf.size.toLong(), remaining).toInt())
                                if (r == -1) break
                                val bb = ByteBuffer.wrap(migrationBuf, 0, r)
                                while (bb.hasRemaining()) {
                                    writePos += fileChannel.write(bb, writePos)
                                }
                                remaining -= r
                            }
                        }
                        val written = legacyLen - remaining
                        chunkDiskBytes.set(i, written)
                        chunkLiveBytes.set(i, written)
                        migratedLegacyParts = true
                    }
                    runCatching { legacyPart.delete() }
                }
            }
            if (migratedLegacyParts) {
                saveProgressLedgerAtomic(chunkDir, progressFile, chunkDiskBytes)
            }

            // Mark already-completed chunks so workers never re-request them
            for (i in 0 until totalChunks) {
                if (chunkDiskBytes.get(i) >= chunkExpectedLengths[i]) {
                    chunkState.set(i, CHUNK_STATE_DONE)
                } else {
                    chunkState.set(i, CHUNK_STATE_PENDING)
                }
            }

            val initialDownloaded = sumArray(chunkDiskBytes).coerceAtMost(totalBytes)
            val speedTracker = SpeedWindowTracker(initialBytes = initialDownloaded)
            val reportLock = Any()
            val ledgerLock = Any()
            val lastReportMs = AtomicLong(0L)
            val lastPersistedDiskTotal = AtomicLong(initialDownloaded)
            val liveSocketCount = AtomicInteger(0)
            val anySegmentReceived206 = AtomicBoolean(initialDownloaded > 0L)
            val lastStepDownMs = AtomicLong(0L)
            val consecutiveThrottleSignals = AtomicInteger(0)
            val requeuedChunks = ConcurrentLinkedQueue<Int>()

            // Per-worker stall watchdog telemetry (zero JNI syscalls in inner read loop!)
            val workerBytesCounters = AtomicLongArray(segmentCount)
            val workerActiveCalls = AtomicReferenceArray<Call?>(segmentCount)
            val activeCalls = ConcurrentHashMap.newKeySet<Call>()

            fun cancelAllActiveCalls() {
                activeCalls.forEach { c ->
                    try { c.cancel() } catch (_: Exception) {}
                }
            }

            fun persistLedgerSafe(force: Boolean = false) {
                val diskNow = sumArray(chunkDiskBytes)
                if (!force && diskNow == lastPersistedDiskTotal.get()) return
                synchronized(ledgerLock) {
                    val curDisk = sumArray(chunkDiskBytes)
                    if (!force && curDisk == lastPersistedDiskTotal.get()) return
                    saveProgressLedgerAtomic(chunkDir, progressFile, chunkDiskBytes)
                    lastPersistedDiskTotal.set(curDisk)
                }
            }

            // Strided zone scheduling + work-stealing:
            // Worker `w` starts in its own dedicated zone so all N parallel segment bars fill evenly
            // from second 1, then steals remaining chunks from the busiest unfinished zone!
            fun claimNextChunk(workerId: Int): Int {
                while (true) {
                    val requeued = requeuedChunks.poll() ?: break
                    if (chunkState.compareAndSet(requeued, CHUNK_STATE_PENDING, CHUNK_STATE_ACTIVE)) {
                        return requeued
                    }
                }
                val zoneStart = (workerId * totalChunks) / segmentCount
                val zoneEnd = (((workerId + 1) * totalChunks) / segmentCount)
                    .coerceAtLeast(zoneStart + 1)
                    .coerceAtMost(totalChunks)
                for (c in zoneStart until zoneEnd) {
                    if (chunkState.compareAndSet(c, CHUNK_STATE_PENDING, CHUNK_STATE_ACTIVE)) {
                        return c
                    }
                }
                // Work-steal from the zone with the most remaining pending chunks
                var bestZone = -1
                var bestPending = 0
                for (offset in 1 until segmentCount) {
                    val z = (workerId + offset) % segmentCount
                    val zs = (z * totalChunks) / segmentCount
                    val ze = (((z + 1) * totalChunks) / segmentCount).coerceAtLeast(zs + 1).coerceAtMost(totalChunks)
                    var pendingInZone = 0
                    for (c in zs until ze) {
                        if (chunkState.get(c) == CHUNK_STATE_PENDING) pendingInZone++
                    }
                    if (pendingInZone > bestPending) {
                        bestPending = pendingInZone
                        bestZone = z
                    }
                }
                if (bestZone >= 0) {
                    val zs = (bestZone * totalChunks) / segmentCount
                    val ze = (((bestZone + 1) * totalChunks) / segmentCount).coerceAtLeast(zs + 1).coerceAtMost(totalChunks)
                    for (c in zs until ze) {
                        if (chunkState.compareAndSet(c, CHUNK_STATE_PENDING, CHUNK_STATE_ACTIVE)) {
                            return c
                        }
                    }
                }
                // Final linear sweep across all chunks
                for (c in 0 until totalChunks) {
                    if (chunkState.compareAndSet(c, CHUNK_STATE_PENDING, CHUNK_STATE_ACTIVE)) {
                        return c
                    }
                }
                return -1
            }

            fun hasPendingOrActiveChunks(): Boolean {
                for (c in 0 until totalChunks) {
                    if (chunkState.get(c) != CHUNK_STATE_DONE) return true
                }
                return false
            }

            fun tryStepDownConcurrencyInPlace(workerId: Int): Boolean {
                val currentAllowed = allowedConcurrency.get()
                if (currentAllowed <= 2) return false
                val now = SystemClock.elapsedRealtime()
                val prevStep = lastStepDownMs.get()
                // Debounce step-downs by 1.2s so simultaneous errors step down smoothly (8 -> 7 -> 6 -> 4)
                if (now - prevStep >= 1_200L && lastStepDownMs.compareAndSet(prevStep, now)) {
                    val nextAllowed = if (currentAllowed >= 8) {
                        (currentAllowed - 2).coerceAtLeast(4)
                    } else {
                        (currentAllowed - 1).coerceAtLeast(2)
                    }
                    allowedConcurrency.set(nextAllowed)
                }
                return workerId >= allowedConcurrency.get()
            }

            fun maybeEmitProgress(force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                val prevMs = lastReportMs.get()
                if (!force && now - prevMs < REPORT_INTERVAL_MS) return
                synchronized(reportLock) {
                    val curMs = SystemClock.elapsedRealtime()
                    val baseMs = lastReportMs.get()
                    if (!force && curMs - baseMs < REPORT_INTERVAL_MS) return
                    lastReportMs.set(curMs)
                    val totalNow = sumArray(chunkLiveBytes).coerceAtMost(totalBytes)
                    val speedBps = speedTracker.sampleSpeedBps(totalNow, curMs)
                    val segFractions = (0 until segmentCount).map { bucket ->
                        val startChunk = (bucket * totalChunks) / segmentCount
                        val endChunk = (((bucket + 1) * totalChunks) / segmentCount)
                            .coerceAtLeast(startChunk + 1)
                            .coerceAtMost(totalChunks)
                        var bucketDone = 0L
                        var bucketTotal = 0L
                        for (c in startChunk until endChunk) {
                            bucketDone += chunkLiveBytes.get(c)
                            bucketTotal += chunkExpectedLengths[c]
                        }
                        (bucketDone.toFloat() / bucketTotal.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                    }
                    val effectiveConns = liveSocketCount.get().let { live ->
                        if (live > 0) live else allowedConcurrency.get().coerceAtLeast(1)
                    }
                    onProgress(
                        IdmProgressUpdate(
                            downloadedBytes = totalNow,
                            totalBytes = totalBytes,
                            speedBytesPerSec = speedBps,
                            activeConnections = effectiveConns,
                            segmentProgress = segFractions,
                            supportsResume = true
                        )
                    )
                }
            }

            maybeEmitProgress(force = true)

            try {
                coroutineScope {
                    val tickerJob = launch(Dispatchers.Default) {
                        var tickCount = 0
                        val prevWorkerBytes = LongArray(segmentCount)
                        val workerStallMs = LongArray(segmentCount)
                        var lastAnyProgressMs = SystemClock.elapsedRealtime()
                        var prevAggregateBytes = sumArray(chunkLiveBytes)

                        while (isActive && !isPausedOrCancelled()) {
                            delay(REPORT_INTERVAL_MS)
                            tickCount++
                            maybeEmitProgress(force = true)

                            // Periodic atomic disk checkpoint (~1.05s)
                            if (tickCount % 3 == 0) {
                                persistLedgerSafe(force = false)
                            }

                            // Per-worker stall watchdog: kill ONLY an individual stalled socket while peers are flying
                            val now = SystemClock.elapsedRealtime()
                            val aggNow = sumArray(chunkLiveBytes)
                            if (aggNow > prevAggregateBytes) {
                                prevAggregateBytes = aggNow
                                lastAnyProgressMs = now
                            }
                            val peersHealthy = liveSocketCount.get() >= 2 && (now - lastAnyProgressMs) < 4_000L
                            for (w in 0 until segmentCount) {
                                val wBytes = workerBytesCounters.get(w)
                                val activeCall = workerActiveCalls.get(w)
                                if (activeCall == null || wBytes != prevWorkerBytes[w]) {
                                    prevWorkerBytes[w] = wBytes
                                    workerStallMs[w] = 0L
                                } else if (peersHealthy) {
                                    workerStallMs[w] += REPORT_INTERVAL_MS
                                    if (workerStallMs[w] >= STALL_WATCHDOG_MS) {
                                        workerStallMs[w] = 0L
                                        runCatching { activeCall.cancel() }
                                    }
                                }
                            }
                        }
                    }

                    try {
                        val workerJobs = (0 until segmentCount).map { workerId ->
                            async(Dispatchers.IO) {
                                // Smooth staggered ramp-up prevents SYN/limit_conn burst rejections
                                // on servers that throttle simultaneous connection storms
                                if (workerId > 0) {
                                    val staggerMs = when {
                                        isTelegramHost -> workerId * 10L
                                        workerId < 4 -> workerId * 25L
                                        else -> 100L + (workerId - 3) * 85L
                                    }
                                    delay(staggerMs)
                                }

                                val readBuffer = ByteArray(CHUNK_BUFFER_BYTES)
                                val coalesceBuffer = ByteArray(WRITE_COALESCE_BYTES)

                                while (true) {
                                    coroutineContext.ensureActive()
                                    if (isPausedOrCancelled()) {
                                        cancelAllActiveCalls()
                                        throw CancellationException("Download paused or cancelled")
                                    }
                                    if (workerId >= allowedConcurrency.get()) {
                                        break
                                    }

                                    val chunkIdx = claimNextChunk(workerId)
                                    if (chunkIdx < 0) {
                                        // If other workers are still running and might re-queue a chunk on step-down,
                                        // wait briefly before exiting only if all chunks are genuinely done
                                        if (!hasPendingOrActiveChunks()) break
                                        delay(120L)
                                        if (!hasPendingOrActiveChunks()) break
                                        continue
                                    }

                                    val (segStart, segEnd) = chunkRanges[chunkIdx]
                                    val expectedLen = chunkExpectedLengths[chunkIdx]
                                    var attempt = 0
                                    var done = false
                                    var handedBackToQueue = false

                                    try {
                                        while (!done) {
                                            coroutineContext.ensureActive()
                                            if (isPausedOrCancelled()) {
                                                cancelAllActiveCalls()
                                                throw CancellationException("Download paused or cancelled")
                                            }
                                            if (workerId >= allowedConcurrency.get()) {
                                                handedBackToQueue = true
                                                break
                                            }

                                            val currentLen = chunkDiskBytes.get(chunkIdx).coerceIn(0L, expectedLen)
                                            chunkLiveBytes.set(chunkIdx, currentLen)
                                            if (currentLen >= expectedLen) {
                                                done = true
                                                break
                                            }

                                            val maxChunkAttempts = 7
                                            val reqStart = segStart + currentLen
                                            val headers = requestHeadersProvider(url)
                                            val reqBuilder = Request.Builder()
                                                .url(url)
                                                .header("Accept-Encoding", "identity")
                                                .header("Range", "bytes=$reqStart-$segEnd")
                                            if (attempt > 0) {
                                                // Force a fresh TCP socket ONLY on retry; keep-alive reuses warm TLS sockets normally!
                                                reqBuilder.header("Connection", "close")
                                            }
                                            applySmartRequestHeaders(url, headers, attempt, reqBuilder)

                                            val clientForAttempt = if (attempt >= 4 && attempt % 2 == 1) {
                                                httpClient
                                            } else {
                                                multiSocketHttp1Client
                                            }
                                            val call = clientForAttempt.newCall(reqBuilder.build())
                                            activeCalls.add(call)
                                            workerActiveCalls.set(workerId, call)
                                            var countedSocket = false
                                            var bytesReadThisCall = 0L

                                            try {
                                                call.execute().use { resp ->
                                                    if (resp.code == 416 && currentLen > 0L) {
                                                        chunkDiskBytes.set(chunkIdx, 0L)
                                                        chunkLiveBytes.set(chunkIdx, 0L)
                                                        throw IOException("Resetting partial chunk $chunkIdx after HTTP 416")
                                                    }
                                                    if (resp.code == 200 || resp.code == 416) {
                                                        if (anySegmentReceived206.get()) {
                                                            // Server already proved Range support; late 200/416 is a connection-limit throttle!
                                                            throw ServerThrottleIOException("Server throttled worker with HTTP ${resp.code} on chunk $chunkIdx")
                                                        }
                                                        throw RangeNotHonoredException("Server returned HTTP ${resp.code} for byte range $reqStart-$segEnd")
                                                    }
                                                    if (resp.code == 403 || resp.code == 410) {
                                                        if (anySegmentReceived206.get()) {
                                                            throw ServerThrottleIOException("CDN connection throttle HTTP ${resp.code} on chunk $chunkIdx")
                                                        }
                                                        throw RangeNotHonoredException("Server rejected parallel range request (HTTP ${resp.code})")
                                                    }
                                                    if (resp.code == 429 || resp.code in 500..504) {
                                                        val retryWait = parseRetryAfterMs(resp, 500L)
                                                        if (retryWait > 500L && liveSocketCount.get() == 0) {
                                                            delay(retryWait)
                                                        }
                                                        throw ServerThrottleIOException("Server limit/transient HTTP ${resp.code} on chunk $chunkIdx")
                                                    }
                                                    if (resp.code != 206) {
                                                        throw IOException("Unexpected HTTP ${resp.code} for byte range $reqStart-$segEnd")
                                                    }

                                                    val contentRange = resp.header("Content-Range")
                                                    if (!contentRange.isNullOrBlank() && contentRange.startsWith("bytes ", ignoreCase = true)) {
                                                        val actualStart = contentRange.substringAfter(" ")
                                                            .substringBefore("-")
                                                            .trim()
                                                            .toLongOrNull()
                                                        if (actualStart != null && actualStart != reqStart) {
                                                            if (currentLen > 0L) {
                                                                chunkDiskBytes.set(chunkIdx, 0L)
                                                                chunkLiveBytes.set(chunkIdx, 0L)
                                                                throw IOException("Mismatched resume Content-Range ($actualStart != $reqStart), resetting chunk $chunkIdx")
                                                            }
                                                            if (anySegmentReceived206.get()) {
                                                                throw ServerThrottleIOException("Mismatched Content-Range start ($actualStart != $reqStart)")
                                                            }
                                                            throw RangeNotHonoredException("Mismatched Content-Range start ($actualStart != $reqStart)")
                                                        }
                                                    }

                                                    val contentEncoding = resp.header("Content-Encoding")?.lowercase(Locale.US) ?: ""
                                                    if (contentEncoding.isNotBlank() && contentEncoding != "identity") {
                                                        throw RangeNotHonoredException("Compressed response ($contentEncoding) incompatible with Range chunks")
                                                    }

                                                    if (anySegmentReceived206.compareAndSet(false, true)) {
                                                        speedTracker.onStreamConnected()
                                                    }
                                                    consecutiveThrottleSignals.set(0)
                                                    liveSocketCount.incrementAndGet()
                                                    countedSocket = true

                                                    val body = resp.body ?: throw IOException("Empty chunk body")
                                                    val input = body.byteStream()
                                                    var diskWrittenForSeg = currentLen
                                                    var liveWrittenForSeg = currentLen
                                                    var bufferedLen = 0

                                                    fun flushCoalescedToChannel() {
                                                        if (bufferedLen <= 0) return
                                                        var filePos = segStart + diskWrittenForSeg
                                                        val bb = ByteBuffer.wrap(coalesceBuffer, 0, bufferedLen)
                                                        while (bb.hasRemaining()) {
                                                            filePos += fileChannel.write(bb, filePos)
                                                        }
                                                        diskWrittenForSeg += bufferedLen
                                                        bufferedLen = 0
                                                        chunkDiskBytes.set(chunkIdx, diskWrittenForSeg)
                                                    }

                                                    try {
                                                        while (liveWrittenForSeg < expectedLen) {
                                                            coroutineContext.ensureActive()
                                                            if (isPausedOrCancelled()) {
                                                                flushCoalescedToChannel()
                                                                cancelAllActiveCalls()
                                                                throw CancellationException("Download paused or cancelled")
                                                            }
                                                            val maxToRead = minOf(
                                                                readBuffer.size.toLong(),
                                                                expectedLen - liveWrittenForSeg
                                                            ).toInt()
                                                            val read = input.read(readBuffer, 0, maxToRead)
                                                            if (read == -1) break
                                                            bytesReadThisCall += read
                                                            liveWrittenForSeg += read
                                                            chunkLiveBytes.set(chunkIdx, liveWrittenForSeg)
                                                            workerBytesCounters.addAndGet(workerId, read.toLong())

                                                            // Coalesce writes into 512 KB contiguous FileChannel pwrite calls
                                                            var offset = 0
                                                            var rem = read
                                                            while (rem > 0) {
                                                                val space = coalesceBuffer.size - bufferedLen
                                                                val toCopy = minOf(space, rem)
                                                                System.arraycopy(readBuffer, offset, coalesceBuffer, bufferedLen, toCopy)
                                                                bufferedLen += toCopy
                                                                offset += toCopy
                                                                rem -= toCopy
                                                                if (bufferedLen >= CHUNK_BUFFER_BYTES) {
                                                                    flushCoalescedToChannel()
                                                                }
                                                            }
                                                        }
                                                    } finally {
                                                        // Always commit already-received bytes to payload.part on exit/error/pause
                                                        runCatching { flushCoalescedToChannel() }
                                                        chunkLiveBytes.set(chunkIdx, diskWrittenForSeg)
                                                    }

                                                    if (diskWrittenForSeg >= expectedLen) {
                                                        done = true
                                                    } else if (attempt < maxChunkAttempts) {
                                                        // If server dropped a socket cleanly while peers are running, step down gently if needed
                                                        if (bytesReadThisCall == 0L && liveSocketCount.get() >= 2 &&
                                                            consecutiveThrottleSignals.incrementAndGet() >= 2 &&
                                                            tryStepDownConcurrencyInPlace(workerId)
                                                        ) {
                                                            handedBackToQueue = true
                                                            break
                                                        }
                                                        attempt++
                                                        val backoffMs = (240L * (1 shl (attempt - 1))).coerceAtMost(3_000L) + (workerId * 30L)
                                                        if (liveSocketCount.get() <= 1) {
                                                            onSmartRetry?.invoke(
                                                                attempt,
                                                                maxChunkAttempts,
                                                                "Resuming chunk #${chunkIdx + 1} from ${diskWrittenForSeg * 100 / expectedLen}%"
                                                            )
                                                        }
                                                        delay(backoffMs)
                                                    } else {
                                                        throw IOException("Chunk $chunkIdx truncated ($diskWrittenForSeg / $expectedLen bytes)")
                                                    }
                                                }
                                            } catch (ce: CancellationException) {
                                                throw ce
                                            } catch (rnhe: RangeNotHonoredException) {
                                                cancelAllActiveCalls()
                                                throw rnhe
                                            } catch (e: IOException) {
                                                if (isPausedOrCancelled()) {
                                                    throw CancellationException("Download paused or cancelled")
                                                }
                                                // If this looks like a server per-IP connection limit while OTHER workers are streaming
                                                // smoothly, gracefully reduce concurrency IN-PLACE without killing healthy sockets
                                                // or losing a single byte!
                                                val isThrottleLike = e is ServerThrottleIOException ||
                                                    (bytesReadThisCall == 0L && liveSocketCount.get() >= 1)
                                                if (isThrottleLike && liveSocketCount.get() >= 1) {
                                                    val throttles = consecutiveThrottleSignals.incrementAndGet()
                                                    if (throttles >= 2 && tryStepDownConcurrencyInPlace(workerId)) {
                                                        handedBackToQueue = true
                                                        break
                                                    }
                                                }

                                                if (attempt < maxChunkAttempts) {
                                                    attempt++
                                                    val backoffMs = (280L * (1 shl (attempt - 1))).coerceAtMost(3_500L) + (workerId * 35L)
                                                    // Only show Smart Retry banner when no other sockets are actively streaming,
                                                    // preventing healthy 4-6 MB/s downloads from flickering to "0 KB/s Smart Retry"
                                                    if (liveSocketCount.get() == 0) {
                                                        onSmartRetry?.invoke(
                                                            attempt,
                                                            maxChunkAttempts,
                                                            "Re-opening fresh TCP socket for chunk #${chunkIdx + 1}"
                                                        )
                                                    }
                                                    delay(backoffMs)
                                                } else if (liveSocketCount.get() >= 1 && allowedConcurrency.get() > 1) {
                                                    // Healthy peers are still streaming! Hand this chunk back to the queue
                                                    // and retire this worker instead of aborting the entire download!
                                                    val curAllowed = allowedConcurrency.get()
                                                    if (curAllowed > 1) {
                                                        allowedConcurrency.compareAndSet(curAllowed, (curAllowed - 1).coerceAtLeast(1))
                                                    }
                                                    handedBackToQueue = true
                                                    break
                                                } else {
                                                    cancelAllActiveCalls()
                                                    throw e
                                                }
                                            } finally {
                                                if (countedSocket) {
                                                    liveSocketCount.decrementAndGet()
                                                }
                                                workerActiveCalls.set(workerId, null)
                                                activeCalls.remove(call)
                                            }
                                        }
                                    } finally {
                                        if (done) {
                                            chunkState.set(chunkIdx, CHUNK_STATE_DONE)
                                            persistLedgerSafe(force = false)
                                        } else {
                                            // Always return unfinished chunk to pending queue so zero bytes are lost!
                                            chunkState.set(chunkIdx, CHUNK_STATE_PENDING)
                                            if (handedBackToQueue) {
                                                requeuedChunks.offer(chunkIdx)
                                            }
                                            persistLedgerSafe(force = true)
                                        }
                                    }
                                }
                            }
                        }
                        workerJobs.awaitAll()
                    } finally {
                        tickerJob.cancel()
                        persistLedgerSafe(force = true)
                    }
                }
            } catch (t: Throwable) {
                cancelAllActiveCalls()
                persistLedgerSafe(force = true)
                throw t
            }

            // Final verification: ensure every single chunk reached 100%
            for (i in 0 until totalChunks) {
                if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")
                val expectedLen = chunkExpectedLengths[i]
                val actualLen = chunkDiskBytes.get(i)
                if (actualLen != expectedLen) {
                    throw IOException("Chunk $i verification failed ($actualLen != $expectedLen)")
                }
            }

            onAssembling?.invoke()
            if (payloadRaf.length() != totalBytes) {
                payloadRaf.setLength(totalBytes)
            }
            fileChannel.force(false)
        } finally {
            runCatching { payloadRaf.close() }
        }

        // Zero-copy O(1) atomic rename from chunkDir/payload.part -> destFile!
        if (destFile.exists()) destFile.delete()
        destFile.parentFile?.mkdirs()

        val renamed = payloadPartFile.renameTo(destFile)
        if (!renamed) {
            FileInputStream(payloadPartFile).use { inStream ->
                FileOutputStream(destFile, false).use { outStream ->
                    val inChannel = inStream.channel
                    val outChannel = outStream.channel
                    var transferred = 0L
                    while (transferred < totalBytes) {
                        val n = inChannel.transferTo(transferred, totalBytes - transferred, outChannel)
                        if (n <= 0L) break
                        transferred += n
                    }
                    outStream.flush()
                }
            }
            runCatching { payloadPartFile.delete() }
        }

        val finalLen = destFile.length()
        if (finalLen != totalBytes) {
            throw IOException("Assembled file size mismatch ($finalLen != $totalBytes)")
        }

        try {
            chunkDir.deleteRecursively()
        } catch (_: Exception) {
        }

        return SegmentedDownloadResult(
            downloadedBytes = finalLen,
            effectiveConnectionsUsed = allowedConcurrency.get().coerceIn(1, segmentCount)
        )
    }

    private class ServerThrottleIOException(message: String) : IOException(message)

    private sealed class SingleStreamResult {
        data class Bytes(
            val count: Long,
            val contentType: String,
            val resolvedUrl: String,
            val supportsResume: Boolean
        ) : SingleStreamResult()

        data class HlsDetected(
            val resolvedUrl: String,
            val playlistText: String
        ) : SingleStreamResult()
    }

    private suspend fun downloadSingleStream(
        url: String,
        destFile: File,
        knownTotalBytes: Long,
        rejectHtml: Boolean,
        requestHeadersProvider: (String) -> Map<String, String>,
        isPausedOrCancelled: () -> Boolean,
        onSmartRetry: ((attempt: Int, maxAttempts: Int, reason: String) -> Unit)? = null,
        onProgress: (IdmProgressUpdate) -> Unit
    ): SingleStreamResult {
        val isGoogleVideoCdn = url.contains("googlevideo.com", ignoreCase = true)
        var streamAttempt = 0
        val maxStreamRetries = 6

        while (true) {
            coroutineContext.ensureActive()
            if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")

            val existingBytes = if (destFile.exists()) destFile.length() else 0L
            if (knownTotalBytes > 0L && existingBytes == knownTotalBytes) {
                return SingleStreamResult.Bytes(
                    count = existingBytes,
                    contentType = "",
                    resolvedUrl = url,
                    supportsResume = true
                )
            }

            fun buildRequest(includeRangeHeader: Boolean, byteOffset: Long): Request {
                val headers = requestHeadersProvider(url)
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                if (streamAttempt >= 2) {
                    reqBuilder.header("Connection", "close")
                }
                applySmartRequestHeaders(url, headers, streamAttempt, reqBuilder)
                if (includeRangeHeader) {
                    if (byteOffset > 0L) {
                        reqBuilder.header("Range", "bytes=$byteOffset-")
                    } else if (isGoogleVideoCdn && !url.contains("range=")) {
                        reqBuilder.header("Range", "bytes=0-")
                    }
                }
                return reqBuilder.build()
            }

            try {
                val streamClient = if (streamAttempt >= 3 && streamAttempt % 2 == 1) {
                    multiSocketHttp1Client
                } else {
                    httpClient
                }
                var response = streamClient.newCall(buildRequest(includeRangeHeader = true, byteOffset = existingBytes)).execute()
                if ((response.code == 403 || response.code == 416) && (existingBytes > 0L || isGoogleVideoCdn)) {
                    response.close()
                    if (existingBytes > 0L && destFile.exists()) {
                        destFile.delete()
                    }
                    response = streamClient.newCall(buildRequest(includeRangeHeader = false, byteOffset = 0L)).execute()
                }

                response.use { resp ->
                    val resolvedUrl = resp.request.url.toString()
                    if (resp.code == 403 || resp.code == 410) {
                        throw ExtractorException(
                            DownloaderErrorType.EXPIRED_LINK,
                            "Media stream link returned HTTP ${resp.code}"
                        )
                    }
                    if (resp.code == 429 || resp.code in 500..504) {
                        val waitMs = parseRetryAfterMs(resp, 600L)
                        if (waitMs > 600L) {
                            delay(waitMs)
                        }
                        throw IOException("Stream server transient HTTP ${resp.code}")
                    }
                    if (!resp.isSuccessful && resp.code != 206) {
                        throw ExtractorException(
                            DownloaderErrorType.NETWORK_FAILURE,
                            "Stream server returned HTTP ${resp.code}: ${resp.message}"
                        )
                    }

                    val contentType = resp.header("Content-Type") ?: ""
                    if (contentType.contains("mpegurl", ignoreCase = true) ||
                        contentType.contains("vnd.apple.mpegurl", ignoreCase = true)
                    ) {
                        val playlistText = resp.body?.string() ?: ""
                        if (playlistText.contains("#EXTM3U")) {
                            return SingleStreamResult.HlsDetected(resolvedUrl, playlistText)
                        }
                    }
                    if (rejectHtml && contentType.contains("text/html", ignoreCase = true)) {
                        throw ExtractorException(
                            DownloaderErrorType.UNSUPPORTED_MEDIA,
                            "Server returned an HTML page instead of a binary media stream."
                        )
                    }

                    val currentExistingBytes = if (destFile.exists()) destFile.length() else 0L
                    val contentRangeHeader = resp.header("Content-Range")
                    val verifiedRangeStart = if (resp.code == 206 && !contentRangeHeader.isNullOrBlank() &&
                        contentRangeHeader.startsWith("bytes ", ignoreCase = true)
                    ) {
                        contentRangeHeader.substringAfter(" ").substringBefore("-").trim().toLongOrNull()
                    } else null
                    val isResumeSupported = resp.code == 206 &&
                        currentExistingBytes > 0L &&
                        (verifiedRangeStart == null || verifiedRangeStart == currentExistingBytes)
                    if (!isResumeSupported && currentExistingBytes > 0L && destFile.exists()) {
                        // Server returned HTTP 200 or mismatched Content-Range: overwrite cleanly from byte 0
                        runCatching { destFile.delete() }
                    }
                    val startByteOffset = if (isResumeSupported) currentExistingBytes else 0L
                    val body = resp.body ?: throw IOException("Empty stream body.")
                    val responseContentLength = body.contentLength()
                    val totalStreamBytes = when {
                        isResumeSupported && responseContentLength > 0L -> currentExistingBytes + responseContentLength
                        responseContentLength > 0L -> responseContentLength
                        knownTotalBytes > 0L -> knownTotalBytes
                        else -> -1L
                    }
                    val acceptsRangesHeader = resp.code == 206 || resp.header("Accept-Ranges")?.contains("bytes", true) == true

                    destFile.parentFile?.mkdirs()
                    BufferedOutputStream(
                        FileOutputStream(destFile, isResumeSupported),
                        SINGLE_BUFFER_BYTES
                    ).use { bos ->
                        val input = body.byteStream()
                        val buffer = ByteArray(CHUNK_BUFFER_BYTES)
                        val downloadedAtomic = AtomicLong(startByteOffset)
                        val speedTracker = SpeedWindowTracker(initialBytes = startByteOffset).apply {
                            onStreamConnected()
                        }
                        val lastReportMs = AtomicLong(0L)
                        val reportLock = Any()

                        fun emitSingleProgress(force: Boolean = false) {
                            val now = SystemClock.elapsedRealtime()
                            val prev = lastReportMs.get()
                            if (!force && now - prev < REPORT_INTERVAL_MS) return
                            synchronized(reportLock) {
                                val curMs = SystemClock.elapsedRealtime()
                                if (!force && curMs - lastReportMs.get() < REPORT_INTERVAL_MS) return
                                lastReportMs.set(curMs)
                                val dl = downloadedAtomic.get()
                                val speedBps = speedTracker.sampleSpeedBps(dl, curMs)
                                val frac = if (totalStreamBytes > 0L) {
                                    (dl.toFloat() / totalStreamBytes.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0.5f
                                }
                                onProgress(
                                    IdmProgressUpdate(
                                        downloadedBytes = dl,
                                        totalBytes = totalStreamBytes,
                                        speedBytesPerSec = speedBps,
                                        activeConnections = 1,
                                        segmentProgress = listOf(frac),
                                        supportsResume = acceptsRangesHeader
                                    )
                                )
                            }
                        }

                        coroutineScope {
                            val tickerJob = launch(Dispatchers.Default) {
                                while (isActive && !isPausedOrCancelled()) {
                                    delay(REPORT_INTERVAL_MS)
                                    emitSingleProgress(force = true)
                                }
                            }
                            try {
                                while (true) {
                                    coroutineContext.ensureActive()
                                    if (isPausedOrCancelled()) {
                                        throw CancellationException("Download paused or cancelled")
                                    }

                                    val read = input.read(buffer)
                                    if (read == -1) break
                                    downloadedAtomic.addAndGet(read.toLong())
                                    emitSingleProgress(force = false)
                                    bos.write(buffer, 0, read)
                                }
                                bos.flush()
                            } finally {
                                tickerJob.cancel()
                            }
                        }

                        val finalDownloaded = downloadedAtomic.get()
                        if (totalStreamBytes > 0L && finalDownloaded < totalStreamBytes) {
                            throw IOException("Stream ended early ($finalDownloaded / $totalStreamBytes bytes)")
                        }

                        onProgress(
                            IdmProgressUpdate(
                                downloadedBytes = finalDownloaded,
                                totalBytes = if (totalStreamBytes > 0L) totalStreamBytes else finalDownloaded,
                                speedBytesPerSec = 0L,
                                activeConnections = 1,
                                segmentProgress = listOf(1.0f),
                                supportsResume = acceptsRangesHeader
                            )
                        )

                        return SingleStreamResult.Bytes(
                            count = finalDownloaded,
                            contentType = contentType,
                            resolvedUrl = resolvedUrl,
                            supportsResume = acceptsRangesHeader
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ee: ExtractorException) {
                throw ee
            } catch (e: IOException) {
                if (isPausedOrCancelled()) {
                    throw CancellationException("Download paused or cancelled")
                }
                if (streamAttempt < maxStreamRetries) {
                    streamAttempt++
                    val resumeBytes = if (destFile.exists()) destFile.length() else 0L
                    val backoffMs = (600L * (1 shl (streamAttempt - 1))).coerceAtMost(4500L)
                    onSmartRetry?.invoke(
                        streamAttempt,
                        maxStreamRetries,
                        if (resumeBytes > 0L) "Resuming stream at ${resumeBytes / 1024} KB"
                        else "Reconnecting stream (${e.javaClass.simpleName})"
                    )
                    delay(backoffMs)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Downloads HLS segments concurrently using up to [concurrency] HTTP workers, decrypts AES-128
     * segments in parallel, and writes all segments in strict sequential order into [tempRawFile].
     * Counts network bytes in real time as each segment streams in so speed is continuously accurate.
     */
    suspend fun downloadHlsSegmentsParallel(
        segments: List<HlsSegmentSpec>,
        tempRawFile: File,
        concurrency: Int,
        requestHeadersProvider: (String) -> Map<String, String>,
        isPausedOrCancelled: () -> Boolean,
        onProgress: (IdmProgressUpdate) -> Unit
    ): Long {
        if (segments.isEmpty()) return 0L
        val workers = concurrency.coerceIn(1, 12).coerceAtMost(segments.size)
        val keyCache = ConcurrentHashMap<String, ByteArray>()
        val windowSize = (workers * 2).coerceAtLeast(workers)
        val liveNetworkBytes = AtomicLong(0L)
        val completedSegments = AtomicLong(0L)
        val speedTracker = SpeedWindowTracker(initialBytes = 0L)
        val lastReportMs = AtomicLong(0L)
        val reportLock = Any()

        fun emitHlsProgress(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastReportMs.get() < REPORT_INTERVAL_MS) return
            synchronized(reportLock) {
                val curMs = SystemClock.elapsedRealtime()
                if (!force && curMs - lastReportMs.get() < REPORT_INTERVAL_MS) return
                lastReportMs.set(curMs)
                val curTotal = liveNetworkBytes.get()
                val speedBps = speedTracker.sampleSpeedBps(curTotal, curMs)
                val doneSegs = completedSegments.get()
                val segFrac = (doneSegs.toFloat() / segments.size.toFloat()).coerceIn(0f, 1f)
                val estTotal = if (segFrac > 0.04f) (curTotal / segFrac).toLong() else -1L
                onProgress(
                    IdmProgressUpdate(
                        downloadedBytes = curTotal,
                        totalBytes = estTotal,
                        speedBytesPerSec = speedBps,
                        activeConnections = workers,
                        segmentProgress = List(workers) { segFrac },
                        supportsResume = false
                    )
                )
            }
        }

        tempRawFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(tempRawFile, false), SINGLE_BUFFER_BYTES).use { out ->
            coroutineScope {
                val tickerJob = launch(Dispatchers.Default) {
                    while (isActive && !isPausedOrCancelled()) {
                        delay(REPORT_INTERVAL_MS)
                        emitHlsProgress(force = true)
                    }
                }
                try {
                    val semaphore = Semaphore(workers)
                    var windowStart = 0
                    while (windowStart < segments.size) {
                        coroutineContext.ensureActive()
                        if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")

                        val windowEnd = minOf(windowStart + windowSize, segments.size)
                        val chunkBytes = coroutineScope {
                            (windowStart until windowEnd).map { segIdx ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")
                                        val data = fetchAndDecryptHlsSegment(
                                            seg = segments[segIdx],
                                            keyCache = keyCache,
                                            requestHeadersProvider = requestHeadersProvider,
                                            onBytesRead = { n ->
                                                liveNetworkBytes.addAndGet(n.toLong())
                                                emitHlsProgress(force = false)
                                            }
                                        )
                                        completedSegments.incrementAndGet()
                                        emitHlsProgress(force = false)
                                        data
                                    }
                                }
                            }.awaitAll()
                        }

                        for (bytes in chunkBytes) {
                            if (bytes.isNotEmpty()) {
                                out.write(bytes)
                            }
                        }

                        emitHlsProgress(force = true)
                        windowStart = windowEnd
                    }
                    out.flush()
                } finally {
                    tickerJob.cancel()
                }
            }
        }
        return tempRawFile.length()
    }

    private fun fetchAndDecryptHlsSegment(
        seg: HlsSegmentSpec,
        keyCache: ConcurrentHashMap<String, ByteArray>,
        requestHeadersProvider: (String) -> Map<String, String>,
        onBytesRead: ((Int) -> Unit)? = null
    ): ByteArray {
        val headers = requestHeadersProvider(seg.url)
        var lastError: Exception? = null
        val maxHlsSegAttempts = 5
        for (attempt in 0 until maxHlsSegAttempts) {
            var bytesCountedThisAttempt = 0
            try {
                val segReqBuilder = Request.Builder()
                    .url(seg.url)
                    .header("Accept", "*/*")
                if (attempt >= 2) {
                    segReqBuilder.header("Connection", "close")
                }
                applySmartRequestHeaders(seg.url, headers, attempt, segReqBuilder)
                if (seg.byteRangeStart >= 0L && seg.byteRangeLength > 0L) {
                    val rangeEnd = seg.byteRangeStart + seg.byteRangeLength - 1L
                    segReqBuilder.header("Range", "bytes=${seg.byteRangeStart}-$rangeEnd")
                }

                val segClient = if (attempt >= 3 && attempt % 2 == 1) multiSocketHttp1Client else httpClient
                val rawBytes = segClient.newCall(segReqBuilder.build()).execute().use { segResp ->
                    if (segResp.code == 401 || segResp.code == 403) {
                        if (attempt < 2) {
                            throw IOException("HLS segment HTTP ${segResp.code} retryable with fresh headers")
                        }
                        throw ExtractorException(
                            DownloaderErrorType.PRIVATE_OR_RESTRICTED,
                            "HLS segment denied (HTTP ${segResp.code})."
                        )
                    }
                    if (segResp.code == 429 || segResp.code in 500..504) {
                        val waitMs = parseRetryAfterMs(segResp, 350L * (attempt + 1))
                        try { Thread.sleep(waitMs) } catch (_: InterruptedException) {}
                        throw IOException("HLS segment rate-limited/transient HTTP ${segResp.code}")
                    }
                    if (!segResp.isSuccessful && segResp.code != 206) {
                        throw ExtractorException(
                            DownloaderErrorType.NETWORK_FAILURE,
                            "HLS segment HTTP ${segResp.code}"
                        )
                    }
                    val body = segResp.body ?: return@use ByteArray(0)
                    val isServerFull200OnByteRange = segResp.code == 200 &&
                        seg.byteRangeStart >= 0L && seg.byteRangeLength > 0L

                    val initialCap = body.contentLength().takeIf { it in 1..(16 * 1024 * 1024) }?.toInt() ?: (64 * 1024)
                    val baos = ByteArrayOutputStream(initialCap)
                    val buf = ByteArray(32 * 1024)
                    val stream = body.byteStream()
                    while (true) {
                        val r = stream.read(buf)
                        if (r == -1) break
                        baos.write(buf, 0, r)
                        bytesCountedThisAttempt += r
                        onBytesRead?.invoke(r)
                    }
                    val all = baos.toByteArray()
                    if (isServerFull200OnByteRange) {
                        val from = seg.byteRangeStart.toInt().coerceIn(0, all.size)
                        val to = (seg.byteRangeStart + seg.byteRangeLength).toInt().coerceIn(from, all.size)
                        all.copyOfRange(from, to)
                    } else {
                        all
                    }
                }

                if (seg.keyUri == null) {
                    return rawBytes
                }

                val keyBytes = keyCache.computeIfAbsent(seg.keyUri) { keyUrl ->
                    val keyHeaders = requestHeadersProvider(keyUrl)
                    val keyReqBuilder = Request.Builder().url(keyUrl)
                    keyHeaders.forEach { (k, v) -> keyReqBuilder.header(k, v) }
                    httpClient.newCall(keyReqBuilder.build()).execute().use { kResp ->
                        if (!kResp.isSuccessful) {
                            throw ExtractorException(DownloaderErrorType.DRM_PROTECTED, "Unable to fetch HLS AES-128 key.")
                        }
                        kResp.body?.bytes() ?: throw ExtractorException(DownloaderErrorType.DRM_PROTECTED, "Empty HLS AES-128 key.")
                    }
                }

                val ivBytes = ByteArray(16)
                if (!seg.ivHex.isNullOrBlank()) {
                    val hex = seg.ivHex.padStart(32, '0').takeLast(32)
                    for (bIdx in 0 until 16) {
                        ivBytes[bIdx] = hex.substring(bIdx * 2, bIdx * 2 + 2).toInt(16).toByte()
                    }
                } else {
                    ByteBuffer.wrap(ivBytes).putLong(8, seg.sequenceNumber)
                }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    IvParameterSpec(ivBytes)
                )
                return cipher.doFinal(rawBytes)
            } catch (ee: ExtractorException) {
                throw ee
            } catch (e: Exception) {
                if (bytesCountedThisAttempt > 0) {
                    onBytesRead?.invoke(-bytesCountedThisAttempt)
                }
                lastError = e
                if (attempt >= maxHlsSegAttempts - 1) throw e
                try { Thread.sleep(250L * (attempt + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw lastError ?: IOException("Failed to fetch HLS segment")
    }

    private fun sumArray(arr: AtomicLongArray): Long {
        var total = 0L
        for (i in 0 until arr.length()) {
            total += arr.get(i)
        }
        return total
    }
}
