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
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
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
        private const val CHUNK_BUFFER_BYTES = 64 * 1024 // 64 KB socket read buffer per worker
        private const val FILE_STREAM_BUFFER_BYTES = 256 * 1024 // 256 KB buffered disk output per worker
        private const val SINGLE_BUFFER_BYTES = 256 * 1024 // 256 KB single stream
        internal const val REPORT_INTERVAL_MS = 350L

        fun chunkDirFor(workDir: File, destFile: File): File {
            val safeName = destFile.name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "stream" }
            return File(workDir, "idm_chunks_$safeName")
        }

        fun buildSegmentMeta(totalBytes: Long, segmentCount: Int, url: String): String {
            return "$totalBytes|$segmentCount|${url.hashCode()}"
        }

        fun createFastOkHttpClient(dns: okhttp3.Dns = okhttp3.Dns.SYSTEM): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            }
            val connectionPool = ConnectionPool(32, 60, TimeUnit.SECONDS)
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

        fun calculateOptimalConnections(totalBytes: Long, maxConnections: Int): Int {
            val cap = maxConnections.coerceIn(1, 16)
            if (cap <= 1 || totalBytes < CHUNK_THRESHOLD_BYTES) return 1
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

        val initialSegmentCount = if (probe.acceptRanges &&
            probe.hasAuthoritativeTotalBytes &&
            probe.totalBytes >= CHUNK_THRESHOLD_BYTES
        ) {
            calculateOptimalConnections(probe.totalBytes, maxConnections)
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
                    val downloaded = downloadSegmentedParallel(
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
                        downloadedBytes = downloaded,
                        activeConnectionsUsed = segCount,
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
                    // Server rejected Range headers; break immediately to single-stream fallback
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
                    try {
                        chunkDirFor(workDir, destFile).deleteRecursively()
                    } catch (_: Exception) {}
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
    ): Long {
        val chunkDir = chunkDirFor(workDir, destFile).apply { mkdirs() }
        val metaFile = File(chunkDir, "segments.meta")
        val expectedMeta = buildSegmentMeta(totalBytes, segmentCount, url)
        if (!metaFile.exists() || metaFile.readText().trim() != expectedMeta) {
            chunkDir.listFiles()?.forEach { it.delete() }
            chunkDir.mkdirs()
            metaFile.writeText(expectedMeta)
        }

        val segmentRanges = (0 until segmentCount).map { i ->
            val start = (totalBytes * i) / segmentCount
            val end = if (i == segmentCount - 1) totalBytes - 1L else ((totalBytes * (i + 1)) / segmentCount) - 1L
            start to end
        }

        val segmentProgressBytes = AtomicLongArray(segmentCount)
        val segmentExpectedLengths = LongArray(segmentCount) { i ->
            segmentRanges[i].second - segmentRanges[i].first + 1L
        }

        for (i in 0 until segmentCount) {
            val partFile = File(chunkDir, "part_$i.bin")
            val existing = if (partFile.exists()) partFile.length().coerceIn(0L, segmentExpectedLengths[i]) else 0L
            segmentProgressBytes.set(i, existing)
        }

        val initialDownloaded = sumArray(segmentProgressBytes).coerceAtMost(totalBytes)
        val speedTracker = SpeedWindowTracker(initialBytes = initialDownloaded)
        val reportLock = Any()
        val lastReportMs = AtomicLong(0L)
        val liveSocketCount = AtomicInteger(0)
        val anySegmentReceived206 = AtomicBoolean(false)

        fun maybeEmitProgress(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            val prevMs = lastReportMs.get()
            if (!force && now - prevMs < REPORT_INTERVAL_MS) return
            synchronized(reportLock) {
                val curMs = SystemClock.elapsedRealtime()
                val baseMs = lastReportMs.get()
                if (!force && curMs - baseMs < REPORT_INTERVAL_MS) return
                lastReportMs.set(curMs)
                val totalNow = sumArray(segmentProgressBytes).coerceAtMost(totalBytes)
                val speedBps = speedTracker.sampleSpeedBps(totalNow, curMs)
                val segFractions = (0 until segmentCount).map { idx ->
                    val len = segmentExpectedLengths[idx].coerceAtLeast(1L)
                    (segmentProgressBytes.get(idx).toFloat() / len.toFloat()).coerceIn(0f, 1f)
                }
                val activeConns = liveSocketCount.get().let { live ->
                    if (live > 0) live else segmentCount
                }
                onProgress(
                    IdmProgressUpdate(
                        downloadedBytes = totalNow,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBps,
                        activeConnections = activeConns,
                        segmentProgress = segFractions,
                        supportsResume = true
                    )
                )
            }
        }

        maybeEmitProgress(force = true)

        val activeCalls = ConcurrentHashMap.newKeySet<Call>()
        fun cancelAllActiveCalls() {
            activeCalls.forEach { c ->
                try { c.cancel() } catch (_: Exception) {}
            }
        }

        try {
            coroutineScope {
                val tickerJob = launch(Dispatchers.Default) {
                    while (isActive && !isPausedOrCancelled()) {
                        delay(REPORT_INTERVAL_MS)
                        maybeEmitProgress(force = true)
                    }
                }
                try {
                    val jobs = (0 until segmentCount).map { segIdx ->
                        async(Dispatchers.IO) {
                            if (segIdx > 0) {
                                // Slight stagger prevents CDN burst-connection rate limiters from rejecting parallel sockets
                                delay(segIdx * 25L)
                            }
                            val (segStart, segEnd) = segmentRanges[segIdx]
                            val expectedLen = segmentExpectedLengths[segIdx]
                            val partFile = File(chunkDir, "part_$segIdx.bin")

                            var attempt = 0
                            var done = false
                            while (!done) {
                                coroutineContext.ensureActive()
                                if (isPausedOrCancelled()) {
                                    cancelAllActiveCalls()
                                    throw CancellationException("Download paused or cancelled")
                                }

                                val currentLen = if (partFile.exists()) {
                                    val rawLen = partFile.length()
                                    if (rawLen > expectedLen) {
                                        partFile.delete()
                                        0L
                                    } else {
                                        rawLen
                                    }
                                } else 0L

                                segmentProgressBytes.set(segIdx, currentLen)
                                if (currentLen >= expectedLen) {
                                    done = true
                                    break
                                }

                                val reqStart = segStart + currentLen
                                val headers = requestHeadersProvider(url)
                                val reqBuilder = Request.Builder()
                                    .url(url)
                                    .header("Accept-Encoding", "identity")
                                    .header("Range", "bytes=$reqStart-$segEnd")
                                headers.forEach { (k, v) -> reqBuilder.header(k, v) }

                                val call = httpClient.newCall(reqBuilder.build())
                                activeCalls.add(call)
                                var countedSocket = false
                                try {
                                    call.execute().use { resp ->
                                        if (resp.code == 200 || resp.code == 416) {
                                            throw RangeNotHonoredException("Server returned HTTP ${resp.code} for byte range $reqStart-$segEnd")
                                        }
                                        if (resp.code == 403 || resp.code == 410) {
                                            if (anySegmentReceived206.get()) {
                                                // Secondary connection throttled by CDN; treat as retryable IOException
                                                throw IOException("CDN connection throttle HTTP ${resp.code} on chunk $segIdx")
                                            }
                                            throw RangeNotHonoredException("Server rejected parallel range request (HTTP ${resp.code})")
                                        }
                                        if (resp.code == 429 || resp.code in 500..504) {
                                            throw IOException("Transient HTTP ${resp.code} for byte range $reqStart-$segEnd")
                                        }
                                        if (resp.code != 206) {
                                            throw IOException("Unexpected HTTP ${resp.code} for byte range $reqStart-$segEnd")
                                        }

                                        // Verify Content-Range start matches requested offset so servers that ignore offsets don't corrupt assembly
                                        val contentRange = resp.header("Content-Range")
                                        if (!contentRange.isNullOrBlank() && contentRange.startsWith("bytes ", ignoreCase = true)) {
                                            val actualStart = contentRange.substringAfter(" ")
                                                .substringBefore("-")
                                                .trim()
                                                .toLongOrNull()
                                            if (actualStart != null && actualStart != reqStart) {
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
                                        liveSocketCount.incrementAndGet()
                                        countedSocket = true

                                        val body = resp.body ?: throw IOException("Empty chunk body")
                                        val input = body.byteStream()
                                        val buffer = ByteArray(CHUNK_BUFFER_BYTES)
                                        var writtenForSeg = currentLen
                                        BufferedOutputStream(
                                            FileOutputStream(partFile, currentLen > 0L),
                                            FILE_STREAM_BUFFER_BYTES
                                        ).use { bos ->
                                            while (writtenForSeg < expectedLen) {
                                                coroutineContext.ensureActive()
                                                if (isPausedOrCancelled()) {
                                                    cancelAllActiveCalls()
                                                    throw CancellationException("Download paused or cancelled")
                                                }
                                                val maxToRead = minOf(buffer.size.toLong(), expectedLen - writtenForSeg).toInt()
                                                val read = input.read(buffer, 0, maxToRead)
                                                if (read == -1) break
                                                writtenForSeg += read
                                                segmentProgressBytes.set(segIdx, writtenForSeg)
                                                maybeEmitProgress(force = false)
                                                bos.write(buffer, 0, read)
                                            }
                                            bos.flush()
                                        }
                                        if (writtenForSeg >= expectedLen) {
                                            done = true
                                        } else if (attempt < 4) {
                                            attempt++
                                            val backoffMs = (350L * (1 shl (attempt - 1))).coerceAtMost(3500L) + (segIdx * 45L)
                                            onSmartRetry?.invoke(
                                                attempt,
                                                4,
                                                "Resuming chunk #${segIdx + 1} from ${writtenForSeg * 100 / expectedLen}%"
                                            )
                                            delay(backoffMs)
                                        } else {
                                            throw IOException("Chunk $segIdx truncated ($writtenForSeg / $expectedLen bytes)")
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
                                    if (attempt < 4) {
                                        attempt++
                                        val backoffMs = (400L * (1 shl (attempt - 1))).coerceAtMost(4000L) + (segIdx * 50L)
                                        onSmartRetry?.invoke(
                                            attempt,
                                            4,
                                            "Recovering chunk #${segIdx + 1} (${e.javaClass.simpleName})"
                                        )
                                        delay(backoffMs)
                                    } else {
                                        cancelAllActiveCalls()
                                        throw e
                                    }
                                } finally {
                                    if (countedSocket) {
                                        liveSocketCount.decrementAndGet()
                                    }
                                    activeCalls.remove(call)
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                } finally {
                    tickerJob.cancel()
                }
            }
        } catch (t: Throwable) {
            cancelAllActiveCalls()
            throw t
        }

        // Notify caller that network transfer is 100% done and chunk assembly is starting
        onAssembling?.invoke()

        // Verify all chunks and assemble sequentially into destFile while freeing each chunk immediately
        if (destFile.exists()) destFile.delete()
        destFile.parentFile?.mkdirs()

        for (i in 0 until segmentCount) {
            if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")
            val partFile = File(chunkDir, "part_$i.bin")
            val expectedLen = segmentExpectedLengths[i]
            if (!partFile.exists() || partFile.length() != expectedLen) {
                throw IOException("Chunk $i verification failed (${partFile.length()} != $expectedLen)")
            }
        }

        val firstPart = File(chunkDir, "part_0.bin")
        val renamedFirst = firstPart.renameTo(destFile)
        val startIdx = if (renamedFirst) 1 else 0

        FileOutputStream(destFile, renamedFirst).use { outStream ->
            val outChannel = outStream.channel
            val copyBuffer = ByteArray(SINGLE_BUFFER_BYTES)
            for (i in startIdx until segmentCount) {
                if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")
                val partFile = File(chunkDir, "part_$i.bin")
                val expectedLen = segmentExpectedLengths[i]
                FileInputStream(partFile).use { inStream ->
                    val inChannel = inStream.channel
                    var transferred = 0L
                    while (transferred < expectedLen) {
                        val n = inChannel.transferTo(transferred, expectedLen - transferred, outChannel)
                        if (n > 0L) {
                            transferred += n
                        } else {
                            // Fallback buffered copy if FileChannel.transferTo returns 0
                            inChannel.position(transferred)
                            while (transferred < expectedLen) {
                                val read = inStream.read(copyBuffer)
                                if (read == -1) break
                                outStream.write(copyBuffer, 0, read)
                                transferred += read
                            }
                            break
                        }
                    }
                }
                runCatching { partFile.delete() }
            }
            outStream.flush()
        }

        val finalLen = destFile.length()
        if (finalLen != totalBytes) {
            throw IOException("Assembled file size mismatch ($finalLen != $totalBytes)")
        }

        try {
            chunkDir.deleteRecursively()
        } catch (_: Exception) {
        }
        return finalLen
    }

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
        val maxStreamRetries = 4

        while (true) {
            coroutineContext.ensureActive()
            if (isPausedOrCancelled()) throw CancellationException("Download paused or cancelled")

            val existingBytes = if (destFile.exists()) destFile.length() else 0L

            fun buildRequest(includeRangeHeader: Boolean, byteOffset: Long): Request {
                val headers = requestHeadersProvider(url)
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                headers.forEach { (k, v) -> reqBuilder.header(k, v) }
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
                var response = httpClient.newCall(buildRequest(includeRangeHeader = true, byteOffset = existingBytes)).execute()
                if ((response.code == 403 || response.code == 416) && (existingBytes > 0L || isGoogleVideoCdn)) {
                    response.close()
                    if (existingBytes > 0L && destFile.exists()) {
                        destFile.delete()
                    }
                    response = httpClient.newCall(buildRequest(includeRangeHeader = false, byteOffset = 0L)).execute()
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
                    val isResumeSupported = resp.code == 206 && currentExistingBytes > 0L
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
        val workers = concurrency.coerceIn(1, 8).coerceAtMost(segments.size)
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
        for (attempt in 0..2) {
            var bytesCountedThisAttempt = 0
            try {
                val segReqBuilder = Request.Builder()
                    .url(seg.url)
                    .header("Accept", "*/*")
                headers.forEach { (k, v) -> segReqBuilder.header(k, v) }
                if (seg.byteRangeStart >= 0L && seg.byteRangeLength > 0L) {
                    val rangeEnd = seg.byteRangeStart + seg.byteRangeLength - 1L
                    segReqBuilder.header("Range", "bytes=${seg.byteRangeStart}-$rangeEnd")
                }

                val rawBytes = httpClient.newCall(segReqBuilder.build()).execute().use { segResp ->
                    if (segResp.code == 401 || segResp.code == 403) {
                        throw ExtractorException(
                            DownloaderErrorType.PRIVATE_OR_RESTRICTED,
                            "HLS segment denied (HTTP ${segResp.code})."
                        )
                    }
                    if (segResp.code == 429 || segResp.code in 500..504) {
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
                if (attempt >= 2) throw e
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
