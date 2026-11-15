package com.example.data.downloader

import android.os.SystemClock
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class WarpDnsTelemetry(
    val isEnabled: Boolean = false,
    val resolvedHostCount: Int = 0,
    val cacheHitCount: Int = 0,
    val lastLatencyMs: Long = 9L,
    val activeResolverLabel: String = "System Default DNS"
)

/**
 * High-speed Cloudflare WARP DNS (1.1.1.1 / 1.0.0.1) accelerator implementing [okhttp3.Dns]
 * and providing WebView DNS resolution, Google DoH fallback, and ISP-block HTTP/HTTPS tunneling.
 */
class CloudflareWarpDns(
    initialEnabled: Boolean = false
) : Dns {

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtElapsedMs: Long
    )

    internal data class DnsWireResult(
        val addresses: List<InetAddress>,
        val ttlSeconds: Long,
        val isSinkholed: Boolean = false,
        val isTruncated: Boolean = false
    )

    @Volatile
    var isEnabled: Boolean = initialEnabled
        private set

    val isAnyCustomDnsActive: Boolean
        get() = isEnabled

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val systemDnsFailedHosts = ConcurrentHashMap<String, Long>()
    private val forcedTunnelHosts = ConcurrentHashMap.newKeySet<String>()
    private val inFlightPrewarmHosts = ConcurrentHashMap.newKeySet<String>()
    private val prewarmExecutor by lazy { java.util.concurrent.Executors.newFixedThreadPool(6) }

    private val resolvedCounter = AtomicInteger(0)
    private val cacheHitCounter = AtomicInteger(0)
    private val lastLatencyMsAtomic = AtomicLong(9L)

    // Adaptive UDP health tracker: if carrier/firewall drops UDP/53, go straight to DoH without waiting
    private val udpConsecutiveFailures = AtomicInteger(0)
    @Volatile
    private var udpBypassUntilElapsedMs: Long = 0L

    private val _telemetryFlow = MutableStateFlow(
        WarpDnsTelemetry(
            isEnabled = initialEnabled,
            activeResolverLabel = buildResolverLabel(initialEnabled)
        )
    )
    val telemetryFlow: StateFlow<WarpDnsTelemetry> = _telemetryFlow.asStateFlow()

    // Bootstrap client resolves Cloudflare (1.1.1.1/1.0.0.1) and Google (8.8.8.8/8.8.4.4)
    // directly by literal IP bytes so DoH never depends on system DNS.
    private val dohBootstrapClient: OkHttpClient by lazy {
        val staticDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return when (hostname.lowercase(Locale.US)) {
                    PRIMARY_IPV4, "cloudflare-dns.com", "one.one.one.one", WARP_DOT_HOSTNAME -> listOf(
                        InetAddress.getByAddress(hostname, PRIMARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, SECONDARY_IPV4_BYTES)
                    )
                    SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, SECONDARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, PRIMARY_IPV4_BYTES)
                    )
                    GOOGLE_PRIMARY_IPV4, GOOGLE_DOH_HOST -> listOf(
                        InetAddress.getByAddress(hostname, GOOGLE_PRIMARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, GOOGLE_SECONDARY_IPV4_BYTES)
                    )
                    GOOGLE_SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, GOOGLE_SECONDARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, GOOGLE_PRIMARY_IPV4_BYTES)
                    )
                    else -> Dns.SYSTEM.lookup(hostname)
                }
            }
        }
        OkHttpClient.Builder()
            .dns(staticDns)
            .connectionPool(ConnectionPool(6, 3, TimeUnit.MINUTES))
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Browser tunnel client using this CloudflareWarpDns resolver for ISP-blocked websites and video CDNs
    private val browserTunnelClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(this)
            .connectionPool(ConnectionPool(16, 3, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        resetStateOnModeSwitch()
    }

    private fun resetStateOnModeSwitch() {
        dnsCache.clear()
        systemDnsFailedHosts.clear()
        inFlightPrewarmHosts.clear()
        udpConsecutiveFailures.set(0)
        udpBypassUntilElapsedMs = 0L
        publishTelemetry()
    }

    fun clearCache() {
        dnsCache.clear()
        systemDnsFailedHosts.clear()
        inFlightPrewarmHosts.clear()
        publishTelemetry()
    }

    fun markHostRequiresTunnel(hostname: String?) {
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return
        if (normalized.isNotBlank() && normalized != "localhost" && !isIpLiteral(normalized)) {
            forcedTunnelHosts.add(normalized)
            systemDnsFailedHosts[normalized] = SystemClock.elapsedRealtime() + 180_000L
        }
    }

    /**
     * Pre-resolves [hostname] via Cloudflare WARP DNS (`1.1.1.1`)
     * and checks in background whether System DNS fails/poisons this host so ISP-blocked domains
     * are seamlessly tunneled.
     */
    fun prewarmHostAsync(hostname: String) {
        if (!isAnyCustomDnsActive) return
        val normalized = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return
        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalized]
        if (cached != null && now < cached.expiresAtElapsedMs && systemDnsFailedHosts.containsKey(normalized)) {
            cacheHitCounter.incrementAndGet()
            publishTelemetry()
            return
        }
        if (!inFlightPrewarmHosts.add(normalized)) return
        try {
            prewarmExecutor.execute {
                try {
                    lookup(normalized)
                    checkIfSystemDnsPoisonedOrFailed(normalized)
                } catch (_: Throwable) {
                } finally {
                    inFlightPrewarmHosts.remove(normalized)
                }
            }
        } catch (_: Throwable) {
            inFlightPrewarmHosts.remove(normalized)
        }
    }

    private fun checkIfSystemDnsPoisonedOrFailed(normalizedHost: String) {
        val now = SystemClock.elapsedRealtime()
        val existingExpiry = systemDnsFailedHosts[normalizedHost]
        if (existingExpiry != null && (existingExpiry == 0L || now < existingExpiry)) return

        val failedOrPoisoned = try {
            val sysAddrs = Dns.SYSTEM.lookup(normalizedHost)
            sysAddrs.isEmpty() || sysAddrs.all { addr -> isBogonOrSinkholeAddress(addr) }
        } catch (_: UnknownHostException) {
            true
        } catch (_: Throwable) {
            false
        }
        systemDnsFailedHosts[normalizedHost] = if (failedOrPoisoned) now + 180_000L else 0L
        if (failedOrPoisoned) {
            forcedTunnelHosts.add(normalizedHost)
        } else {
            forcedTunnelHosts.remove(normalizedHost)
        }
    }

    /**
     * Determines whether a browser WebView request should be tunneled through [browserTunnelClient]
     * using our active Custom DNS resolver (because System DNS is blocked/poisoned for this host).
     */
    fun shouldTunnelBrowserRequest(
        hostname: String?,
        method: String?,
        requestHeaders: Map<String, String>?
    ): Boolean {
        val m = method?.uppercase(Locale.US) ?: "GET"
        if (m != "GET" && m != "HEAD") return false
        if (!isAnyCustomDnsActive) return false
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return false
        if (isAuthOrSessionCriticalHost(normalized)) return false

        // Do not intercept WebSocket upgrades or EventSource streams
        val upgrade = requestHeaders?.entries?.firstOrNull { it.key.equals("Upgrade", true) }?.value
        if (upgrade.equals("websocket", ignoreCase = true)) return false
        val accept = requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
        if (accept?.contains("text/event-stream", ignoreCase = true) == true) return false

        val failedUntil = systemDnsFailedHosts[normalized] ?: 0L
        if (forcedTunnelHosts.contains(normalized) || failedUntil > SystemClock.elapsedRealtime()) {
            return true
        }
        return false
    }

    /**
     * Executes an HTTP/HTTPS GET/HEAD request through [browserTunnelClient] backed by Cloudflare WARP
     * DNS (`1.1.1.1`), preserving byte-range `206 Partial Content` streaming headers and CORS headers
     * so ISP-blocked websites and video CDNs work inside Android WebView when WARP DNS is enabled.
     */
    fun executeBrowserTunnelRequest(
        requestUrl: String,
        method: String?,
        requestHeaders: Map<String, String>?,
        isMainFrame: Boolean,
        defaultUserAgent: String
    ): WebResourceResponse? {
        return try {
            val reqBuilder = Request.Builder()
                .url(requestUrl)
                .method(method?.uppercase(Locale.US) ?: "GET", null)

            var hasUserAgent = false
            var hasAccept = false
            var hasAcceptLanguage = false
            requestHeaders?.forEach { (k, v) ->
                // Skip hop-by-hop or encoding headers so OkHttp handles decompression transparently
                if (!k.equals("Accept-Encoding", ignoreCase = true) &&
                    !k.equals("Host", ignoreCase = true) &&
                    !k.equals("Connection", ignoreCase = true)
                ) {
                    reqBuilder.header(k, v)
                    if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    if (k.equals("Accept", ignoreCase = true)) hasAccept = true
                    if (k.equals("Accept-Language", ignoreCase = true)) hasAcceptLanguage = true
                }
            }
            if (!hasUserAgent && defaultUserAgent.isNotBlank()) {
                reqBuilder.header("User-Agent", defaultUserAgent)
            }
            if (isMainFrame && !hasAccept) {
                reqBuilder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            }
            if (!hasAcceptLanguage) {
                reqBuilder.header("Accept-Language", "en-US,en;q=0.9")
            }

            val response = browserTunnelClient.newCall(reqBuilder.build()).execute()
            val finalUrl = response.request.url.toString()

            val rawContentType = response.header("Content-Type") ?: guessMimeTypeFromUrl(finalUrl)
            val mimeType = rawContentType.substringBefore(';').trim().ifBlank { "text/html" }
            val charset = rawContentType
                .substringAfter("charset=", "")
                .substringBefore(';')
                .trim()
                .removeSurrounding("\"")
                .ifBlank {
                    if (mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("javascript") || mimeType.contains("xml")) {
                        "UTF-8"
                    } else {
                        null
                    }
                }

            val respHeaders = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) {
                val name = response.headers.name(i)
                val value = response.headers.value(i)
                if (!name.equals("Content-Encoding", ignoreCase = true) &&
                    !name.equals("Content-Length", ignoreCase = true) &&
                    !name.equals("Transfer-Encoding", ignoreCase = true)
                ) {
                    respHeaders[name] = value
                }
            }
            fun hasRespHeader(headerName: String): Boolean =
                respHeaders.keys.any { it.equals(headerName, ignoreCase = true) }

            if (!isMainFrame) {
                val reqOrigin = requestHeaders?.entries
                    ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
                    ?.value?.takeIf { it.isNotBlank() }
                val allowsCredentials = respHeaders.entries
                    .firstOrNull { it.key.equals("Access-Control-Allow-Credentials", ignoreCase = true) }
                    ?.value?.equals("true", ignoreCase = true) == true
                if (!hasRespHeader("Access-Control-Allow-Origin")) {
                    respHeaders["Access-Control-Allow-Origin"] = if (allowsCredentials && reqOrigin != null) reqOrigin else "*"
                }
                if (!hasRespHeader("Access-Control-Allow-Methods")) {
                    respHeaders["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
                }
                if (!hasRespHeader("Access-Control-Allow-Headers")) {
                    respHeaders["Access-Control-Allow-Headers"] = "*"
                }
                if (!hasRespHeader("Access-Control-Expose-Headers")) {
                    respHeaders["Access-Control-Expose-Headers"] = "Content-Length, Content-Range, Accept-Ranges"
                }
            }

            val statusCode = response.code.coerceIn(100, 599)
            val reasonPhrase = response.message.ifBlank {
                when (statusCode) {
                    200 -> "OK"
                    206 -> "Partial Content"
                    204 -> "No Content"
                    301, 302, 307, 308 -> "Redirect"
                    404 -> "Not Found"
                    416 -> "Range Not Satisfiable"
                    else -> "OK"
                }
            }

            val rawBodyStream = response.body?.byteStream()
            val bodyStream: java.io.InputStream = if (rawBodyStream != null) {
                object : java.io.FilterInputStream(rawBodyStream) {
                    override fun close() {
                        try {
                            super.close()
                        } finally {
                            runCatching { response.close() }
                        }
                    }
                }
            } else {
                runCatching { response.close() }
                ByteArrayInputStream(ByteArray(0))
            }
            WebResourceResponse(
                mimeType,
                charset,
                statusCode,
                reasonPhrase,
                respHeaders,
                bodyStream
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun prewarmCommonHosts(scope: CoroutineScope) {
        if (!isAnyCustomDnsActive) return
        scope.launch(Dispatchers.IO) {
            COMMON_WARMUP_HOSTS.forEach { host ->
                if (!isAnyCustomDnsActive) return@launch
                try {
                    lookup(host)
                } catch (_: Exception) {}
            }
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val normalizedHost = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (!isAnyCustomDnsActive || normalizedHost.isBlank() || normalizedHost == "localhost" || isIpLiteral(normalizedHost)) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalizedHost]
        if (cached != null && now < cached.expiresAtElapsedMs && cached.addresses.isNotEmpty()) {
            cacheHitCounter.incrementAndGet()
            publishTelemetry()
            return cached.addresses
        }

        val startMs = SystemClock.elapsedRealtime()
        val resolvedAddresses = LinkedHashSet<InetAddress>()
        var ttlSeconds = DEFAULT_TTL_SECONDS
        val tryUdpFirst = SystemClock.elapsedRealtime() >= udpBypassUntilElapsedMs

        // 1. Fast UDP/53 query to Cloudflare WARP DNS (1.1.1.1 / 1.0.0.1)
        if (tryUdpFirst) {
            val udpResult = queryDnsUdpWire(hostname, PRIMARY_IPV4_BYTES)
                ?: queryDnsUdpWire(hostname, SECONDARY_IPV4_BYTES)
            if (udpResult != null && !udpResult.isTruncated && udpResult.addresses.isNotEmpty()) {
                udpConsecutiveFailures.set(0)
                resolvedAddresses.addAll(udpResult.addresses)
                ttlSeconds = udpResult.ttlSeconds
            } else if (udpResult == null) {
                recordUdpFailure()
            }
        }

        // 2. Cloudflare DoH (JSON API + RFC 8484 Wireformat fallback) + Google 8.8.8.8 DoH fallback
        if (resolvedAddresses.isEmpty()) {
            val dohResult = queryCloudflareDoh(hostname)
            if (dohResult != null && dohResult.first.isNotEmpty()) {
                resolvedAddresses.addAll(dohResult.first)
                ttlSeconds = dohResult.second
            } else {
                val wireDoh = queryCloudflareDohWire(hostname, PRIMARY_IPV4)
                if (wireDoh != null && wireDoh.addresses.isNotEmpty()) {
                    resolvedAddresses.addAll(wireDoh.addresses)
                    ttlSeconds = wireDoh.ttlSeconds
                } else {
                    val googleFallback = queryGoogleDoh(hostname)
                    if (googleFallback != null && googleFallback.first.isNotEmpty()) {
                        resolvedAddresses.addAll(googleFallback.first)
                        ttlSeconds = googleFallback.second
                    }
                }
            }
        }

        val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        lastLatencyMsAtomic.set(elapsed)

        // 3. Only fall back to System DNS if all DoH/UDP resolvers returned no addresses
        if (resolvedAddresses.isEmpty()) {
            val sysAddrs = Dns.SYSTEM.lookup(hostname)
            sysAddrs.forEach { addr ->
                if (!isBogonOrSinkholeAddress(addr)) {
                    resolvedAddresses.add(InetAddress.getByAddress(hostname, addr.address))
                }
            }
            if (resolvedAddresses.isEmpty()) {
                return sysAddrs
            }
        }

        // Prioritize IPv4 addresses first (Happy Eyeballs) to avoid broken IPv6 carrier routes
        val sorted = resolvedAddresses.sortedBy { addr ->
            if (addr is Inet4Address) 0 else 1
        }

        resolvedCounter.incrementAndGet()
        val boundedTtlMs = (ttlSeconds.coerceIn(60L, 600L)) * 1000L
        dnsCache[normalizedHost] = CachedDnsEntry(
            addresses = sorted,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + boundedTtlMs
        )
        publishTelemetry()
        return sorted
    }

    private fun recordUdpFailure() {
        val fails = udpConsecutiveFailures.incrementAndGet()
        if (fails >= 1) {
            // Carrier/router blocks UDP port 53 -> use encrypted DoH directly for next 2 minutes
            udpBypassUntilElapsedMs = SystemClock.elapsedRealtime() + 120_000L
        }
    }

    private fun publishTelemetry() {
        _telemetryFlow.value = WarpDnsTelemetry(
            isEnabled = isEnabled,
            resolvedHostCount = resolvedCounter.get(),
            cacheHitCount = cacheHitCounter.get(),
            lastLatencyMs = lastLatencyMsAtomic.get(),
            activeResolverLabel = buildResolverLabel(isEnabled)
        )
    }

    private fun buildResolverLabel(warpOn: Boolean): String {
        return if (warpOn) "1.1.1.1 • Cloudflare WARP (DoH + UDP)" else "System Default DNS"
    }

    /**
     * Queries Cloudflare DoH JSON API (`https://cloudflare-dns.com/dns-query?name=...`) with matching SNI/Host,
     * falling back to AAAA if A is empty.
     */
    internal fun queryCloudflareDoh(hostname: String): Pair<List<InetAddress>, Long>? {
        val encoded = runCatching { URLEncoder.encode(hostname, "UTF-8") }.getOrNull() ?: return null
        for (qType in listOf("A", "AAAA")) {
            val res = runCatching {
                val url = "https://cloudflare-dns.com/dns-query?name=$encoded&type=$qType"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()

                dohBootstrapClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    parseDohJsonResponse(hostname, body)
                }
            }.getOrNull()
            if (res != null && res.first.isNotEmpty()) {
                return res
            }
        }
        return null
    }

    /**
     * Queries Google Public DoH JSON API (`https://dns.google/resolve?name=...`) as resilient fallback.
     */
    internal fun queryGoogleDoh(hostname: String): Pair<List<InetAddress>, Long>? {
        val encoded = runCatching { URLEncoder.encode(hostname, "UTF-8") }.getOrNull() ?: return null
        for (qType in listOf("A", "AAAA")) {
            val res = runCatching {
                val url = "https://dns.google/resolve?name=$encoded&type=$qType"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()

                dohBootstrapClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    parseDohJsonResponse(hostname, body)
                }
            }.getOrNull()
            if (res != null && res.first.isNotEmpty()) {
                return res
            }
        }
        return null
    }

    /**
     * Queries RFC 8484 Wireformat DoH (`https://1.1.1.1/dns-query`).
     */
    internal fun queryCloudflareDohWire(hostname: String, dohHost: String = PRIMARY_IPV4): DnsWireResult? {
        return try {
            val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
            if (labels.isEmpty()) return null
            val wirePacket = buildDnsQueryPacket(labels, qType = 1)
            val base64Url = android.util.Base64.encodeToString(
                wirePacket,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val url = "https://$dohHost/dns-query?dns=$base64Url"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-message")
                .get()
                .build()

            dohBootstrapClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                parseDnsWireResponseDetailed(hostname, bytes, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PRIMARY_IPV4 = "1.1.1.1"
        const val SECONDARY_IPV4 = "1.0.0.1"
        const val WARP_DOT_HOSTNAME = "1dot1dot1dot1.cloudflare-dns.com"
        const val GOOGLE_PRIMARY_IPV4 = "8.8.8.8"
        const val GOOGLE_SECONDARY_IPV4 = "8.8.4.4"
        const val GOOGLE_DOH_HOST = "dns.google"
        private const val DEFAULT_TTL_SECONDS = 300L

        private val PRIMARY_IPV4_BYTES = byteArrayOf(1, 1, 1, 1)
        private val SECONDARY_IPV4_BYTES = byteArrayOf(1, 0, 0, 1)
        private val GOOGLE_PRIMARY_IPV4_BYTES = byteArrayOf(8, 8, 8, 8)
        private val GOOGLE_SECONDARY_IPV4_BYTES = byteArrayOf(8, 8, 4, 4)

        private val COMMON_WARMUP_HOSTS = listOf(
            "www.google.com",
            "www.youtube.com",
            "m.youtube.com",
            "patreon.com",
            "www.patreon.com",
            "bilibili.tv",
            "www.bilibili.tv",
            "www.dailymotion.com",
            "vimeo.com",
            "player.vimeo.com",
            "pawchive.pw"
        )

        // Never block legitimate video/audio streaming CDNs or core website domains
        private val ESSENTIAL_STREAMING_HOST_SUFFIXES = listOf(
            "googlevideo.com",
            "youtube.com",
            "ytimg.com",
            "ggpht.com",
            "youtu.be",
            "bilibili.tv",
            "bilibili.com",
            "bilivideo.com",
            "hdslb.com",
            "akamaized.net",
            "akamaihd.net",
            "cloudfront.net",
            "cloudflare.com",
            "cdnjs.cloudflare.com",
            "jsdelivr.net",
            "unpkg.com",
            "fastly.net",
            "vimeo.com",
            "vimeocdn.com",
            "dailymotion.com",
            "dmcdn.net",
            "twitch.tv",
            "ttvnw.net",
            "jwpcdn.com",
            "jwplatform.com",
            "jwpsrv.com",
            "vjs.zencdn.net",
            "patreon.com",
            "patreonusercontent.com",
            "tiktok.com",
            "tiktokcdn.com",
            "fbcdn.net",
            "cdninstagram.com",
            "twimg.com",
            "twitter.com",
            "x.com",
            "reddit.com",
            "redd.it",
            "redditmedia.com",
            "google.com",
            "gstatic.com",
            "googleapis.com",
            "github.com",
            "githubusercontent.com",
            "archive.org",
            "mega.nz",
            "b-cdn.net",
            "bunnycdn.com",
            "mediadelivery.net",
            "mux.com",
            "bitmovin.com",
            "brightcove.com",
            "brightcovecdn.com",
            "cloudflarestream.com",
            "videodelivery.net",
            "r2.dev",
            "amazonaws.com",
            "azureedge.net",
            "edgesuite.net",
            "edgecastcdn.net",
            "stackpathcdn.com",
            "cdn77.org",
            "gcdn.co",
            "lldns.net",
            "streamable.com",
            "vidyard.com",
            "wistia.com",
            "wistia.net",
            "rumble.com",
            "odysee.com",
            "vk.com",
            "vkuservideo.net",
            "ok.ru",
            "mycdn.me",
            "pawchive.pw",
            "biliintl.com",
            "bilivideo.cn",
            "streamtape.com",
            "doodstream.com",
            "filemoon.sx",
            "sibnet.ru"
        )

        fun isAuthOrSessionCriticalHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            return h == "accounts.google.com" ||
                h.startsWith("accounts.google.") ||
                h == "myaccount.google.com" ||
                h.startsWith("myaccount.google.") ||
                h == "accounts.youtube.com" ||
                h == "ogs.google.com" ||
                h == "consent.google.com" ||
                h == "gds.google.com"
        }

        fun isMediaStreamingOrEssentialHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            return ESSENTIAL_STREAMING_HOST_SUFFIXES.any { suffix ->
                h == suffix || h.endsWith(".$suffix")
            }
        }

        fun isLikelyMediaStreamAssetUrl(url: String): Boolean {
            val pathLower = url.substringBefore('?').lowercase(Locale.US)
            val fullLower = url.lowercase(Locale.US)
            return pathLower.endsWith(".m3u8") ||
                pathLower.endsWith(".m3u") ||
                pathLower.endsWith(".mpd") ||
                pathLower.endsWith(".ts") ||
                pathLower.endsWith(".m4s") ||
                pathLower.endsWith(".m4f") ||
                pathLower.endsWith(".fmp4") ||
                pathLower.endsWith(".mp4") ||
                pathLower.endsWith(".m4v") ||
                pathLower.endsWith(".webm") ||
                pathLower.endsWith(".mkv") ||
                pathLower.endsWith(".mov") ||
                pathLower.endsWith(".mp3") ||
                pathLower.endsWith(".m4a") ||
                pathLower.endsWith(".aac") ||
                pathLower.endsWith(".flac") ||
                pathLower.endsWith(".ogg") ||
                pathLower.endsWith(".opus") ||
                pathLower.endsWith(".vtt") ||
                pathLower.endsWith(".srt") ||
                pathLower.endsWith(".key") ||
                pathLower.endsWith(".ism") ||
                pathLower.endsWith(".isml") ||
                pathLower.endsWith(".f4m") ||
                pathLower.endsWith(".f4v") ||
                fullLower.contains("videoplayback") ||
                fullLower.contains("mime=video") ||
                fullLower.contains("mime=audio") ||
                fullLower.contains("mime%3dvideo") ||
                fullLower.contains("mime%3daudio") ||
                fullLower.contains(".m3u8") ||
                fullLower.contains(".mpd") ||
                fullLower.contains("/hls/") ||
                fullLower.contains("/dash/") ||
                fullLower.contains("/playlist/") ||
                fullLower.contains("/manifest") ||
                fullLower.contains("/chunk") ||
                fullLower.contains("/segment") ||
                fullLower.contains("/frag-")
        }


        fun isBogonOrSinkholeAddress(addr: InetAddress): Boolean {
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress) return true
            val hostAddr = addr.hostAddress ?: return true
            return hostAddr == "0.0.0.0" ||
                hostAddr == "127.0.0.1" ||
                hostAddr == "::" ||
                hostAddr == "::1" ||
                hostAddr == "0:0:0:0:0:0:0:0" ||
                hostAddr == "0:0:0:0:0:0:0:1"
        }

        private fun guessMimeTypeFromUrl(url: String): String {
            val path = url.substringBefore('?').lowercase(Locale.US)
            return when {
                path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
                path.endsWith(".webm") -> "video/webm"
                path.endsWith(".ts") -> "video/mp2t"
                path.endsWith(".m4s") -> "video/iso.segment"
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".m4a") -> "audio/mp4"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".svg") -> "image/svg+xml"
                else -> "text/html"
            }
        }

        fun isIpLiteral(host: String): Boolean {
            val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
            return ipv4Regex.matches(host) || host.contains(':')
        }

        /**
         * Parses a Cloudflare `application/dns-json` response payload into [InetAddress] records
         * with preserved TLS SNI hostname.
         */
        fun parseDohJsonResponse(hostname: String, jsonText: String): Pair<List<InetAddress>, Long>? {
            return try {
                val root = JSONObject(jsonText)
                if (root.optInt("Status", -1) != 0) return null
                val answers = root.optJSONArray("Answer") ?: return null
                val results = mutableListOf<InetAddress>()
                var minTtl = DEFAULT_TTL_SECONDS

                for (i in 0 until answers.length()) {
                    val ans = answers.optJSONObject(i) ?: continue
                    val type = ans.optInt("type", 0)
                    val data = ans.optString("data", "").trim()
                    val ttl = ans.optLong("TTL", DEFAULT_TTL_SECONDS)
                    if (ttl > 0L && ttl < minTtl) minTtl = ttl

                    if (type == 1 && isIpLiteral(data)) {
                        val parts = data.split('.')
                        if (parts.size == 4) {
                            val bytes = ByteArray(4) { idx ->
                                (parts[idx].toIntOrNull() ?: 0).toByte()
                            }
                            val addr = InetAddress.getByAddress(hostname, bytes)
                            if (!isBogonOrSinkholeAddress(addr)) {
                                results.add(addr)
                            }
                        }
                    } else if (type == 28 && data.contains(':')) {
                        runCatching {
                            val raw = InetAddress.getByName(data).address
                            val addr = InetAddress.getByAddress(hostname, raw)
                            if (!isBogonOrSinkholeAddress(addr)) {
                                results.add(addr)
                            }
                        }
                    }
                }
                if (results.isEmpty()) null else (results to minTtl)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Sends an RFC 1035 UDP DNS query to the target DNS server IP:53 and returns [DnsWireResult].
         */
        internal fun queryDnsUdpWire(hostname: String, serverIpBytes: ByteArray): DnsWireResult? {
            return try {
                val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
                if (labels.isEmpty()) return null

                val packetBytes = buildDnsQueryPacket(labels, qType = 1)
                DatagramSocket().use { socket ->
                    socket.soTimeout = 350
                    val serverAddr = InetAddress.getByAddress(serverIpBytes)
                    val outPacket = DatagramPacket(packetBytes, packetBytes.size, serverAddr, 53)
                    socket.send(outPacket)

                    val recvBuf = ByteArray(1500)
                    val inPacket = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(inPacket)
                    parseDnsWireResponseDetailed(hostname, recvBuf, inPacket.length)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun queryCloudflareUdpA(hostname: String, serverIpBytes: ByteArray): Pair<List<InetAddress>, Long>? {
            val res = queryDnsUdpWire(hostname, serverIpBytes) ?: return null
            return if (res.addresses.isEmpty()) null else (res.addresses to res.ttlSeconds)
        }

        internal fun buildDnsQueryPacket(labels: List<String>, qType: Int = 1): ByteArray {
            val baos = ByteArrayOutputStream(128)
            val dos = DataOutputStream(baos)
            // Transaction ID: 0x1111, Flags: 0x0100 (Standard query, recursion desired)
            dos.writeShort(0x1111)
            dos.writeShort(0x0100)
            // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0) // End of QNAME
            dos.writeShort(qType) // QTYPE = A (1) or AAAA (28)
            dos.writeShort(1) // QCLASS = IN (1)
            dos.flush()
            return baos.toByteArray()
        }

        internal fun parseDnsWireResponseDetailed(
            hostname: String,
            buf: ByteArray,
            length: Int
        ): DnsWireResult? {
            if (length < 12) return null
            val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val isTruncated = (flags and 0x0200) != 0
            if (isTruncated) {
                return DnsWireResult(
                    addresses = emptyList(),
                    ttlSeconds = DEFAULT_TTL_SECONDS,
                    isSinkholed = false,
                    isTruncated = true
                )
            }

            val rcode = flags and 0x0F
            if (rcode != 0) return null

            val qdCount = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            if (anCount <= 0) {
                return DnsWireResult(
                    addresses = emptyList(),
                    ttlSeconds = 120L,
                    isSinkholed = false,
                    isTruncated = false
                )
            }

            var offset = 12
            // Skip Questions
            repeat(qdCount) {
                offset = skipDnsName(buf, offset, length)
                if (offset < 0 || offset + 4 > length) return null
                offset += 4
            }

            val addresses = mutableListOf<InetAddress>()
            var minTtl = DEFAULT_TTL_SECONDS
            var sawSinkholeIp = false

            repeat(anCount) {
                offset = skipDnsName(buf, offset, length)
                if (offset < 0 || offset + 10 > length) return@repeat

                val type = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
                val ttl = (((buf[offset + 4].toLong() and 0xFF) shl 24) or
                    ((buf[offset + 5].toLong() and 0xFF) shl 16) or
                    ((buf[offset + 6].toLong() and 0xFF) shl 8) or
                    (buf[offset + 7].toLong() and 0xFF)).coerceAtLeast(30L)
                val rdLength = ((buf[offset + 8].toInt() and 0xFF) shl 8) or (buf[offset + 9].toInt() and 0xFF)
                offset += 10

                if (offset + rdLength > length) return@repeat
                if ((type == 1 && rdLength == 4) || (type == 28 && rdLength == 16)) {
                    val ipBytes = buf.copyOfRange(offset, offset + rdLength)
                    val addr = InetAddress.getByAddress(hostname, ipBytes)
                    if (isBogonOrSinkholeAddress(addr)) {
                        sawSinkholeIp = true
                    } else {
                        addresses.add(addr)
                        if (ttl < minTtl) minTtl = ttl
                    }
                }
                offset += rdLength
            }

            return DnsWireResult(
                addresses = addresses,
                ttlSeconds = minTtl,
                isSinkholed = sawSinkholeIp && addresses.isEmpty(),
                isTruncated = false
            )
        }

        internal fun parseDnsUdpResponse(
            hostname: String,
            buf: ByteArray,
            length: Int
        ): Pair<List<InetAddress>, Long>? {
            val detailed = parseDnsWireResponseDetailed(hostname, buf, length) ?: return null
            return if (detailed.addresses.isEmpty()) null else (detailed.addresses to detailed.ttlSeconds)
        }

        private fun skipDnsName(buf: ByteArray, startOffset: Int, length: Int): Int {
            var pos = startOffset
            while (pos < length) {
                val len = buf[pos].toInt() and 0xFF
                if (len == 0) {
                    return pos + 1
                }
                if ((len and 0xC0) == 0xC0) {
                    // DNS name compression pointer (2 bytes)
                    return pos + 2
                }
                pos += 1 + len
            }
            return -1
        }
    }
}
