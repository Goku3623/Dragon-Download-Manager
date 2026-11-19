package com.example.data.downloader

import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class DnsProviderMode(
    val id: String,
    val title: String,
    val shortBadge: String,
    val subtitle: String,
    val primaryServer: String
) {
    SYSTEM_DEFAULT(
        id = "system_default",
        title = "System Default (Off)",
        shortBadge = "OFF",
        subtitle = "Standard Android & ISP network DNS",
        primaryServer = "System"
    ),
    CLOUDFLARE_WARP(
        id = "cloudflare_warp",
        title = "Cloudflare WARP DNS",
        shortBadge = "WARP",
        subtitle = "1.1.1.1 / 1.0.0.1 • Ultra-fast DoH + ISP block bypass",
        primaryServer = "1.1.1.1"
    ),
    ADBLOCKER_DNS(
        id = "adblocker_dns",
        title = "AdBlocker DNS (AdGuard)",
        shortBadge = "ADBLOCK",
        subtitle = "94.140.14.14 • Blocks ads, popups & trackers at DNS level",
        primaryServer = "94.140.14.14"
    ),
    UNFILTERED_ADBLOCKER_DNS(
        id = "unfiltered_adblocker_dns",
        title = "Unfiltered AdBlocker DNS",
        shortBadge = "UNFILTERED",
        subtitle = "94.140.14.140 • AdGuard Unfiltered (No censorship, ISP bypass)",
        primaryServer = "94.140.14.140"
    ),
    CUSTOM_DNS(
        id = "custom_dns",
        title = "Custom DNS",
        shortBadge = "CUSTOM",
        subtitle = "User-defined DoH URL or IPv4 DNS server",
        primaryServer = "Custom"
    );

    companion object {
        fun fromId(raw: String?): DnsProviderMode {
            if (raw.isNullOrBlank()) return SYSTEM_DEFAULT
            return entries.firstOrNull {
                it.id.equals(raw.trim(), ignoreCase = true) ||
                    it.name.equals(raw.trim(), ignoreCase = true)
            } ?: SYSTEM_DEFAULT
        }
    }
}

data class CustomDnsServerEntry(
    val id: String,
    val name: String,
    val primaryServer: String,
    val secondaryServer: String = ""
) {
    val displayEndpoint: String
        get() = if (secondaryServer.isNotBlank()) "$primaryServer / $secondaryServer" else primaryServer
}

data class WarpDnsTelemetry(
    val isEnabled: Boolean = false,
    val dnsMode: DnsProviderMode = DnsProviderMode.SYSTEM_DEFAULT,
    val activeCustomDns: CustomDnsServerEntry? = null,
    val resolvedHostCount: Int = 0,
    val cacheHitCount: Int = 0,
    val blockedAdHostCount: Int = 0,
    val lastLatencyMs: Long = 9L,
    val activeResolverLabel: String = "System Default DNS"
)

data class MainFrameDownloadIntercept(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String,
    val contentLength: Long
)

/**
 * Multi-mode DNS Accelerator & AdBlocker implementing [okhttp3.Dns]:
 * - System Default DNS
 * - Cloudflare WARP DNS (1.1.1.1 / 1.0.0.1 • Encrypted DoH primary + UDP/TCP fallback)
 * - AdBlocker DNS (AdGuard Default 94.140.14.14 / 94.140.15.15 • Encrypted DoH + DNS ad/popup/tracker sinkholing)
 * - Unfiltered AdBlocker DNS (AdGuard Unfiltered 94.140.14.140 / 94.140.14.141 • Encrypted DoH + UDP/TCP)
 * - Custom DNS (User-defined DoH URL, DNS hostname, or IPv4 primary/secondary addresses)
 */
class CloudflareWarpDns(
    initialEnabled: Boolean = false,
    initialMode: DnsProviderMode = if (initialEnabled) DnsProviderMode.CLOUDFLARE_WARP else DnsProviderMode.SYSTEM_DEFAULT,
    initialCustomDns: CustomDnsServerEntry? = null
) : Dns {

    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtElapsedMs: Long,
        val isSinkholed: Boolean = false
    )

    internal data class DnsWireResult(
        val addresses: List<InetAddress>,
        val ttlSeconds: Long,
        val isSinkholed: Boolean = false,
        val isTruncated: Boolean = false
    )

    @Volatile
    var dnsMode: DnsProviderMode = initialMode
        private set

    @Volatile
    var activeCustomDns: CustomDnsServerEntry? = initialCustomDns
        private set

    @Volatile
    var isEnabled: Boolean = (initialMode == DnsProviderMode.CLOUDFLARE_WARP)
        private set

    val isAnyCustomDnsActive: Boolean
        get() = dnsMode != DnsProviderMode.SYSTEM_DEFAULT

    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val sinkholedDomains = ConcurrentHashMap.newKeySet<String>()
    private val verifiedSafeDomains = ConcurrentHashMap.newKeySet<String>()
    private val systemDnsFailedHosts = ConcurrentHashMap<String, Long>()
    private val forcedTunnelHosts = ConcurrentHashMap.newKeySet<String>()
    private val inFlightPrewarmHosts = ConcurrentHashMap.newKeySet<String>()
    private val prewarmExecutor by lazy { java.util.concurrent.Executors.newFixedThreadPool(6) }

    private val resolvedCounter = AtomicInteger(0)
    private val cacheHitCounter = AtomicInteger(0)
    private val blockedAdCounter = AtomicInteger(0)
    private val lastLatencyMsAtomic = AtomicLong(9L)

    // Adaptive UDP health tracker: if carrier/firewall drops UDP/53, skip UDP temporarily
    private val udpConsecutiveFailures = AtomicInteger(0)
    @Volatile
    private var udpBypassUntilElapsedMs: Long = 0L

    private val _telemetryFlow = MutableStateFlow(
        WarpDnsTelemetry(
            isEnabled = isEnabled,
            dnsMode = dnsMode,
            activeCustomDns = activeCustomDns,
            activeResolverLabel = buildResolverLabel(dnsMode, activeCustomDns)
        )
    )
    val telemetryFlow: StateFlow<WarpDnsTelemetry> = _telemetryFlow.asStateFlow()

    // Bootstrap client resolves Cloudflare, AdGuard, Quad9, Google, OpenDNS, CleanBrowsing, NextDNS, and Mullvad DoH hosts
    // directly by static IPv4 bytes so encrypted DoH never depends on ISP system DNS.
    private val dohBootstrapClient: OkHttpClient by lazy {
        val staticDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val lower = hostname.lowercase(Locale.US).trimEnd('.')
                return when (lower) {
                    PRIMARY_IPV4, "cloudflare-dns.com", "one.one.one.one", WARP_DOT_HOSTNAME,
                    "mozilla.cloudflare-dns.com", "dns.cloudflare.com",
                    "security.cloudflare-dns.com", "family.cloudflare-dns.com" -> listOf(
                        InetAddress.getByAddress(hostname, PRIMARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, SECONDARY_IPV4_BYTES)
                    )
                    SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, SECONDARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, PRIMARY_IPV4_BYTES)
                    )
                    ADGUARD_FILTERED_PRIMARY_IPV4, ADGUARD_FILTERED_DOH_HOST, "dns.adguard.com" -> listOf(
                        InetAddress.getByAddress(hostname, ADGUARD_FILTERED_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, ADGUARD_FILTERED_SECONDARY_BYTES)
                    )
                    "family.adguard-dns.com", "94.140.14.15", "94.140.15.16" -> listOf(
                        InetAddress.getByAddress(hostname, ADGUARD_FAMILY_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, ADGUARD_FAMILY_SECONDARY_BYTES)
                    )
                    ADGUARD_FILTERED_SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, ADGUARD_FILTERED_SECONDARY_BYTES),
                        InetAddress.getByAddress(hostname, ADGUARD_FILTERED_PRIMARY_BYTES)
                    )
                    ADGUARD_UNFILTERED_PRIMARY_IPV4, ADGUARD_UNFILTERED_DOH_HOST, "dns-unfiltered.adguard.com" -> listOf(
                        InetAddress.getByAddress(hostname, ADGUARD_UNFILTERED_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, ADGUARD_UNFILTERED_SECONDARY_BYTES)
                    )
                    ADGUARD_UNFILTERED_SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, ADGUARD_UNFILTERED_SECONDARY_BYTES),
                        InetAddress.getByAddress(hostname, ADGUARD_UNFILTERED_PRIMARY_BYTES)
                    )
                    QUAD9_PRIMARY_IPV4, "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net" -> listOf(
                        InetAddress.getByAddress(hostname, QUAD9_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, QUAD9_SECONDARY_BYTES)
                    )
                    GOOGLE_PRIMARY_IPV4, GOOGLE_DOH_HOST, "dns.google.com", "8888.google" -> listOf(
                        InetAddress.getByAddress(hostname, GOOGLE_PRIMARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, GOOGLE_SECONDARY_IPV4_BYTES)
                    )
                    GOOGLE_SECONDARY_IPV4 -> listOf(
                        InetAddress.getByAddress(hostname, GOOGLE_SECONDARY_IPV4_BYTES),
                        InetAddress.getByAddress(hostname, GOOGLE_PRIMARY_IPV4_BYTES)
                    )
                    "doh.opendns.com", "208.67.222.222", "208.67.220.220" -> listOf(
                        InetAddress.getByAddress(hostname, OPENDNS_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, OPENDNS_SECONDARY_BYTES)
                    )
                    "doh.cleanbrowsing.org", "185.228.168.9", "185.228.169.9" -> listOf(
                        InetAddress.getByAddress(hostname, CLEANBROWSING_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, CLEANBROWSING_SECONDARY_BYTES)
                    )
                    "freedns.controld.com", "76.76.2.0", "76.76.2.2" -> listOf(
                        InetAddress.getByAddress(hostname, CONTROLD_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, CONTROLD_SECONDARY_BYTES)
                    )
                    "dns.mullvad.net", "doh.mullvad.net", "adblock.dns.mullvad.net", "base.dns.mullvad.net",
                    "extended.dns.mullvad.net", "all.dns.mullvad.net" -> listOf(
                        InetAddress.getByAddress(hostname, MULLVAD_PRIMARY_BYTES),
                        InetAddress.getByAddress(hostname, MULLVAD_SECONDARY_BYTES)
                    )
                    else -> {
                        if (lower.endsWith("nextdns.io")) {
                            return listOf(
                                InetAddress.getByAddress(hostname, NEXTDNS_PRIMARY_BYTES),
                                InetAddress.getByAddress(hostname, NEXTDNS_SECONDARY_BYTES)
                            )
                        }
                        val literalBytes = parseIpv4Bytes(lower)
                        if (literalBytes != null) {
                            return listOf(InetAddress.getByAddress(hostname, literalBytes))
                        }
                        // Prefer user's custom secondary DNS or system DNS first when bootstrapping a Custom DoH host
                        val customSecondaryBytes = activeCustomDns?.secondaryServer?.let { parseIpv4Bytes(it) }
                        if (customSecondaryBytes != null) {
                            val viaSecondary = queryDnsUdpWire(hostname, customSecondaryBytes)?.addresses
                                ?: queryDnsTcpWire(hostname, customSecondaryBytes)?.addresses
                            if (!viaSecondary.isNullOrEmpty()) {
                                return viaSecondary
                            }
                        }
                        val viaSystem = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
                        if (!viaSystem.isNullOrEmpty()) {
                            return viaSystem
                        }
                        val resolvedViaGoogle = queryGoogleDoh(hostname)?.first
                        if (!resolvedViaGoogle.isNullOrEmpty()) {
                            return resolvedViaGoogle
                        }
                        val resolvedViaWarp = queryCloudflareDoh(hostname)?.first
                        if (!resolvedViaWarp.isNullOrEmpty()) {
                            return resolvedViaWarp
                        }
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            }
        }
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(staticDns)
            .connectionPool(ConnectionPool(8, 3, TimeUnit.MINUTES))
            .connectTimeout(3500, TimeUnit.MILLISECONDS)
            .readTimeout(4500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Network interceptor that synchronizes Set-Cookie headers from OkHttp responses into Android WebView's CookieManager
    private val webViewCookieSyncInterceptor = Interceptor { chain ->
        val request = chain.request()
        val reqUrlStr = request.url.toString()
        val cm = runCatching { CookieManager.getInstance() }.getOrNull()
        val existingCookieHeader = request.header("Cookie")
        val webViewCookie = runCatching { cm?.getCookie(reqUrlStr) }.getOrNull()
        val mergedRequest = if (!webViewCookie.isNullOrBlank()) {
            val combinedCookie = if (existingCookieHeader.isNullOrBlank()) {
                webViewCookie
            } else if (existingCookieHeader.contains(webViewCookie)) {
                existingCookieHeader
            } else {
                "$existingCookieHeader; $webViewCookie"
            }
            request.newBuilder().header("Cookie", combinedCookie).build()
        } else {
            request
        }

        val response = chain.proceed(mergedRequest)
        if (cm != null) {
            val respUrlStr = response.request.url.toString()
            val setCookies = response.headers("Set-Cookie")
            if (setCookies.isNotEmpty()) {
                for (cookieLine in setCookies) {
                    if (cookieLine.isNotBlank()) {
                        runCatching { cm.setCookie(respUrlStr, cookieLine) }
                    }
                }
                runCatching { cm.flush() }
            }
        }
        response
    }

    private val tunnelConnectionPool = ConnectionPool(16, 3, TimeUnit.MINUTES)

    // Subresource tunnel client using this CloudflareWarpDns resolver (follows redirects automatically)
    private val browserTunnelClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(this)
            .proxySelector(com.example.data.browser.BrowserProxyManager.okHttpProxySelector)
            .proxyAuthenticator(com.example.data.browser.BrowserProxyManager.okHttpProxyAuthenticator)
            .connectionPool(tunnelConnectionPool)
            .addNetworkInterceptor(webViewCookieSyncInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Main-frame tunnel client with followRedirects(false) so top-level 301/302 redirects update WebView's URL bar and window.location
    private val browserMainFrameTunnelClient: OkHttpClient by lazy {
        browserTunnelClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun setEnabled(enabled: Boolean) {
        setDnsMode(
            if (enabled) DnsProviderMode.CLOUDFLARE_WARP else DnsProviderMode.SYSTEM_DEFAULT,
            activeCustomDns
        )
    }

    fun setDnsMode(mode: DnsProviderMode, customDns: CustomDnsServerEntry? = activeCustomDns) {
        dnsMode = mode
        if (customDns != null) {
            activeCustomDns = customDns
        }
        // isEnabled specifically indicates Cloudflare WARP (1.1.1.1) state; use isAnyCustomDnsActive for all non-system DNS modes
        isEnabled = (mode == DnsProviderMode.CLOUDFLARE_WARP)
        resetStateOnModeSwitch()
    }

    private fun resetStateOnModeSwitch() {
        dnsCache.clear()
        sinkholedDomains.clear()
        verifiedSafeDomains.clear()
        systemDnsFailedHosts.clear()
        inFlightPrewarmHosts.clear()
        udpConsecutiveFailures.set(0)
        udpBypassUntilElapsedMs = 0L
        runCatching { tunnelConnectionPool.evictAll() }
        publishTelemetry()
    }

    fun clearCache() {
        dnsCache.clear()
        sinkholedDomains.clear()
        verifiedSafeDomains.clear()
        systemDnsFailedHosts.clear()
        inFlightPrewarmHosts.clear()
        runCatching { tunnelConnectionPool.evictAll() }
        publishTelemetry()
    }

    fun markHostRequiresTunnel(hostname: String?) {
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return
        if (normalized.isNotBlank() && normalized != "localhost" && !isIpLiteral(normalized)) {
            forcedTunnelHosts.add(normalized)
            systemDnsFailedHosts[normalized] = SystemClock.elapsedRealtime() + 300_000L
        }
    }

    /**
     * Fast-path sinkhole check used by [com.example.data.browser.LocalBrowserProxyServer]
     * before dialing any TCP socket for CONNECT or plain HTTP requests.
     */
    fun isHostSinkholedForProxy(hostname: String?): Boolean {
        val mode = dnsMode
        if (mode != DnsProviderMode.ADBLOCKER_DNS && mode != DnsProviderMode.CUSTOM_DNS) return false
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return false
        if (isMediaStreamingOrEssentialHost(normalized) || isAuthOrSessionCriticalHost(normalized)) {
            return false
        }
        val cachedSinkhole = dnsCache[normalized]?.let {
            it.isSinkholed && SystemClock.elapsedRealtime() < it.expiresAtElapsedMs
        } == true
        val blocked = sinkholedDomains.contains(normalized) ||
            cachedSinkhole ||
            (mode == DnsProviderMode.ADBLOCKER_DNS && isKnownAdOrTrackerDomain(normalized))
        if (blocked) {
            sinkholedDomains.add(normalized)
            blockedAdCounter.incrementAndGet()
            publishTelemetry()
        }
        return blocked
    }

    /**
     * Checks whether a top-level navigation or popup window URL should be blocked when
     * [DnsProviderMode.ADBLOCKER_DNS] is active (prevents popup/redirect ads).
     */
    fun shouldBlockBrowserAdNavigation(targetUrl: String?, pageHost: String? = null): Boolean {
        if (dnsMode != DnsProviderMode.ADBLOCKER_DNS) return false
        val raw = targetUrl?.trim() ?: return false
        if (!raw.startsWith("http://", ignoreCase = true) && !raw.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val host = extractHostFromUrl(raw) ?: return false
        if (isMediaStreamingOrEssentialHost(host) || isAuthOrSessionCriticalHost(host)) {
            return false
        }
        if (isKnownAdOrTrackerDomain(host) || sinkholedDomains.contains(host)) {
            blockedAdCounter.incrementAndGet()
            publishTelemetry()
            return true
        }
        return false
    }

    /**
     * Checks whether a browser request should be blocked at the DNS/network layer when
     * [DnsProviderMode.ADBLOCKER_DNS] (or a sinkholing [DnsProviderMode.CUSTOM_DNS]) is active.
     *
     * Because `WebViewClient.shouldInterceptRequest` runs on a background worker thread,
     * if [hostname] is not yet in cache, this synchronously queries the active DNS resolver
     * so AdGuard (`94.140.14.14`) or Custom DNS sinkholing blocks ad domains on the very first request.
     */
    fun shouldBlockBrowserAdRequest(
        hostname: String?,
        requestUrl: String? = null,
        isMainFrame: Boolean = false
    ): Boolean {
        val mode = dnsMode
        if (mode != DnsProviderMode.ADBLOCKER_DNS && mode != DnsProviderMode.CUSTOM_DNS) return false
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return false
        if (isMediaStreamingOrEssentialHost(normalized) || isAuthOrSessionCriticalHost(normalized)) {
            return false
        }

        // Fast path 1: known ad/tracker domain or URL pattern when AdBlocker DNS is enabled
        if (mode == DnsProviderMode.ADBLOCKER_DNS) {
            if (isKnownAdOrTrackerDomain(normalized)) {
                sinkholedDomains.add(normalized)
                blockedAdCounter.incrementAndGet()
                publishTelemetry()
                return true
            }
            if (!isMainFrame && !requestUrl.isNullOrBlank() && isKnownAdOrTrackerUrlPath(requestUrl)) {
                blockedAdCounter.incrementAndGet()
                publishTelemetry()
                return true
            }
        }

        if (isMainFrame) return false

        // Fast path 2: already sinkholed or verified safe by upstream DNS
        if (sinkholedDomains.contains(normalized)) {
            blockedAdCounter.incrementAndGet()
            publishTelemetry()
            return true
        }
        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalized]
        if (cached != null && now < cached.expiresAtElapsedMs) {
            if (cached.isSinkholed) {
                blockedAdCounter.incrementAndGet()
                publishTelemetry()
                return true
            }
            return false
        }
        if (verifiedSafeDomains.contains(normalized)) {
            return false
        }

        // Synchronous upstream DNS check on WebView's background interception thread
        return try {
            val resolved = lookup(normalized)
            if (resolved.isNotEmpty()) {
                verifiedSafeDomains.add(normalized)
            }
            false
        } catch (_: UnknownHostException) {
            if (sinkholedDomains.contains(normalized)) {
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun buildAdBlockedWebResourceResponse(requestUrl: String? = null): WebResourceResponse {
        val pathLower = requestUrl?.substringBefore('?')?.substringBefore('#')?.lowercase(Locale.US).orEmpty()
        val (mimeType, bodyBytes) = when {
            pathLower.endsWith(".js") || pathLower.endsWith(".mjs") ->
                "application/javascript" to "/* Blocked by AdBlocker DNS */".toByteArray(Charsets.UTF_8)
            pathLower.endsWith(".css") ->
                "text/css" to "/* Blocked by AdBlocker DNS */".toByteArray(Charsets.UTF_8)
            pathLower.endsWith(".gif") || pathLower.endsWith(".png") ||
                pathLower.endsWith(".jpg") || pathLower.endsWith(".jpeg") ||
                pathLower.endsWith(".webp") || pathLower.endsWith(".ico") ->
                "image/gif" to TRANSPARENT_1X1_GIF_BYTES
            pathLower.endsWith(".json") ->
                "application/json" to "{}".toByteArray(Charsets.UTF_8)
            pathLower.endsWith(".html") || pathLower.endsWith(".htm") ->
                "text/html" to "<!DOCTYPE html><html><head></head><body></body></html>".toByteArray(Charsets.UTF_8)
            else ->
                "text/plain" to ByteArray(0)
        }
        return WebResourceResponse(
            mimeType,
            if (mimeType.startsWith("image/")) null else "UTF-8",
            200,
            "OK",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, HEAD, POST, OPTIONS",
                "Cache-Control" to "no-store"
            ),
            ByteArrayInputStream(bodyBytes)
        )
    }

    /**
     * Pre-resolves [hostname] via the active DNS mode in background.
     */
    fun prewarmHostAsync(hostname: String) {
        if (!isAnyCustomDnsActive) return
        val normalized = hostname.trim().lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return
        if (dnsMode == DnsProviderMode.ADBLOCKER_DNS && isKnownAdOrTrackerDomain(normalized)) {
            sinkholedDomains.add(normalized)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val cached = dnsCache[normalized]
        if (cached != null && now < cached.expiresAtElapsedMs) {
            cacheHitCounter.incrementAndGet()
            publishTelemetry()
            return
        }
        if (!inFlightPrewarmHosts.add(normalized)) return
        try {
            prewarmExecutor.execute {
                try {
                    lookup(normalized)
                } catch (_: Throwable) {
                } finally {
                    inFlightPrewarmHosts.remove(normalized)
                }
            }
        } catch (_: Throwable) {
            inFlightPrewarmHosts.remove(normalized)
        }
    }

    /**
     * Determines whether a browser WebView request should be tunneled through [browserTunnelClient]
     * using our active DNS resolver (Cloudflare WARP 1.1.1.1, AdBlocker 94.140.14.14,
     * Unfiltered AdBlocker 94.140.14.140, or Custom DNS) so WebView actually uses the selected DNS
     * instead of leaking to Android's System DNS.
     */
    fun shouldTunnelBrowserRequest(
        hostname: String?,
        method: String?,
        requestHeaders: Map<String, String>?
    ): Boolean {
        if (!isAnyCustomDnsActive) return false
        val m = method?.uppercase(Locale.US) ?: "GET"
        if (m != "GET" && m != "HEAD" && m != "OPTIONS") return false
        val normalized = hostname?.trim()?.lowercase(Locale.US)?.trimEnd('.') ?: return false
        if (normalized.isBlank() || normalized == "localhost" || isIpLiteral(normalized)) return false
        if (normalized == "dragon-ext.local" || normalized.endsWith(".dragon-ext.local")) return false
        if (isAuthOrSessionCriticalHost(normalized)) return false
        if ((dnsMode == DnsProviderMode.ADBLOCKER_DNS || dnsMode == DnsProviderMode.CUSTOM_DNS) &&
            (sinkholedDomains.contains(normalized) ||
                (dnsMode == DnsProviderMode.ADBLOCKER_DNS && isKnownAdOrTrackerDomain(normalized)))
        ) {
            return false
        }

        // Do not intercept WebSocket upgrades or EventSource streams
        val upgrade = requestHeaders?.entries?.firstOrNull { it.key.equals("Upgrade", true) }?.value
        if (upgrade.equals("websocket", ignoreCase = true)) return false
        val accept = requestHeaders?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
        if (accept?.contains("text/event-stream", ignoreCase = true) == true) return false

        return true
    }

    /**
     * Executes an HTTP/HTTPS GET/HEAD/OPTIONS request through [browserTunnelClient] backed by the active
     * DNS provider, preserving cookies, main-frame redirects, file download attachments,
     * byte-range `206 Partial Content` streaming headers, and CORS headers.
     */
    fun executeBrowserTunnelRequest(
        requestUrl: String,
        method: String?,
        requestHeaders: Map<String, String>?,
        isMainFrame: Boolean,
        defaultUserAgent: String,
        onMainFrameDownloadDetected: ((MainFrameDownloadIntercept) -> Unit)? = null
    ): WebResourceResponse? {
        val normMethod = method?.uppercase(Locale.US) ?: "GET"
        if (normMethod == "OPTIONS") {
            val reqOrigin = requestHeaders?.entries
                ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
                ?.value?.takeIf { it.isNotBlank() } ?: "*"
            val reqAllowHeaders = requestHeaders?.entries
                ?.firstOrNull { it.key.equals("Access-Control-Request-Headers", ignoreCase = true) }
                ?.value?.takeIf { it.isNotBlank() } ?: "*"
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                200,
                "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to reqOrigin,
                    "Access-Control-Allow-Methods" to "GET, HEAD, POST, PUT, DELETE, OPTIONS",
                    "Access-Control-Allow-Headers" to reqAllowHeaders,
                    "Access-Control-Allow-Credentials" to "true",
                    "Access-Control-Max-Age" to "86400"
                ),
                ByteArrayInputStream(ByteArray(0))
            )
        }

        return try {
            val reqBuilder = Request.Builder()
                .url(requestUrl)
                .method(normMethod, null)

            var hasUserAgent = false
            var hasAccept = false
            var hasAcceptLanguage = false
            requestHeaders?.forEach { (k, v) ->
                if (!k.equals("Accept-Encoding", ignoreCase = true) &&
                    !k.equals("Host", ignoreCase = true) &&
                    !k.equals("Connection", ignoreCase = true) &&
                    !k.equals("If-None-Match", ignoreCase = true) &&
                    !k.equals("If-Modified-Since", ignoreCase = true)
                ) {
                    reqBuilder.header(k, v)
                    if (k.equals("User-Agent", ignoreCase = true)) hasUserAgent = true
                    if (k.equals("Accept", ignoreCase = true)) hasAccept = true
                    if (k.equals("Accept-Language", ignoreCase = true)) hasAcceptLanguage = true
                }
            }
            val effectiveUserAgent = if (!hasUserAgent && defaultUserAgent.isNotBlank()) {
                reqBuilder.header("User-Agent", defaultUserAgent)
                defaultUserAgent
            } else {
                requestHeaders?.entries?.firstOrNull { it.key.equals("User-Agent", true) }?.value ?: defaultUserAgent
            }
            if (isMainFrame && !hasAccept) {
                reqBuilder.header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            }
            if (!hasAcceptLanguage) {
                reqBuilder.header("Accept-Language", "en-US,en;q=0.9")
            }

            val clientToUse = if (isMainFrame) browserMainFrameTunnelClient else browserTunnelClient
            val response = clientToUse.newCall(reqBuilder.build()).execute()
            val statusCode = response.code

            // 1. Handle Main-Frame HTTP Redirects (301, 302, 303, 307, 308) so WebView updates its URL & window.location
            if (isMainFrame && statusCode in listOf(301, 302, 303, 307, 308)) {
                val locationHeader = response.header("Location")?.trim()
                val resolvedRedirectUrl = if (!locationHeader.isNullOrBlank()) {
                    response.request.url.resolve(locationHeader)?.toString() ?: locationHeader
                } else {
                    null
                }
                runCatching { response.close() }
                if (!resolvedRedirectUrl.isNullOrBlank()) {
                    val safeJsUrl = JSONObject.quote(resolvedRedirectUrl)
                    val safeHtmlUrl = resolvedRedirectUrl
                        .replace("&", "&amp;")
                        .replace("\"", "&quot;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                    val redirectHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                        <meta charset="utf-8">
                        <meta http-equiv="refresh" content="0;url=$safeHtmlUrl">
                        <script>window.location.replace($safeJsUrl);</script>
                        </head>
                        <body></body>
                        </html>
                    """.trimIndent()
                    return WebResourceResponse(
                        "text/html",
                        "UTF-8",
                        200,
                        "OK",
                        mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(redirectHtml.toByteArray(Charsets.UTF_8))
                    )
                }
            }

            val finalUrl = response.request.url.toString()
            val contentDisposition = response.header("Content-Disposition")
            val rawContentType = response.header("Content-Type") ?: guessMimeTypeFromUrl(finalUrl)
            val mimeType = rawContentType.substringBefore(';').trim().ifBlank { "text/html" }

            // 2. If a Main-Frame navigation returns a downloadable file attachment, hand off to DragonDownloadManager
            if (isMainFrame && onMainFrameDownloadDetected != null &&
                isDownloadableAttachmentResponse(finalUrl, mimeType, contentDisposition)
            ) {
                val contentLength = response.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                runCatching { response.close() }
                onMainFrameDownloadDetected(
                    MainFrameDownloadIntercept(
                        url = finalUrl,
                        userAgent = effectiveUserAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        contentLength = contentLength
                    )
                )
                return WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    204,
                    "No Content",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(ByteArray(0))
                )
            }

            val charset = rawContentType
                .substringAfter("charset=", "")
                .substringBefore(';')
                .trim()
                .removeSurrounding("\"")
                .ifBlank {
                    if (mimeType.startsWith("text/") ||
                        mimeType.contains("json") ||
                        mimeType.contains("javascript") ||
                        mimeType.contains("xml")
                    ) {
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
                    respHeaders["Access-Control-Allow-Origin"] =
                        if (allowsCredentials && reqOrigin != null) reqOrigin else (reqOrigin ?: "*")
                }
                if (!hasRespHeader("Access-Control-Allow-Methods")) {
                    respHeaders["Access-Control-Allow-Methods"] = "GET, HEAD, POST, OPTIONS"
                }
                if (!hasRespHeader("Access-Control-Allow-Headers")) {
                    respHeaders["Access-Control-Allow-Headers"] = "*"
                }
                if (!hasRespHeader("Access-Control-Expose-Headers")) {
                    respHeaders["Access-Control-Expose-Headers"] = "Content-Length, Content-Range, Accept-Ranges"
                }
            }

            val safeStatusCode = statusCode.coerceIn(100, 599)
            val reasonPhrase = response.message.ifBlank {
                when (safeStatusCode) {
                    200 -> "OK"
                    206 -> "Partial Content"
                    204 -> "No Content"
                    301, 302, 307, 308 -> "Redirect"
                    304 -> "Not Modified"
                    403 -> "Forbidden"
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
                safeStatusCode,
                reasonPhrase,
                respHeaders,
                bodyStream
            )
        } catch (_: UnknownHostException) {
            val reqHost = extractHostFromUrl(requestUrl)
            if ((dnsMode == DnsProviderMode.ADBLOCKER_DNS || dnsMode == DnsProviderMode.CUSTOM_DNS) &&
                (!isMainFrame || (reqHost != null && sinkholedDomains.contains(reqHost)))
            ) {
                buildAdBlockedWebResourceResponse(requestUrl)
            } else {
                null
            }
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
        return try {
            val normalizedHost = hostname.trim().lowercase(Locale.US).trimEnd('.')
            if (!isAnyCustomDnsActive || normalizedHost.isBlank() || normalizedHost == "localhost" || isIpLiteral(normalizedHost)) {
                return Dns.SYSTEM.lookup(hostname)
            }

            // 1. If AdBlocker DNS is active, sinkhole known ad/tracker domains in 0ms
            if (dnsMode == DnsProviderMode.ADBLOCKER_DNS &&
                !isMediaStreamingOrEssentialHost(normalizedHost) &&
                !isAuthOrSessionCriticalHost(normalizedHost) &&
                isKnownAdOrTrackerDomain(normalizedHost)
            ) {
                sinkholedDomains.add(normalizedHost)
                blockedAdCounter.incrementAndGet()
                publishTelemetry()
                throw UnknownHostException("Blocked by AdBlocker DNS: $hostname")
            }

            val now = SystemClock.elapsedRealtime()
            val cached = dnsCache[normalizedHost]
            if (cached != null && now < cached.expiresAtElapsedMs) {
                if (cached.isSinkholed && (dnsMode == DnsProviderMode.ADBLOCKER_DNS || dnsMode == DnsProviderMode.CUSTOM_DNS)) {
                    blockedAdCounter.incrementAndGet()
                    publishTelemetry()
                    throw UnknownHostException("Blocked by DNS sinkhole: $hostname")
                }
                if (cached.addresses.isNotEmpty()) {
                    cacheHitCounter.incrementAndGet()
                    publishTelemetry()
                    return cached.addresses
                }
            }

            val startMs = SystemClock.elapsedRealtime()
            val resolvedAddresses = LinkedHashSet<InetAddress>()
            var ttlSeconds = DEFAULT_TTL_SECONDS
            var wasSinkholedByUpstream = false
            var activeProviderResponded = false
            val allowUdpFallback = SystemClock.elapsedRealtime() >= udpBypassUntilElapsedMs

            when (dnsMode) {
                DnsProviderMode.ADBLOCKER_DNS -> {
                    // Always query encrypted AdGuard DoH first so ISP UDP/53 interception cannot strip ad blocking
                    val wireDoh = queryRfc8484DohWire(hostname, "https://$ADGUARD_FILTERED_DOH_HOST/dns-query")
                        ?: queryRfc8484DohWire(hostname, "https://$ADGUARD_FILTERED_PRIMARY_IPV4/dns-query")
                    if (wireDoh != null) {
                        activeProviderResponded = true
                        if (wireDoh.isSinkholed) {
                            wasSinkholedByUpstream = true
                        } else if (wireDoh.addresses.isNotEmpty()) {
                            resolvedAddresses.addAll(wireDoh.addresses)
                            ttlSeconds = wireDoh.ttlSeconds
                        }
                    }
                    if (resolvedAddresses.isEmpty() && !wasSinkholedByUpstream && !activeProviderResponded) {
                        if (allowUdpFallback) {
                            val udpResult = queryDnsUdpWire(hostname, ADGUARD_FILTERED_PRIMARY_BYTES)
                                ?: queryDnsUdpWire(hostname, ADGUARD_FILTERED_SECONDARY_BYTES)
                            if (udpResult != null) {
                                activeProviderResponded = true
                                udpConsecutiveFailures.set(0)
                                if (udpResult.isSinkholed) {
                                    wasSinkholedByUpstream = true
                                } else if (!udpResult.isTruncated && udpResult.addresses.isNotEmpty()) {
                                    resolvedAddresses.addAll(udpResult.addresses)
                                    ttlSeconds = udpResult.ttlSeconds
                                }
                            } else {
                                recordUdpFailure()
                            }
                        }
                        if (resolvedAddresses.isEmpty() && !wasSinkholedByUpstream) {
                            val tcpResult = queryDnsTcpWire(hostname, ADGUARD_FILTERED_PRIMARY_BYTES)
                                ?: queryDnsTcpWire(hostname, ADGUARD_FILTERED_SECONDARY_BYTES)
                            if (tcpResult != null) {
                                activeProviderResponded = true
                                if (tcpResult.isSinkholed) {
                                    wasSinkholedByUpstream = true
                                } else if (tcpResult.addresses.isNotEmpty()) {
                                    resolvedAddresses.addAll(tcpResult.addresses)
                                    ttlSeconds = tcpResult.ttlSeconds
                                }
                            }
                        }
                    }
                }

                DnsProviderMode.UNFILTERED_ADBLOCKER_DNS -> {
                    // Always query encrypted AdGuard Unfiltered DoH first so ISP UDP/53 censorship cannot hijack DNS
                    val wireDoh = queryRfc8484DohWire(hostname, "https://$ADGUARD_UNFILTERED_DOH_HOST/dns-query")
                        ?: queryRfc8484DohWire(hostname, "https://$ADGUARD_UNFILTERED_PRIMARY_IPV4/dns-query")
                    if (wireDoh != null) {
                        activeProviderResponded = true
                        if (wireDoh.addresses.isNotEmpty()) {
                            resolvedAddresses.addAll(wireDoh.addresses)
                            ttlSeconds = wireDoh.ttlSeconds
                        }
                    }
                    if (resolvedAddresses.isEmpty() && !activeProviderResponded) {
                        if (allowUdpFallback) {
                            val udpResult = queryDnsUdpWire(hostname, ADGUARD_UNFILTERED_PRIMARY_BYTES)
                                ?: queryDnsUdpWire(hostname, ADGUARD_UNFILTERED_SECONDARY_BYTES)
                            if (udpResult != null && !udpResult.isTruncated) {
                                activeProviderResponded = true
                                udpConsecutiveFailures.set(0)
                                if (udpResult.addresses.isNotEmpty()) {
                                    resolvedAddresses.addAll(udpResult.addresses)
                                    ttlSeconds = udpResult.ttlSeconds
                                }
                            } else if (udpResult == null) {
                                recordUdpFailure()
                            }
                        }
                        if (resolvedAddresses.isEmpty()) {
                            val tcpResult = queryDnsTcpWire(hostname, ADGUARD_UNFILTERED_PRIMARY_BYTES)
                                ?: queryDnsTcpWire(hostname, ADGUARD_UNFILTERED_SECONDARY_BYTES)
                            if (tcpResult != null) {
                                activeProviderResponded = true
                                if (tcpResult.addresses.isNotEmpty()) {
                                    resolvedAddresses.addAll(tcpResult.addresses)
                                    ttlSeconds = tcpResult.ttlSeconds
                                }
                            }
                        }
                    }
                }

                DnsProviderMode.CUSTOM_DNS -> {
                    val custom = activeCustomDns
                    if (custom != null) {
                        val pBytes = parseIpv4Bytes(custom.primaryServer)
                        val sBytes = parseIpv4Bytes(custom.secondaryServer)
                        if (pBytes != null) {
                            // 1. If the IPv4 maps to a known encrypted DoH endpoint (e.g. 9.9.9.9, 8.8.8.8, 1.1.1.1, 94.140.14.14),
                            // try encrypted DoH first so ISP UDP/53 interception cannot interfere
                            val knownDohUrl = mapKnownIpv4ToDohEndpoint(custom.primaryServer.trim())
                                ?: mapKnownIpv4ToDohEndpoint(custom.secondaryServer.trim())
                            if (knownDohUrl != null) {
                                val wireDoh = queryRfc8484DohWire(hostname, knownDohUrl)
                                if (wireDoh != null) {
                                    activeProviderResponded = true
                                    if (wireDoh.isSinkholed) {
                                        wasSinkholedByUpstream = true
                                    } else if (wireDoh.addresses.isNotEmpty()) {
                                        resolvedAddresses.addAll(wireDoh.addresses)
                                        ttlSeconds = wireDoh.ttlSeconds
                                    }
                                }
                            }
                            // 2. Query custom IPv4 via UDP/53
                            if (resolvedAddresses.isEmpty() && !wasSinkholedByUpstream && allowUdpFallback) {
                                val udpRes = queryDnsUdpWire(hostname, pBytes)
                                    ?: (sBytes?.let { queryDnsUdpWire(hostname, it) })
                                if (udpRes != null) {
                                    activeProviderResponded = true
                                    udpConsecutiveFailures.set(0)
                                    if (udpRes.isSinkholed) {
                                        wasSinkholedByUpstream = true
                                    } else if (!udpRes.isTruncated && udpRes.addresses.isNotEmpty()) {
                                        resolvedAddresses.addAll(udpRes.addresses)
                                        ttlSeconds = udpRes.ttlSeconds
                                    }
                                } else {
                                    recordUdpFailure()
                                }
                            }
                            // 3. Query custom IPv4 via TCP/53 (works when carrier blocks UDP/53 or truncates packets)
                            if (resolvedAddresses.isEmpty() && !wasSinkholedByUpstream) {
                                val tcpRes = queryDnsTcpWire(hostname, pBytes)
                                    ?: (sBytes?.let { queryDnsTcpWire(hostname, it) })
                                if (tcpRes != null) {
                                    activeProviderResponded = true
                                    if (tcpRes.isSinkholed) {
                                        wasSinkholedByUpstream = true
                                    } else if (tcpRes.addresses.isNotEmpty()) {
                                        resolvedAddresses.addAll(tcpRes.addresses)
                                        ttlSeconds = tcpRes.ttlSeconds
                                    }
                                }
                            }
                            // 4. Try direct DoH on the custom IPv4
                            if (resolvedAddresses.isEmpty() && !wasSinkholedByUpstream && knownDohUrl == null) {
                                val wireDoh = queryRfc8484DohWire(hostname, "https://${custom.primaryServer.trim()}/dns-query")
                                if (wireDoh != null) {
                                    activeProviderResponded = true
                                    if (wireDoh.isSinkholed) {
                                        wasSinkholedByUpstream = true
                                    } else if (wireDoh.addresses.isNotEmpty()) {
                                        resolvedAddresses.addAll(wireDoh.addresses)
                                        ttlSeconds = wireDoh.ttlSeconds
                                    }
                                }
                            }
                        } else {
                            val dohUrl = normalizeCustomDohEndpoint(custom.primaryServer)
                            val wireDoh = queryRfc8484DohWire(hostname, dohUrl)
                            if (wireDoh != null) {
                                activeProviderResponded = true
                                if (wireDoh.isSinkholed) {
                                    wasSinkholedByUpstream = true
                                } else if (wireDoh.addresses.isNotEmpty()) {
                                    resolvedAddresses.addAll(wireDoh.addresses)
                                    ttlSeconds = wireDoh.ttlSeconds
                                }
                            }
                            if (resolvedAddresses.isEmpty() && !wasSinkholedByUpstream) {
                                val jsonDoh = queryGenericDohJson(hostname, dohUrl)
                                if (jsonDoh != null && jsonDoh.first.isNotEmpty()) {
                                    activeProviderResponded = true
                                    resolvedAddresses.addAll(jsonDoh.first)
                                    ttlSeconds = jsonDoh.second
                                }
                            }
                            if (resolvedAddresses.isEmpty() && !wasSinkholedByUpstream && sBytes != null) {
                                val secondaryUdp = queryDnsUdpWire(hostname, sBytes) ?: queryDnsTcpWire(hostname, sBytes)
                                if (secondaryUdp != null) {
                                    activeProviderResponded = true
                                    if (secondaryUdp.isSinkholed) {
                                        wasSinkholedByUpstream = true
                                    } else if (secondaryUdp.addresses.isNotEmpty()) {
                                        resolvedAddresses.addAll(secondaryUdp.addresses)
                                        ttlSeconds = secondaryUdp.ttlSeconds
                                    }
                                }
                            }
                        }
                    }
                }

                DnsProviderMode.CLOUDFLARE_WARP -> {
                    // Query encrypted Cloudflare DoH first so ISP UDP/53 hijacking cannot spoof responses
                    val dohResult = queryCloudflareDoh(hostname)
                    if (dohResult != null && dohResult.first.isNotEmpty()) {
                        activeProviderResponded = true
                        resolvedAddresses.addAll(dohResult.first)
                        ttlSeconds = dohResult.second
                    } else {
                        val wireDoh = queryCloudflareDohWire(hostname, PRIMARY_IPV4)
                            ?: queryCloudflareDohWire(hostname, SECONDARY_IPV4)
                        if (wireDoh != null && wireDoh.addresses.isNotEmpty()) {
                            activeProviderResponded = true
                            resolvedAddresses.addAll(wireDoh.addresses)
                            ttlSeconds = wireDoh.ttlSeconds
                        }
                    }
                    if (resolvedAddresses.isEmpty()) {
                        if (allowUdpFallback) {
                            val udpResult = queryDnsUdpWire(hostname, PRIMARY_IPV4_BYTES)
                                ?: queryDnsUdpWire(hostname, SECONDARY_IPV4_BYTES)
                            if (udpResult != null && !udpResult.isTruncated && udpResult.addresses.isNotEmpty()) {
                                activeProviderResponded = true
                                udpConsecutiveFailures.set(0)
                                resolvedAddresses.addAll(udpResult.addresses)
                                ttlSeconds = udpResult.ttlSeconds
                            } else if (udpResult == null) {
                                recordUdpFailure()
                            }
                        }
                        if (resolvedAddresses.isEmpty()) {
                            val tcpResult = queryDnsTcpWire(hostname, PRIMARY_IPV4_BYTES)
                                ?: queryDnsTcpWire(hostname, SECONDARY_IPV4_BYTES)
                            if (tcpResult != null && tcpResult.addresses.isNotEmpty()) {
                                activeProviderResponded = true
                                resolvedAddresses.addAll(tcpResult.addresses)
                                ttlSeconds = tcpResult.ttlSeconds
                            }
                        }
                    }
                }

                DnsProviderMode.SYSTEM_DEFAULT -> Unit
            }

            // Honor upstream sinkholing for both ADBLOCKER_DNS and CUSTOM_DNS (never fall back to 1.1.1.1 for blocked domains!)
            if (wasSinkholedByUpstream &&
                (dnsMode == DnsProviderMode.ADBLOCKER_DNS || dnsMode == DnsProviderMode.CUSTOM_DNS) &&
                !isMediaStreamingOrEssentialHost(normalizedHost) &&
                !isAuthOrSessionCriticalHost(normalizedHost)
            ) {
                sinkholedDomains.add(normalizedHost)
                dnsCache[normalizedHost] = CachedDnsEntry(
                    addresses = emptyList(),
                    expiresAtElapsedMs = SystemClock.elapsedRealtime() + 180_000L,
                    isSinkholed = true
                )
                blockedAdCounter.incrementAndGet()
                publishTelemetry()
                throw UnknownHostException("Sinkholed by DNS: $hostname")
            }

            // Resilient DoH secondary fallback ONLY when Cloudflare WARP mode itself is active;
            // never silently route AdBlocker DNS, Unfiltered DNS, or Custom DNS through Cloudflare WARP 1.1.1.1.
            if (dnsMode == DnsProviderMode.CLOUDFLARE_WARP &&
                resolvedAddresses.isEmpty() &&
                !wasSinkholedByUpstream &&
                !activeProviderResponded
            ) {
                val dohFallback = queryCloudflareDoh(hostname)
                    ?: queryGoogleDoh(hostname)
                if (dohFallback != null && dohFallback.first.isNotEmpty()) {
                    resolvedAddresses.addAll(dohFallback.first)
                    ttlSeconds = dohFallback.second
                }
            }

            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            lastLatencyMsAtomic.set(elapsed)

            // Only fall back to System DNS if all DoH/UDP/TCP resolvers were unreachable and domain was not sinkholed
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

            val sorted = resolvedAddresses.sortedBy { addr ->
                if (addr is Inet4Address) 0 else 1
            }

            resolvedCounter.incrementAndGet()
            verifiedSafeDomains.add(normalizedHost)
            val boundedTtlMs = (ttlSeconds.coerceIn(60L, 600L)) * 1000L
            dnsCache[normalizedHost] = CachedDnsEntry(
                addresses = sorted,
                expiresAtElapsedMs = SystemClock.elapsedRealtime() + boundedTtlMs
            )
            publishTelemetry()
            sorted
        } catch (e: Throwable) {
            if (e is UnknownHostException && (dnsMode == DnsProviderMode.ADBLOCKER_DNS || dnsMode == DnsProviderMode.CUSTOM_DNS) && sinkholedDomains.contains(hostname.trim().lowercase(Locale.US).trimEnd('.'))) {
                throw e
            }
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull() ?: throw e
        }
    }

    private fun recordUdpFailure() {
        val fails = udpConsecutiveFailures.incrementAndGet()
        if (fails >= 2) {
            udpBypassUntilElapsedMs = SystemClock.elapsedRealtime() + 90_000L
        }
    }

    private fun publishTelemetry() {
        _telemetryFlow.value = WarpDnsTelemetry(
            isEnabled = isEnabled,
            dnsMode = dnsMode,
            activeCustomDns = activeCustomDns,
            resolvedHostCount = resolvedCounter.get(),
            cacheHitCount = cacheHitCounter.get(),
            blockedAdHostCount = blockedAdCounter.get(),
            lastLatencyMs = lastLatencyMsAtomic.get(),
            activeResolverLabel = buildResolverLabel(dnsMode, activeCustomDns)
        )
    }

    private fun buildResolverLabel(mode: DnsProviderMode, custom: CustomDnsServerEntry?): String {
        return when (mode) {
            DnsProviderMode.SYSTEM_DEFAULT -> "System Default DNS"
            DnsProviderMode.CLOUDFLARE_WARP -> "1.1.1.1 • Cloudflare WARP (DoH + UDP)"
            DnsProviderMode.ADBLOCKER_DNS -> "94.140.14.14 • AdBlocker DNS (AdGuard DoH)"
            DnsProviderMode.UNFILTERED_ADBLOCKER_DNS -> "94.140.14.140 • Unfiltered AdBlocker DNS"
            DnsProviderMode.CUSTOM_DNS -> {
                if (custom != null) "${custom.name} (${custom.primaryServer})"
                else "Custom DNS"
            }
        }
    }

    internal fun queryCloudflareDoh(hostname: String): Pair<List<InetAddress>, Long>? {
        return queryGenericDohJson(hostname, "https://cloudflare-dns.com/dns-query")
    }

    internal fun queryGoogleDoh(hostname: String): Pair<List<InetAddress>, Long>? {
        return queryGenericDohJson(hostname, "https://dns.google/resolve")
    }

    internal fun queryGenericDohJson(hostname: String, endpointUrl: String): Pair<List<InetAddress>, Long>? {
        val encoded = runCatching { URLEncoder.encode(hostname, "UTF-8") }.getOrNull() ?: return null
        val sep = if (endpointUrl.contains('?')) "&" else "?"
        val combinedAddresses = LinkedHashSet<InetAddress>()
        var minTtl = DEFAULT_TTL_SECONDS
        for (qType in listOf("A", "AAAA")) {
            val res = runCatching {
                val url = "${endpointUrl}${sep}name=$encoded&type=$qType"
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
                combinedAddresses.addAll(res.first)
                if (res.second < minTtl) minTtl = res.second
                if (qType == "A") break
            }
        }
        return if (combinedAddresses.isEmpty()) null else (combinedAddresses.toList() to minTtl)
    }

    internal fun queryCloudflareDohWire(hostname: String, dohHost: String = PRIMARY_IPV4): DnsWireResult? {
        return queryRfc8484DohWire(hostname, "https://$dohHost/dns-query")
    }

    internal fun queryRfc8484DohWire(hostname: String, endpointUrl: String): DnsWireResult? {
        val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
        if (labels.isEmpty()) return null

        val firstAttempt = executeRfc8484SingleQuery(hostname, labels, endpointUrl, qType = 1)
        if (firstAttempt != null) {
            if (firstAttempt.isSinkholed || firstAttempt.addresses.isNotEmpty()) {
                return firstAttempt
            }
            // If A query succeeded with 0 records and wasn't sinkholed, check AAAA (IPv6)
            val ipv6Attempt = executeRfc8484SingleQuery(hostname, labels, endpointUrl, qType = 28)
            if (ipv6Attempt != null && (ipv6Attempt.addresses.isNotEmpty() || ipv6Attempt.isSinkholed)) {
                return ipv6Attempt
            }
            return firstAttempt
        }
        return null
    }

    private fun executeRfc8484SingleQuery(
        hostname: String,
        labels: List<String>,
        endpointUrl: String,
        qType: Int
    ): DnsWireResult? {
        return try {
            val wirePacket = buildDnsQueryPacket(labels, qType = qType)
            val base64Url = try {
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(wirePacket)
            } catch (_: Throwable) {
                android.util.Base64.encodeToString(
                    wirePacket,
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                )
            }
            val sep = if (endpointUrl.contains('?')) "&" else "?"
            val getUrl = "${endpointUrl}${sep}dns=$base64Url"
            val getRequest = Request.Builder()
                .url(getUrl)
                .header("Accept", "application/dns-message")
                .get()
                .build()

            var shouldRetryWithPost = false
            val getResult = try {
                dohBootstrapClient.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        shouldRetryWithPost = response.code in setOf(400, 405, 415, 501)
                        return@use null
                    }
                    val bytes = response.body?.bytes() ?: return@use null
                    parseDnsWireResponseDetailed(hostname, bytes, bytes.size)
                }
            } catch (_: Exception) {
                // Network/TLS timeout on GET: do not double-stall with POST to the same unreachable server
                return null
            }
            if (getResult != null || !shouldRetryWithPost) return getResult

            // Fallback to RFC 8484 POST only when the DoH server rejected GET by HTTP status
            val postRequest = Request.Builder()
                .url(endpointUrl)
                .header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message")
                .post(wirePacket.toRequestBody(DNS_MESSAGE_MEDIA_TYPE))
                .build()

            dohBootstrapClient.newCall(postRequest).execute().use { response ->
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

        const val ADGUARD_FILTERED_PRIMARY_IPV4 = "94.140.14.14"
        const val ADGUARD_FILTERED_SECONDARY_IPV4 = "94.140.15.15"
        const val ADGUARD_FILTERED_DOH_HOST = "dns.adguard-dns.com"

        const val ADGUARD_UNFILTERED_PRIMARY_IPV4 = "94.140.14.140"
        const val ADGUARD_UNFILTERED_SECONDARY_IPV4 = "94.140.14.141"
        const val ADGUARD_UNFILTERED_DOH_HOST = "unfiltered.adguard-dns.com"

        const val QUAD9_PRIMARY_IPV4 = "9.9.9.9"
        const val GOOGLE_PRIMARY_IPV4 = "8.8.8.8"
        const val GOOGLE_SECONDARY_IPV4 = "8.8.4.4"
        const val GOOGLE_DOH_HOST = "dns.google"
        private const val DEFAULT_TTL_SECONDS = 300L
        private val DNS_MESSAGE_MEDIA_TYPE = "application/dns-message".toMediaType()

        private val PRIMARY_IPV4_BYTES = byteArrayOf(1, 1, 1, 1)
        private val SECONDARY_IPV4_BYTES = byteArrayOf(1, 0, 0, 1)
        private val ADGUARD_FILTERED_PRIMARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 14, 14)
        private val ADGUARD_FILTERED_SECONDARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 15, 15)
        private val ADGUARD_FAMILY_PRIMARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 14, 15)
        private val ADGUARD_FAMILY_SECONDARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 15, 16)
        private val ADGUARD_UNFILTERED_PRIMARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 14, 140.toByte())
        private val ADGUARD_UNFILTERED_SECONDARY_BYTES = byteArrayOf(94.toByte(), 140.toByte(), 14, 141.toByte())
        private val QUAD9_PRIMARY_BYTES = byteArrayOf(9, 9, 9, 9)
        private val QUAD9_SECONDARY_BYTES = byteArrayOf(149.toByte(), 112, 112, 112)
        private val GOOGLE_PRIMARY_IPV4_BYTES = byteArrayOf(8, 8, 8, 8)
        private val GOOGLE_SECONDARY_IPV4_BYTES = byteArrayOf(8, 8, 4, 4)
        private val OPENDNS_PRIMARY_BYTES = byteArrayOf(208.toByte(), 67, 222.toByte(), 222.toByte())
        private val OPENDNS_SECONDARY_BYTES = byteArrayOf(208.toByte(), 67, 220.toByte(), 220.toByte())
        private val CLEANBROWSING_PRIMARY_BYTES = byteArrayOf(185.toByte(), 228.toByte(), 168.toByte(), 9)
        private val CLEANBROWSING_SECONDARY_BYTES = byteArrayOf(185.toByte(), 228.toByte(), 169.toByte(), 9)
        private val CONTROLD_PRIMARY_BYTES = byteArrayOf(76, 76, 2, 0)
        private val CONTROLD_SECONDARY_BYTES = byteArrayOf(76, 76, 2, 2)
        private val NEXTDNS_PRIMARY_BYTES = byteArrayOf(45, 90, 28, 0)
        private val NEXTDNS_SECONDARY_BYTES = byteArrayOf(45, 90, 30, 0)
        private val MULLVAD_PRIMARY_BYTES = byteArrayOf(194.toByte(), 242.toByte(), 2, 2)
        private val MULLVAD_SECONDARY_BYTES = byteArrayOf(194.toByte(), 242.toByte(), 2, 3)

        private val TRANSPARENT_1X1_GIF_BYTES = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00,
            0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
        )

        const val ADBLOCKER_COSMETIC_CLEANUP_JS = """
            (function(){
              try {
                if (window.__dragonAdblockDnsCssApplied) return;
                window.__dragonAdblockDnsCssApplied = true;
                var style = document.createElement('style');
                style.textContent = 'ins.adsbygoogle, .adsbygoogle, iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], iframe[src*="adsterra"], iframe[src*="exoclick"], iframe[src*="popads"], div[id^="google_ads_iframe_"], div[id^="div-gpt-ad"] { display: none !important; height: 0 !important; min-height: 0 !important; pointer-events: none !important; }';
                (document.head || document.documentElement).appendChild(style);
              } catch(e) {}
            })();
        """

        // Comprehensive Ad, Popup, Redirect & Tracker domain suffixes blocked by AdBlocker DNS mode
        private val KNOWN_AD_TRACKER_DOMAIN_SUFFIXES = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com",
            "ade.googlesyndication.com",
            "adnxs.com",
            "adsrvr.org",
            "criteo.com",
            "criteo.net",
            "outbrain.com",
            "taboola.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "adsterra.com",
            "adsterratools.com",
            "exoclick.com",
            "exosrv.com",
            "juicyads.com",
            "trafficjunky.net",
            "trafficjunky.com",
            "tsyndicate.com",
            "hilltopads.net",
            "clickadu.com",
            "clickaine.com",
            "admaven.com",
            "bidvertiser.com",
            "revcontent.com",
            "mgid.com",
            "rubiconproject.com",
            "pubmatic.com",
            "openx.net",
            "casalemedia.com",
            "smartadserver.com",
            "moatads.com",
            "scorecardresearch.com",
            "quantserve.com",
            "zedo.com",
            "advertising.com",
            "adform.net",
            "2mdn.net",
            "amazon-adsystem.com",
            "ads-twitter.com",
            "ads.yahoo.com",
            "adroll.com",
            "bidgear.com",
            "adcolony.com",
            "applovin.com",
            "vungle.com",
            "unityads.unity3d.com",
            "chartboost.com",
            "inmobi.com",
            "ironsrc.com",
            "tapjoy.com",
            "startappservice.com",
            "smaato.net",
            "fyber.com",
            "mintegral.com",
            "adjust.com",
            "appsflyer.com",
            "branch.io",
            "kochava.com",
            "singular.net",
            "mixpanel.com",
            "amplitude.com",
            "hotjar.com",
            "mouseflow.com",
            "luckyorange.com",
            "fullstory.com",
            "crazyegg.com",
            "logrocket.io",
            "segment.io",
            "segment.com",
            "newrelic.com",
            "nr-data.net",
            "bugsnag.com",
            "omtrdc.net",
            "demdex.net",
            "everesttech.net",
            "bluekai.com",
            "krxd.net",
            "rlcdn.com",
            "turn.com",
            "mathtag.com",
            "mediamath.com",
            "Lotame.com".lowercase(Locale.US),
            "crwdcntrl.net",
            "exelator.com",
            "eyeota.net",
            "tapad.com",
            "liadm.com",
            "id5-sync.com",
            "teads.tv",
            "sharethrough.com",
            "triplelift.com",
            "33across.com",
            "sovrn.com",
            "lijit.com",
            "gumgum.com",
            "kargo.com",
            "yieldmo.com",
            "indexexchange.com",
            "contextweb.com",
            "spotxchange.com",
            "spotx.tv",
            "springserve.com",
            "innovid.com",
            "flashtalking.com",
            "celtra.com",
            "sizmek.com",
            "serving-sys.com",
            "adition.com",
            "adtech.de",
            "adtechus.com",
            "zemanta.com",
            "nativo.com",
            "connatix.com",
            "primis.tech",
            "aniview.com",
            "vidazoo.com",
            "playwire.com",
            "freestar.com",
            "snigelweb.com",
            "ezoic.net",
            "mediavine.com",
            "adthrive.com",
            "monumetric.com",
            "a-ads.com",
            "coinzilla.com",
            "cointraffic.io",
            "bitmedia.io",
            "adcash.com",
            "galaksion.com",
            "evadav.com",
            "richads.com",
            "pushground.com",
            "mondiad.com",
            "rollerads.com",
            "trafficstars.com",
            "plugrush.com",
            "ero-advertising.com",
            "realsrv.com",
            "magsrv.com",
            "onclkds.com",
            "onclickalgo.com",
            "acscdn.com",
            " highperformanceformat.com".trim(),
            "profitablecpmrate.com",
            "effectivegatecpm.com",
            "topcreativeformat.com",
            "bncloudfl.com",
            "irtyc.com"
        )

        private val FIRST_LABEL_AD_SAFE_ALLOWLIST = setOf(
            "tracker.debian.org",
            "track.ups.com",
            "track.fedex.com",
            "track.dhl.com",
            "analytics.github.com",
            "pixel.google.com",
            "analytics.google.com"
        )

        fun isKnownAdOrTrackerDomain(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            if (h.isBlank()) return false
            if (KNOWN_AD_TRACKER_DOMAIN_SUFFIXES.any { suffix -> h == suffix || h.endsWith(".$suffix") }) {
                return true
            }
            if (h in FIRST_LABEL_AD_SAFE_ALLOWLIST ||
                (h.endsWith(".google.com") && h != "adservice.google.com")
            ) {
                return false
            }
            val firstLabel = h.substringBefore('.')
            if ((firstLabel == "ads" || firstLabel == "adserver" || firstLabel == "adservice" ||
                    firstLabel == "Banner".lowercase(Locale.US) || firstLabel == "popads" ||
                    firstLabel == "telemetry" || firstLabel == "analytics" || firstLabel == "track" ||
                    firstLabel == "tracker" || firstLabel == "pixel") &&
                !isMediaStreamingOrEssentialHost(h)
            ) {
                return true
            }
            return false
        }

        fun isKnownAdOrTrackerUrlPath(url: String): Boolean {
            val lower = url.lowercase(Locale.US)
            return lower.contains("/pagead/") ||
                lower.contains("/ads/ga-audiences") ||
                lower.contains("/adserver/") ||
                lower.contains("/popunder") ||
                lower.contains("adsbygoogle.js") ||
                lower.contains("/gpt.js") ||
                lower.contains("/prebid")
        }

        private fun extractHostFromUrl(url: String): String? {
            return try {
                val afterScheme = url.substringAfter("://", "")
                if (afterScheme.isBlank()) return null
                afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
                    .trim().lowercase(Locale.US).trimEnd('.').takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        fun mapKnownIpv4ToDohEndpoint(ipv4: String): String? {
            return when (ipv4.trim()) {
                "1.1.1.1", "1.0.0.1" -> "https://cloudflare-dns.com/dns-query"
                "1.1.1.2", "1.0.0.2" -> "https://security.cloudflare-dns.com/dns-query"
                "1.1.1.3", "1.0.0.3" -> "https://family.cloudflare-dns.com/dns-query"
                "94.140.14.14", "94.140.15.15" -> "https://dns.adguard-dns.com/dns-query"
                "94.140.14.140", "94.140.14.141" -> "https://unfiltered.adguard-dns.com/dns-query"
                "94.140.14.15", "94.140.15.16" -> "https://family.adguard-dns.com/dns-query"
                "9.9.9.9", "149.112.112.112" -> "https://dns.quad9.net/dns-query"
                "8.8.8.8", "8.8.4.4" -> "https://dns.google/dns-query"
                "208.67.222.222", "208.67.220.220" -> "https://doh.opendns.com/dns-query"
                "185.228.168.9", "185.228.169.9" -> "https://doh.cleanbrowsing.org/doh/security-filter/"
                "76.76.2.0" -> "https://freedns.controld.com/p0"
                "76.76.2.2" -> "https://freedns.controld.com/p2"
                else -> null
            }
        }

        fun parseIpv4Bytes(raw: String?): ByteArray? {
            val clean = raw?.trim()?.removePrefix("http://")?.removePrefix("https://")
                ?.substringBefore('/')?.substringBefore(':') ?: return null
            val parts = clean.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0..3) {
                val n = parts[i].toIntOrNull() ?: return null
                if (n !in 0..255) return null
                out[i] = n.toByte()
            }
            return out
        }

        fun normalizeCustomDohEndpoint(raw: String): String {
            val trimmed = raw.trim()
            val mappedFromIp = mapKnownIpv4ToDohEndpoint(trimmed)
            if (mappedFromIp != null) return mappedFromIp
            if (trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true)) {
                return if (trimmed.substringAfter("://").contains('/')) trimmed else "$trimmed/dns-query"
            }
            val cleanHost = trimmed.removePrefix("tls://").removePrefix("quic://").removePrefix("sdns://").trimEnd('/')
            return if (cleanHost.contains('/')) "https://$cleanHost" else "https://$cleanHost/dns-query"
        }

        private fun isDownloadableAttachmentResponse(
            url: String,
            mimeType: String,
            contentDisposition: String?
        ): Boolean {
            if (contentDisposition?.contains("attachment", ignoreCase = true) == true) {
                return true
            }
            val lowerMime = mimeType.lowercase(Locale.US)
            if (lowerMime in setOf(
                    "application/octet-stream",
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/x-rar-compressed",
                    "application/vnd.rar",
                    "application/x-7z-compressed",
                    "application/vnd.android.package-archive",
                    "application/x-bittorrent",
                    "application/x-iso9660-image",
                    "application/x-msdownload",
                    "application/x-tar",
                    "application/gzip"
                )
            ) {
                return true
            }
            val pathLower = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
            return pathLower.endsWith(".apk") ||
                pathLower.endsWith(".zip") ||
                pathLower.endsWith(".rar") ||
                pathLower.endsWith(".7z") ||
                pathLower.endsWith(".iso") ||
                pathLower.endsWith(".img") ||
                pathLower.endsWith(".torrent")
        }

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

        private val ESSENTIAL_STREAMING_HOST_SUFFIXES = listOf(
            "googlevideo.com",
            "youtube.com",
            "ytimg.com",
            "youtu.be",
            "ggpht.com",
            "googleusercontent.com",
            "gstatic.com",
            "googleapis.com",
            "gvt1.com",
            "bilibili.tv",
            "bilibili.com",
            "bilivideo.com",
            "bstarstatic.com",
            "hdslb.com",
            "akamaized.net",
            "cloudfront.net",
            "fastly.net",
            "vimeo.com",
            "vimeocdn.com",
            "dailymotion.com",
            "dmcdn.net",
            "patreon.com",
            "patreonusercontent.com",
            "mux.com",
            "jwpsrv.com",
            "jwpcdn.com",
            "tiktokcdn.com",
            "fbcdn.net",
            "cdninstagram.com",
            "twimg.com",
            "redd.it",
            "redditmedia.com"
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
            if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress) {
                return true
            }
            val raw = addr.address
            if (raw != null && raw.size == 4) {
                val b0 = raw[0].toInt() and 0xFF
                val b1 = raw[1].toInt() and 0xFF
                // Carrier-grade NAT 100.64.0.0/10 or Benchmarking 198.18.0.0/15 often used by ISP DNS hijacking
                if (b0 == 100 && (b1 in 64..127)) return true
                if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
                if (b0 == 0) return true
            }
            val hostAddr = addr.hostAddress ?: return true
            return hostAddr == "0.0.0.0" ||
                hostAddr == "127.0.0.1" ||
                hostAddr == "::" ||
                hostAddr == "::1" ||
                hostAddr == "0:0:0:0:0:0:0:0" ||
                hostAddr == "0:0:0:0:0:0:0:1" ||
                hostAddr == "94.140.14.33" || // AdGuard block sinkhole IP
                hostAddr == "94.140.14.35" ||
                hostAddr.startsWith("146.112.61.") // OpenDNS / Cisco sinkhole range
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
                path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
                path.endsWith(".css") -> "text/css"
                path.endsWith(".json") -> "application/json"
                path.endsWith(".png") -> "image/png"
                path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
                path.endsWith(".webp") -> "image/webp"
                path.endsWith(".svg") -> "image/svg+xml"
                path.endsWith(".woff2") -> "font/woff2"
                path.endsWith(".woff") -> "font/woff"
                path.endsWith(".ttf") -> "font/ttf"
                else -> "text/html"
            }
        }

        fun isIpLiteral(host: String): Boolean {
            val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
            return ipv4Regex.matches(host) || host.contains(':')
        }

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

        internal fun queryDnsUdpWire(hostname: String, serverIpBytes: ByteArray): DnsWireResult? {
            return try {
                val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
                if (labels.isEmpty()) return null

                val packetBytes = buildDnsQueryPacket(labels, qType = 1)
                DatagramSocket().use { socket ->
                    socket.soTimeout = 800
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

        /**
         * Queries DNS over TCP port 53 (RFC 1035 Section 4.2.2) using a 2-byte length prefix.
         * Essential when mobile carriers drop/throttle UDP port 53 or when responses are truncated.
         */
        internal fun queryDnsTcpWire(hostname: String, serverIpBytes: ByteArray): DnsWireResult? {
            return try {
                val labels = hostname.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
                if (labels.isEmpty()) return null

                val packetBytes = buildDnsQueryPacket(labels, qType = 1)
                Socket().use { socket ->
                    socket.soTimeout = 1500
                    val serverAddr = InetAddress.getByAddress(serverIpBytes)
                    socket.connect(InetSocketAddress(serverAddr, 53), 1200)
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeShort(packetBytes.size)
                    out.write(packetBytes)
                    out.flush()

                    val input = DataInputStream(socket.getInputStream())
                    val respLength = input.readUnsignedShort()
                    if (respLength !in 12..8192) return null
                    val recvBuf = ByteArray(respLength)
                    input.readFully(recvBuf)
                    parseDnsWireResponseDetailed(hostname, recvBuf, respLength)
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
            dos.writeShort(0x1111)
            dos.writeShort(0x0100)
            dos.writeShort(1)
            dos.writeShort(0)
            dos.writeShort(0)
            dos.writeShort(0)
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0)
            dos.writeShort(qType)
            dos.writeShort(1)
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
            if (rcode == 3 || rcode == 5) {
                // NXDOMAIN or REFUSED (used by AdBlocker / Filtering DNS for blocked domains)
                return DnsWireResult(
                    addresses = emptyList(),
                    ttlSeconds = 120L,
                    isSinkholed = true,
                    isTruncated = false
                )
            }
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
                    return pos + 2
                }
                pos += 1 + len
            }
            return -1
        }
    }
}
