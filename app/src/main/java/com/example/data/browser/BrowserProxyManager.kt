package com.example.data.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.example.data.downloader.CloudflareWarpDns
import com.example.data.security.MalwareProtectionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor

enum class BrowserProxyType(
    val id: String,
    val label: String,
    val defaultPort: Int,
    val scheme: String
) {
    HTTP("HTTP", "HTTP", 8080, "http"),
    HTTPS("HTTPS", "HTTPS", 8443, "https"),
    SOCKS5("SOCKS5", "SOCKS5", 1080, "socks5"),
    SOCKS4("SOCKS4", "SOCKS4", 1080, "socks4");

    companion object {
        fun fromId(raw: String?): BrowserProxyType {
            val upper = raw?.trim()?.uppercase(Locale.US)
            return entries.firstOrNull { it.id == upper || it.scheme.equals(raw?.trim(), true) } ?: HTTP
        }
    }
}

data class BrowserProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: BrowserProxyType = BrowserProxyType.HTTP,
    val host: String,
    val port: Int = type.defaultPort,
    val username: String = "",
    val password: String = "",
    val bypassRules: List<String> = DEFAULT_BYPASS_RULES,
    val reverseBypass: Boolean = false,
    val applyToDownloads: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun hasAuth(): Boolean = username.isNotBlank()

    fun formattedHost(): String {
        val trimmed = host.trim()
        return if (trimmed.contains(':') && !trimmed.startsWith("[")) {
            "[$trimmed]"
        } else {
            trimmed
        }
    }

    fun toProxyRuleUrl(): String = "${type.scheme}://${formattedHost()}:$port"

    fun toDisplayEndpoint(): String = "${formattedHost()}:$port"

    fun toJavaNetProxy(): Proxy {
        val javaType = when (type) {
            BrowserProxyType.HTTP, BrowserProxyType.HTTPS -> Proxy.Type.HTTP
            BrowserProxyType.SOCKS5, BrowserProxyType.SOCKS4 -> Proxy.Type.SOCKS
        }
        return Proxy(javaType, InetSocketAddress.createUnresolved(host.trim(), port))
    }

    fun shouldProxyHost(targetHost: String?): Boolean {
        val hostLower = targetHost?.trim()?.lowercase(Locale.US).orEmpty()
        if (hostLower.isBlank()) return !reverseBypass
        val matched = bypassRules.any { rawRule ->
            val rule = rawRule.trim().lowercase(Locale.US)
            when {
                rule.isBlank() -> false
                rule == "<local>" -> !hostLower.contains('.') || hostLower == "localhost" || hostLower == "127.0.0.1"
                rule.startsWith("*.") -> {
                    val suffix = rule.removePrefix("*.")
                    hostLower == suffix || hostLower.endsWith(".$suffix")
                }
                rule.endsWith(".*") -> {
                    val prefix = rule.removeSuffix(".*")
                    hostLower.startsWith("$prefix.")
                }
                else -> hostLower == rule
            }
        }
        return if (reverseBypass) matched else !matched
    }

    companion object {
        val DEFAULT_BYPASS_RULES = listOf("localhost", "127.0.0.1", "*.local")

        fun parseBypassInput(raw: String): List<String> {
            return raw.split(',', '\n', ';')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        }
    }
}

data class ProxyConnectivityResult(
    val reachable: Boolean,
    val latencyMs: Long,
    val protocolDetail: String
)

/**
 * Coordinates WebView ProxyController overrides (HTTP, HTTPS, SOCKS5, SOCKS4),
 * OkHttp download/extension routing, SOCKS5/HTTP proxy authentication,
 * proxy URI parsing, and live proxy connectivity verification.
 */
object BrowserProxyManager {

    @Volatile
    var activeNetworkProxy: BrowserProxyProfile? = null
        private set

    @Volatile
    var activeWarpDns: CloudflareWarpDns? = null
        private set

    /**
     * True when Android WebView's ProxyController is actively pointed at our loopback
     * [LocalBrowserProxyServer], meaning 100% of WebView TCP/TLS/HTTP/HTTPS/WebSocket traffic
     * already flows through our active Custom DNS and/or upstream Proxy natively.
     */
    @Volatile
    var isLocalProxyActiveForWebView: Boolean = false
        private set

    @Volatile
    private var lastAppliedOverrideKey: String? = null

    private val localProxyServer: LocalBrowserProxyServer by lazy {
        LocalBrowserProxyServer(
            dnsProvider = { activeWarpDns },
            proxyProvider = { activeNetworkProxy }
        )
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val mainExecutor = Executor { runnable ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    private val defaultSystemProxySelector: ProxySelector? = runCatching { ProxySelector.getDefault() }.getOrNull()

    val okHttpProxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val active = activeNetworkProxy
            if (active != null && active.applyToDownloads) {
                return if (active.shouldProxyHost(uri?.host)) {
                    // When the local loopback proxy bridge is running, route protocols that OkHttp's
                    // java.net.Proxy does not natively support (HTTPS TLS proxy, SOCKS4/4a, authenticated SOCKS5)
                    // through LocalBrowserProxyServer so downloads also succeed with every proxy type.
                    val needsBridge = active.type == BrowserProxyType.HTTPS ||
                        active.type == BrowserProxyType.SOCKS4 ||
                        (active.type == BrowserProxyType.SOCKS5 && active.hasAuth())
                    if (needsBridge && localProxyServer.isRunning && localProxyServer.port > 0) {
                        listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localProxyServer.port)))
                    } else {
                        listOf(active.toJavaNetProxy())
                    }
                } else {
                    listOf(Proxy.NO_PROXY)
                }
            }
            return runCatching { defaultSystemProxySelector?.select(uri) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            if (activeNetworkProxy == null) {
                runCatching { defaultSystemProxySelector?.connectFailed(uri, sa, ioe) }
            }
        }
    }

    val okHttpProxyAuthenticator: Authenticator = Authenticator { _: Route?, response: Response ->
        val active = activeNetworkProxy ?: return@Authenticator null
        if (!active.hasAuth()) return@Authenticator null
        if (response.priorResponseCount() >= 2) return@Authenticator null

        val challengeMatches = response.challenges().any {
            it.scheme.equals("Basic", ignoreCase = true)
        }
        val request: Request = response.request
        if (response.code == 407 && (challengeMatches || response.challenges().isEmpty())) {
            val credential = Credentials.basic(active.username, active.password)
            if (request.header("Proxy-Authorization") == credential) {
                return@Authenticator null
            }
            return@Authenticator request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
        null
    }

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var cur = priorResponse
        while (cur != null) {
            count++
            cur = cur.priorResponse
        }
        return count
    }

    fun syncNetworkProxyState(enabled: Boolean, profile: BrowserProxyProfile?) {
        val effective = if (enabled && profile != null) profile else null
        activeNetworkProxy = effective
        runCatching {
            if (effective != null && effective.hasAuth()) {
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication? {
                        val current = activeNetworkProxy ?: return null
                        if (!current.hasAuth()) return null
                        val reqHost = requestingHost?.trim().orEmpty()
                        val reqPort = requestingPort
                        val isProxyPrompt = requestorType == RequestorType.PROXY ||
                            (reqHost.equals(current.host.trim(), ignoreCase = true) &&
                                (reqPort <= 0 || reqPort == current.port))
                        return if (isProxyPrompt) {
                            PasswordAuthentication(current.username, current.password.toCharArray())
                        } else {
                            null
                        }
                    }
                })
            } else {
                java.net.Authenticator.setDefault(null)
            }
        }
    }

    /**
     * Unified network routing controller for WebView:
     * - Whenever ANY Custom DNS (Cloudflare WARP, AdGuard AdBlocker, AdGuard Unfiltered, Custom DNS)
     *   OR ANY Proxy (HTTP, HTTPS, SOCKS5 with/without auth, SOCKS4/4a) is enabled, starts the
     *   loopback-only [LocalBrowserProxyServer] and points WebView's [ProxyController] at
     *   `http://127.0.0.1:<port>`.
     * - Bypass rules inside WebView are kept strictly to loopback + internal extension host so
     *   user proxy bypass rules & reverse-bypass are evaluated inside [LocalBrowserProxyServer],
     *   ensuring bypassed hosts STILL receive Custom DNS resolution.
     * - When both Proxy and Custom DNS are disabled, clears the WebView proxy override cleanly.
     */
    fun configureWebViewNetworkRouting(
        @Suppress("UNUSED_PARAMETER") context: Context,
        proxyEnabled: Boolean,
        proxyProfile: BrowserProxyProfile?,
        warpDns: CloudflareWarpDns?,
        onCompleted: (applied: Boolean, statusMessage: String) -> Unit = { _, _ -> }
    ) {
        activeWarpDns = warpDns
        syncNetworkProxyState(proxyEnabled, proxyProfile)

        val effectiveProxy = if (proxyEnabled && proxyProfile != null) proxyProfile else null
        val customDnsActive = warpDns?.isAnyCustomDnsActive == true

        // Register active user-configured Proxy & Custom DNS infrastructure hosts with MalwareProtectionEngine
        val infraHosts = buildList {
            if (effectiveProxy != null && effectiveProxy.host.isNotBlank()) {
                add(effectiveProxy.host)
            }
            warpDns?.activeCustomDns?.let { custom ->
                if (custom.primaryServer.isNotBlank()) add(custom.primaryServer)
                if (custom.secondaryServer.isNotBlank()) add(custom.secondaryServer)
            }
        }
        MalwareProtectionEngine.setInfrastructureHosts(infraHosts)

        val needsLocalTunnel = (effectiveProxy != null) || customDnsActive
        val isSupported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        }.getOrDefault(false)

        if (!isSupported) {
            isLocalProxyActiveForWebView = false
            // Still start local bridge if downloads need HTTPS/SOCKS4/auth-SOCKS5 bridging
            if (effectiveProxy != null && effectiveProxy.applyToDownloads &&
                (effectiveProxy.type == BrowserProxyType.HTTPS ||
                    effectiveProxy.type == BrowserProxyType.SOCKS4 ||
                    (effectiveProxy.type == BrowserProxyType.SOCKS5 && effectiveProxy.hasAuth()))
            ) {
                runCatching { localProxyServer.ensureStarted() }
            }
            val msg = when {
                effectiveProxy != null -> "Downloader proxy ready (${effectiveProxy.type.label}); WebView fallback active"
                customDnsActive -> "Custom DNS fallback tunnel active"
                else -> "Direct connection active"
            }
            onCompleted(false, msg)
            return
        }

        val statusSummary = when {
            effectiveProxy != null && customDnsActive ->
                "Proxy ${effectiveProxy.type.label} (${effectiveProxy.toDisplayEndpoint()}) + ${warpDns?.dnsMode?.title}"
            effectiveProxy != null ->
                "Proxy active: ${effectiveProxy.type.label} (${effectiveProxy.toDisplayEndpoint()})"
            customDnsActive ->
                "DNS active: ${warpDns?.dnsMode?.title}"
            else -> "Direct connection active"
        }

        if (needsLocalTunnel) {
            val localPort = try {
                localProxyServer.ensureStarted()
            } catch (err: Throwable) {
                isLocalProxyActiveForWebView = false
                onCompleted(false, err.localizedMessage ?: "Failed to start local routing server")
                return
            }

            val desiredKey = "LOCAL_BRIDGE:127.0.0.1:$localPort"
            if (desiredKey == lastAppliedOverrideKey && isLocalProxyActiveForWebView) {
                // LocalBrowserProxyServer reads live dnsProvider & proxyProvider dynamically per connection
                onCompleted(true, statusSummary)
                return
            }

            runCatching {
                val controller = ProxyController.getInstance()
                val builder = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$localPort")
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("[::1]")
                    .addBypassRule("dragon-ext.local")
                    .addBypassRule("*.dragon-ext.local")

                controller.setProxyOverride(builder.build(), mainExecutor) {
                    lastAppliedOverrideKey = desiredKey
                    isLocalProxyActiveForWebView = true
                    onCompleted(true, statusSummary)
                }
            }.onFailure { err ->
                isLocalProxyActiveForWebView = false
                onCompleted(false, err.localizedMessage ?: "Failed to apply WebView network routing")
            }
        } else {
            val desiredKey = "DIRECT"
            if (desiredKey == lastAppliedOverrideKey && !isLocalProxyActiveForWebView) {
                onCompleted(true, "Direct connection active")
                return
            }
            runCatching {
                val controller = ProxyController.getInstance()
                controller.clearProxyOverride(mainExecutor) {
                    lastAppliedOverrideKey = desiredKey
                    isLocalProxyActiveForWebView = false
                    onCompleted(true, "Direct connection restored")
                }
            }.onFailure { err ->
                isLocalProxyActiveForWebView = false
                onCompleted(false, err.localizedMessage ?: "Failed to clear WebView proxy")
            }
        }
    }

    fun applyWebViewProxyOverride(
        context: Context,
        enabled: Boolean,
        profile: BrowserProxyProfile?,
        onCompleted: (applied: Boolean, statusMessage: String) -> Unit = { _, _ -> }
    ) {
        configureWebViewNetworkRouting(
            context = context,
            proxyEnabled = enabled,
            proxyProfile = profile,
            warpDns = activeWarpDns,
            onCompleted = onCompleted
        )
    }

    fun validateProxyInput(host: String, portText: String): String? {
        val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
        if (cleanHost.isBlank()) return "Proxy host or IP address is required."
        if (cleanHost.any { it.isWhitespace() }) return "Proxy host cannot contain spaces."
        val port = portText.trim().toIntOrNull() ?: return "Port must be a valid number."
        if (port !in 1..65535) return "Port must be between 1 and 65535."
        return null
    }

    /**
     * Parses common proxy strings:
     * - `socks5://user:pass@192.168.1.10:1080`
     * - `http://proxy.example.com:8080`
     * - `https://user:pass@secure-proxy.net:8443`
     * - `192.168.1.10:1080:user:pass`
     * - `192.168.1.10:1080`
     * - `[2001:db8::1]:1080`
     */
    fun parseProxyUri(
        rawInput: String,
        defaultType: BrowserProxyType = BrowserProxyType.HTTP,
        nameHint: String = ""
    ): Result<BrowserProxyProfile> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a proxy address or URI."))
        }

        // Case 1: URI with explicit scheme (`http://`, `https://`, `socks5://`, `socks4://`, `socks://`)
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx > 0) {
            val rawScheme = trimmed.substring(0, schemeIdx).lowercase(Locale.US)
            val parsedType = when (rawScheme) {
                "http" -> BrowserProxyType.HTTP
                "https" -> BrowserProxyType.HTTPS
                "socks5", "socks5h", "socks" -> BrowserProxyType.SOCKS5
                "socks4", "socks4a" -> BrowserProxyType.SOCKS4
                else -> return Result.failure(IllegalArgumentException("Unsupported proxy scheme: $rawScheme"))
            }
            val uri = runCatching { URI(trimmed) }.getOrElse {
                return Result.failure(IllegalArgumentException("Malformed proxy URI."))
            }
            val parsedHost = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
            if (parsedHost.isBlank()) {
                return Result.failure(IllegalArgumentException("Missing host in proxy URI."))
            }
            val parsedPort = if (uri.port > 0) uri.port else parsedType.defaultPort
            if (parsedPort !in 1..65535) {
                return Result.failure(IllegalArgumentException("Invalid proxy port: $parsedPort"))
            }
            var user = ""
            var pass = ""
            val userInfo = uri.rawUserInfo.orEmpty()
            if (userInfo.isNotBlank()) {
                val colon = userInfo.indexOf(':')
                if (colon >= 0) {
                    user = runCatching { URLDecoder.decode(userInfo.substring(0, colon), "UTF-8") }.getOrDefault(userInfo.substring(0, colon))
                    pass = runCatching { URLDecoder.decode(userInfo.substring(colon + 1), "UTF-8") }.getOrDefault(userInfo.substring(colon + 1))
                } else {
                    user = runCatching { URLDecoder.decode(userInfo, "UTF-8") }.getOrDefault(userInfo)
                }
            }
            val cleanName = nameHint.trim().ifBlank { "${parsedType.label} • $parsedHost:$parsedPort" }
            return Result.success(
                BrowserProxyProfile(
                    name = cleanName,
                    type = parsedType,
                    host = parsedHost,
                    port = parsedPort,
                    username = user,
                    password = pass
                )
            )
        }

        // Case 2: Bracketed IPv6 host `[addr]:port` or `[addr]:port:user:pass`
        if (trimmed.startsWith("[")) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket <= 1) {
                return Result.failure(IllegalArgumentException("Invalid bracketed IPv6 host."))
            }
            val ipv6Host = trimmed.substring(1, endBracket).trim()
            val remainder = trimmed.substring(endBracket + 1).removePrefix(":")
            val parts = remainder.split(':')
            val port = parts.firstOrNull()?.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Invalid port after IPv6 host."))
            if (port !in 1..65535) return Result.failure(IllegalArgumentException("Port must be 1..65535."))
            val user = parts.getOrNull(1).orEmpty()
            val pass = parts.getOrNull(2).orEmpty()
            return Result.success(
                BrowserProxyProfile(
                    name = nameHint.trim().ifBlank { "${defaultType.label} • [$ipv6Host]:$port" },
                    type = defaultType,
                    host = ipv6Host,
                    port = port,
                    username = user,
                    password = pass
                )
            )
        }

        // Case 3: `user:pass@host:port` without scheme
        var body = trimmed
        var user = ""
        var pass = ""
        val atIdx = body.lastIndexOf('@')
        if (atIdx > 0) {
            val credPart = body.substring(0, atIdx)
            body = body.substring(atIdx + 1)
            val colonIdx = credPart.indexOf(':')
            if (colonIdx >= 0) {
                user = credPart.substring(0, colonIdx)
                pass = credPart.substring(colonIdx + 1)
            } else {
                user = credPart
            }
        }

        val colonParts = body.split(':')
        return when (colonParts.size) {
            2 -> {
                val h = colonParts[0].trim()
                val p = colonParts[1].trim().toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("Invalid proxy port."))
                if (h.isBlank() || p !in 1..65535) {
                    return Result.failure(IllegalArgumentException("Invalid host or port (1..65535)."))
                }
                Result.success(
                    BrowserProxyProfile(
                        name = nameHint.trim().ifBlank { "${defaultType.label} • $h:$p" },
                        type = defaultType,
                        host = h,
                        port = p,
                        username = user,
                        password = pass
                    )
                )
            }
            4 -> {
                // `host:port:user:pass` format
                val h = colonParts[0].trim()
                val p = colonParts[1].trim().toIntOrNull()
                    ?: return Result.failure(IllegalArgumentException("Invalid proxy port."))
                if (h.isBlank() || p !in 1..65535) {
                    return Result.failure(IllegalArgumentException("Invalid host or port (1..65535)."))
                }
                Result.success(
                    BrowserProxyProfile(
                        name = nameHint.trim().ifBlank { "${defaultType.label} • $h:$p" },
                        type = defaultType,
                        host = h,
                        port = p,
                        username = user.ifBlank { colonParts[2] },
                        password = pass.ifBlank { colonParts[3] }
                    )
                )
            }
            else -> Result.failure(
                IllegalArgumentException("Use scheme://host:port, host:port, or host:port:user:pass")
            )
        }
    }

    /**
     * Performs a real TCP / TLS / SOCKS5 / SOCKS4 handshake test against the proxy endpoint on Dispatchers.IO.
     */
    suspend fun testProxyConnection(
        profile: BrowserProxyProfile,
        activeDns: CloudflareWarpDns? = activeWarpDns
    ): ProxyConnectivityResult = withContext(Dispatchers.IO) {
        val cleanHost = profile.host.trim().removePrefix("[").removeSuffix("]")
        if (cleanHost.isBlank() || profile.port !in 1..65535) {
            return@withContext ProxyConnectivityResult(false, 0L, "Invalid host or port")
        }
        val startNs = System.nanoTime()
        runCatching {
            if (profile.type == BrowserProxyType.HTTPS) {
                LocalBrowserProxyServer.openHttpOrHttpsProxyBaseSocket(profile, activeDns).use {
                    val tlsMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    return@runCatching ProxyConnectivityResult(
                        reachable = true,
                        latencyMs = tlsMs,
                        protocolDetail = "HTTPS proxy TLS handshake verified"
                    )
                }
            }

            val resolved = LocalBrowserProxyServer.resolveTargetAddresses(cleanHost, activeDns).firstOrNull()
                ?: InetAddress.getByName(cleanHost)
            Socket().use { socket ->
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress(resolved, profile.port), 5000)
                val tcpMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)

                if (profile.type == BrowserProxyType.SOCKS5) {
                    val out = socket.getOutputStream()
                    val inp = socket.getInputStream()
                    val greeting = if (profile.hasAuth()) {
                        byteArrayOf(0x05, 0x02, 0x00, 0x02) // No-Auth + Username/Password
                    } else {
                        byteArrayOf(0x05, 0x01, 0x00) // No-Auth only
                    }
                    out.write(greeting)
                    out.flush()
                    val ver = inp.read()
                    val method = inp.read()
                    val totalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    if (ver != 0x05) {
                        return@runCatching ProxyConnectivityResult(
                            reachable = false,
                            latencyMs = totalMs,
                            protocolDetail = "Port open (${tcpMs}ms), but server did not respond with SOCKS5 handshake"
                        )
                    }
                    return@runCatching when (method) {
                        0x00 -> ProxyConnectivityResult(true, totalMs, "SOCKS5 handshake OK (No Auth)")
                        0x02 -> {
                            if (!profile.hasAuth()) {
                                ProxyConnectivityResult(false, totalMs, "SOCKS5 server requires username/password")
                            } else {
                                // RFC 1929 Username/Password subnegotiation
                                val uBytes = profile.username.toByteArray(Charsets.UTF_8).take(255).toByteArray()
                                val pBytes = profile.password.toByteArray(Charsets.UTF_8).take(255).toByteArray()
                                val authPkt = ByteArray(3 + uBytes.size + pBytes.size)
                                authPkt[0] = 0x01
                                authPkt[1] = uBytes.size.toByte()
                                System.arraycopy(uBytes, 0, authPkt, 2, uBytes.size)
                                authPkt[2 + uBytes.size] = pBytes.size.toByte()
                                System.arraycopy(pBytes, 0, authPkt, 3 + uBytes.size, pBytes.size)
                                out.write(authPkt)
                                out.flush()
                                val authVer = inp.read()
                                val authStatus = inp.read()
                                val finalMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                                if (authVer == 0x01 && authStatus == 0x00) {
                                    ProxyConnectivityResult(true, finalMs, "SOCKS5 auth & handshake verified")
                                } else {
                                    ProxyConnectivityResult(false, finalMs, "SOCKS5 authentication rejected")
                                }
                            }
                        }
                        0xFF -> ProxyConnectivityResult(false, totalMs, "SOCKS5 server rejected authentication methods")
                        else -> ProxyConnectivityResult(true, totalMs, "SOCKS5 reachable (method $method)")
                    }
                }

                ProxyConnectivityResult(
                    reachable = true,
                    latencyMs = tcpMs,
                    protocolDetail = "${profile.type.label} proxy TCP endpoint reachable"
                )
            }
        }.getOrElse { err ->
            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            ProxyConnectivityResult(
                reachable = false,
                latencyMs = elapsedMs,
                protocolDetail = err.localizedMessage ?: "Connection failed"
            )
        }
    }
}
