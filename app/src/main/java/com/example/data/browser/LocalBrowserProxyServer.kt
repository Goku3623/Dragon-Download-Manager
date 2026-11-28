package com.example.data.browser

import com.example.data.downloader.CloudflareWarpDns
import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * High-performance loopback-only (127.0.0.1) HTTP/HTTPS CONNECT forward proxy server
 * used by Android WebView's ProxyController and OkHttp so that:
 *
 * 1. Every Custom DNS mode (Cloudflare WARP 1.1.1.1, AdGuard AdBlocker DNS 94.140.14.14,
 *    Unfiltered AdGuard DNS 94.140.14.140, and any Custom DoH/IPv4 DNS) resolves 100% of
 *    WebView traffic natively (GET, POST, PUT, DELETE, WebSockets, HTTP/2, Cookies, Auth,
 *    Streaming, Service Workers) while preserving Chromium's genuine end-to-end TLS handshake.
 * 2. Every Proxy protocol (HTTP, HTTPS over TLS, SOCKS5 with RFC 1929 username/password auth
 *    and remote DNS, and SOCKS4/SOCKS4a) works reliably across every website and download,
 *    including combined Proxy + Custom DNS + Malware & Security Shield configurations.
 */
class LocalBrowserProxyServer(
    @Volatile var dnsProvider: () -> CloudflareWarpDns? = { null },
    @Volatile var proxyProvider: () -> BrowserProxyProfile? = { null },
    @Volatile var malwareValidator: (url: String, host: String) -> Boolean = { _, _ -> false }
) {
    private val running = AtomicBoolean(false)
    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var port: Int = -1
        private set

    val isRunning: Boolean
        get() = running.get() && port > 0 && serverSocket?.isClosed == false

    private val threadCounter = AtomicInteger(0)
    private val executor: ExecutorService = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "dragon-local-proxy-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    })

    fun ensureStarted(): Int {
        if (isRunning) return port
        synchronized(lock) {
            if (isRunning) return port
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val ss = ServerSocket(0, 128, loopback).apply {
                reuseAddress = true
            }
            serverSocket = ss
            port = ss.localPort
            running.set(true)
            executor.execute { acceptLoop(ss) }
            return port
        }
    }

    fun stop() {
        synchronized(lock) {
            running.set(false)
            port = -1
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get() && !ss.isClosed) {
            val client = try {
                ss.accept()
            } catch (_: Throwable) {
                if (!running.get() || ss.isClosed) break
                continue
            }
            try {
                executor.execute { handleClient(client) }
            } catch (_: Throwable) {
                runCatching { client.close() }
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val clientIn = client.getInputStream()
            val clientOut = client.getOutputStream()

            val headerBytes = readHttpHeaderBlock(clientIn) ?: return
            val headerText = String(headerBytes, StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.trim().orEmpty()
            if (requestLine.isBlank()) return

            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeSimpleHttpError(clientOut, 400, "Bad Request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val targetRaw = parts[1].trim()
            val httpVersion = parts.getOrNull(2)?.takeIf { it.startsWith("HTTP/", ignoreCase = true) } ?: "HTTP/1.1"
            val headers = parseHeaders(lines.drop(1))

            if (method == "CONNECT") {
                handleConnectTunnel(client, clientIn, clientOut, targetRaw)
            } else {
                handlePlainHttpRequest(client, clientIn, clientOut, method, targetRaw, httpVersion, headers)
            }
        } catch (_: Throwable) {
            // Client closed or upstream error already handled cleanly
        } finally {
            runCatching { client.close() }
        }
    }

    private fun openResilientUpstreamTunnel(
        primaryProxy: BrowserProxyProfile,
        targetHost: String,
        targetPort: Int,
        activeDns: CloudflareWarpDns?
    ): Socket {
        // Merge any freshly-updated credentials from proxyProvider() in case onAuthRequired resolved asynchronously
        val latestActive = proxyProvider()
        val effectiveUser = primaryProxy.username.ifBlank { latestActive?.username.orEmpty() }
        val effectivePass = if (primaryProxy.username.isNotBlank()) primaryProxy.password else (latestActive?.password.orEmpty())

        val candidates = buildList {
            add(primaryProxy.copy(username = effectiveUser, password = effectivePass))
            primaryProxy.fallbackProxies.forEach { fb ->
                val fbUser = fb.username.ifBlank { effectiveUser }
                val fbPass = if (fb.username.isNotBlank()) fb.password else effectivePass
                add(fb.copy(username = fbUser, password = fbPass))
            }
        }.distinctBy { "${it.type.id}:${it.host.lowercase(Locale.US)}:${it.port}:${it.username}" }

        for (candidate in candidates) {
            try {
                return openUpstreamProxyTunnel(candidate, targetHost, targetPort, activeDns)
            } catch (_: Throwable) {
                // If an extension specified HTTP on port 443/8443 or HTTPS on port 80/8080, try alternate HTTP/HTTPS framing once
                val altType = when {
                    candidate.type == BrowserProxyType.HTTP && (candidate.port == 443 || candidate.port == 8443) ->
                        BrowserProxyType.HTTPS
                    candidate.type == BrowserProxyType.HTTPS && (candidate.port == 80 || candidate.port == 8080 || candidate.port == 3128) ->
                        BrowserProxyType.HTTP
                    else -> null
                }
                if (altType != null) {
                    try {
                        return openUpstreamProxyTunnel(candidate.copy(type = altType), targetHost, targetPort, activeDns)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        // Resilient DNS-protected fallback: if all upstream VPN proxy servers fail (e.g. HTTP 407 auth expired,
        // free VPN proxy node unreachable, or tunnel handshake rejected), fall back to direct socket using the
        // active Custom DNS (Cloudflare WARP / AdGuard AdBlocker / Unfiltered / Custom DNS) so WebView never
        // crashes with net::ERR_TUNNEL_CONNECTION_FAILED.
        return openDirectSocketWithDns(targetHost, targetPort, activeDns)
    }

    private fun handleConnectTunnel(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        authority: String
    ) {
        val (targetHost, targetPort) = parseHostPort(authority, defaultPort = 443)
        if (targetHost.isBlank() || targetPort !in 1..65535) {
            writeSimpleHttpError(clientOut, 400, "Bad Request")
            return
        }
        if (isLoopbackSelfTarget(targetHost, targetPort)) {
            writeSimpleHttpError(clientOut, 403, "Forbidden")
            return
        }

        // 1. Malware & Protection Shield enforcement at tunnel level (works with VPN extensions + DNS)
        if (malwareValidator("https://$targetHost/", targetHost)) {
            writeSimpleHttpError(clientOut, 403, "Blocked by Malware & Protection Shield")
            return
        }

        // 2. Custom DNS & AdBlocker DNS enforcement at tunnel level
        val activeDns = dnsProvider()
        if (activeDns != null && activeDns.isAnyCustomDnsActive) {
            if (activeDns.isHostSinkholedForProxy(targetHost)) {
                writeSimpleHttpError(clientOut, 403, "Blocked by AdBlocker DNS")
                return
            }
            activeDns.prewarmHostAsync(targetHost)
        }

        val upstreamProxy = proxyProvider()?.takeIf { it.shouldProxyHost(targetHost) }
        val upstreamSocket: Socket = try {
            if (upstreamProxy != null) {
                openResilientUpstreamTunnel(upstreamProxy, targetHost, targetPort, activeDns)
            } else {
                openDirectSocketWithDns(targetHost, targetPort, activeDns)
            }
        } catch (_: UnknownHostException) {
            if (activeDns != null && activeDns.isHostSinkholedForProxy(targetHost)) {
                writeSimpleHttpError(clientOut, 403, "Blocked by DNS")
            } else {
                writeSimpleHttpError(clientOut, 502, "DNS Resolution Failed")
            }
            return
        } catch (_: Throwable) {
            writeSimpleHttpError(clientOut, 502, "Bad Gateway")
            return
        }

        try {
            upstreamSocket.tcpNoDelay = true
            client.soTimeout = TUNNEL_IDLE_TIMEOUT_MS
            upstreamSocket.soTimeout = TUNNEL_IDLE_TIMEOUT_MS

            clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            clientOut.flush()

            pipeBidirectional(client, clientIn, clientOut, upstreamSocket)
        } finally {
            runCatching { upstreamSocket.close() }
        }
    }

    private fun handlePlainHttpRequest(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        method: String,
        targetRaw: String,
        httpVersion: String,
        headers: List<Pair<String, String>>
    ) {
        val hostHeaderVal = headers.firstOrNull { it.first.equals("Host", ignoreCase = true) }?.second?.trim().orEmpty()
        val parsedUri = runCatching { URI(targetRaw) }.getOrNull()

        val targetHost: String
        val targetPort: Int
        val originPath: String

        if (parsedUri != null && !parsedUri.host.isNullOrBlank()) {
            targetHost = parsedUri.host.removePrefix("[").removeSuffix("]")
            targetPort = if (parsedUri.port > 0) parsedUri.port else 80
            val rawPath = parsedUri.rawPath.orEmpty().ifBlank { "/" }
            val rawQuery = parsedUri.rawQuery?.takeIf { it.isNotBlank() }
            originPath = if (rawQuery != null) "$rawPath?$rawQuery" else rawPath
        } else if (hostHeaderVal.isNotBlank()) {
            val (h, p) = parseHostPort(hostHeaderVal, defaultPort = 80)
            targetHost = h
            targetPort = p
            originPath = if (targetRaw.startsWith("/")) targetRaw else "/$targetRaw"
        } else {
            writeSimpleHttpError(clientOut, 400, "Bad Request")
            return
        }

        if (targetHost.isBlank() || targetPort !in 1..65535) {
            writeSimpleHttpError(clientOut, 400, "Bad Request")
            return
        }
        if (isLoopbackSelfTarget(targetHost, targetPort)) {
            writeSimpleHttpError(clientOut, 403, "Forbidden")
            return
        }

        // 1. Malware & Protection Shield enforcement at plain HTTP request level
        if (malwareValidator("http://$targetHost$originPath", targetHost)) {
            writeSimpleHttpError(clientOut, 403, "Blocked by Malware & Protection Shield")
            return
        }

        // 2. Custom DNS & AdBlocker DNS enforcement
        val activeDns = dnsProvider()
        if (activeDns != null && activeDns.isAnyCustomDnsActive) {
            if (activeDns.isHostSinkholedForProxy(targetHost)) {
                writeSimpleHttpError(clientOut, 403, "Blocked by AdBlocker DNS")
                return
            }
            activeDns.prewarmHostAsync(targetHost)
        }

        val upstreamProxy = proxyProvider()?.takeIf { it.shouldProxyHost(targetHost) }
        val isWebsocketUpgrade = headers.any { it.first.equals("Upgrade", ignoreCase = true) }
        var usedHttpUpstreamProxy = false
        var activeHttpProxyForHeaders: BrowserProxyProfile? = null

        val upstreamSocket: Socket = try {
            if (upstreamProxy != null &&
                (upstreamProxy.type == BrowserProxyType.HTTP || upstreamProxy.type == BrowserProxyType.HTTPS)
            ) {
                val latestActive = proxyProvider()
                val effectiveUser = upstreamProxy.username.ifBlank { latestActive?.username.orEmpty() }
                val effectivePass = if (upstreamProxy.username.isNotBlank()) upstreamProxy.password else (latestActive?.password.orEmpty())
                val mergedProxy = upstreamProxy.copy(username = effectiveUser, password = effectivePass)
                try {
                    val s = openHttpOrHttpsProxyBaseSocket(mergedProxy, activeDns)
                    usedHttpUpstreamProxy = true
                    activeHttpProxyForHeaders = mergedProxy
                    s
                } catch (_: Throwable) {
                    usedHttpUpstreamProxy = false
                    openDirectSocketWithDns(targetHost, targetPort, activeDns)
                }
            } else if (upstreamProxy != null) {
                openResilientUpstreamTunnel(upstreamProxy, targetHost, targetPort, activeDns)
            } else {
                openDirectSocketWithDns(targetHost, targetPort, activeDns)
            }
        } catch (_: UnknownHostException) {
            if (activeDns != null && activeDns.isHostSinkholedForProxy(targetHost)) {
                writeSimpleHttpError(clientOut, 403, "Blocked by DNS")
            } else {
                writeSimpleHttpError(clientOut, 502, "DNS Resolution Failed")
            }
            return
        } catch (_: Throwable) {
            writeSimpleHttpError(clientOut, 502, "Bad Gateway")
            return
        }

        try {
            upstreamSocket.tcpNoDelay = true
            upstreamSocket.soTimeout = TUNNEL_IDLE_TIMEOUT_MS
            client.soTimeout = TUNNEL_IDLE_TIMEOUT_MS

            val upstreamOut = upstreamSocket.getOutputStream()
            val upstreamIn = upstreamSocket.getInputStream()

            val outboundTarget = if (usedHttpUpstreamProxy) {
                if (targetRaw.startsWith("http://", ignoreCase = true)) {
                    targetRaw
                } else {
                    val hostFmt = if (targetHost.contains(':')) "[$targetHost]" else targetHost
                    val portSuffix = if (targetPort == 80) "" else ":$targetPort"
                    "http://$hostFmt$portSuffix$originPath"
                }
            } else {
                originPath
            }

            val reqBuf = StringBuilder(512)
            reqBuf.append(method).append(' ').append(outboundTarget).append(' ').append(httpVersion).append("\r\n")

            var sawHostHeader = false
            var contentLength = -1L
            var isChunked = false

            for ((k, v) in headers) {
                if (k.equals("Proxy-Connection", ignoreCase = true) ||
                    k.equals("Proxy-Authorization", ignoreCase = true)
                ) {
                    continue
                }
                if (k.equals("Connection", ignoreCase = true) && !isWebsocketUpgrade) {
                    continue
                }
                if (k.equals("Host", ignoreCase = true)) sawHostHeader = true
                if (k.equals("Content-Length", ignoreCase = true)) {
                    contentLength = v.trim().toLongOrNull() ?: -1L
                }
                if (k.equals("Transfer-Encoding", ignoreCase = true) && v.contains("chunked", ignoreCase = true)) {
                    isChunked = true
                }
                reqBuf.append(k).append(": ").append(v).append("\r\n")
            }

            if (!sawHostHeader) {
                val hostFmt = if (targetHost.contains(':')) "[$targetHost]" else targetHost
                val portPart = if (targetPort == 80) "" else ":$targetPort"
                reqBuf.append("Host: ").append(hostFmt).append(portPart).append("\r\n")
            }
            val proxyForAuth = activeHttpProxyForHeaders
            if (usedHttpUpstreamProxy && proxyForAuth != null && proxyForAuth.hasAuth()) {
                reqBuf.append("Proxy-Authorization: ").append(buildBasicAuthHeader(proxyForAuth.username, proxyForAuth.password)).append("\r\n")
            }
            if (!isWebsocketUpgrade) {
                reqBuf.append("Connection: close\r\n")
            }
            reqBuf.append("\r\n")

            upstreamOut.write(reqBuf.toString().toByteArray(StandardCharsets.ISO_8859_1))
            upstreamOut.flush()

            if (isWebsocketUpgrade) {
                pipeBidirectional(client, clientIn, clientOut, upstreamSocket)
            } else {
                // Forward request body if present (POST/PUT/PATCH), then stream response back
                if (contentLength > 0L) {
                    copyExactBytes(clientIn, upstreamOut, contentLength)
                    upstreamOut.flush()
                } else if (isChunked) {
                    copyChunkedBody(clientIn, upstreamOut)
                    upstreamOut.flush()
                }
                copyStream(upstreamIn, clientOut)
                runCatching { clientOut.flush() }
            }
        } finally {
            runCatching { upstreamSocket.close() }
        }
    }

    private fun pipeBidirectional(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        upstream: Socket
    ) {
        val upstreamIn = upstream.getInputStream()
        val upstreamOut = upstream.getOutputStream()

        val clientToUpstreamFuture = executor.submit {
            try {
                copyStream(clientIn, upstreamOut)
                runCatching { upstream.shutdownOutput() }
            } catch (_: Throwable) {
                runCatching { upstream.close() }
            }
        }

        try {
            copyStream(upstreamIn, clientOut)
            runCatching { client.shutdownOutput() }
        } catch (_: Throwable) {
            runCatching { client.close() }
        } finally {
            runCatching { clientToUpstreamFuture.get() }
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buf = ByteArray(PIPE_BUFFER_SIZE)
        while (true) {
            val read = input.read(buf)
            if (read == -1) break
            output.write(buf, 0, read)
            output.flush()
        }
    }

    private fun copyExactBytes(input: InputStream, output: OutputStream, totalBytes: Long) {
        val buf = ByteArray(PIPE_BUFFER_SIZE)
        var remaining = totalBytes
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read == -1) break
            output.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun copyChunkedBody(input: InputStream, output: OutputStream) {
        while (true) {
            val sizeLine = readCrLfLine(input) ?: break
            output.write(sizeLine.toByteArray(StandardCharsets.ISO_8859_1))
            output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            val chunkSize = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: 0L
            if (chunkSize == 0L) {
                // Copy optional trailers until blank line
                while (true) {
                    val trailer = readCrLfLine(input) ?: break
                    output.write(trailer.toByteArray(StandardCharsets.ISO_8859_1))
                    output.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                    if (trailer.isEmpty()) break
                }
                break
            }
            copyExactBytes(input, output, chunkSize)
            // Copy trailing CRLF after chunk payload
            val cr = input.read()
            val lf = input.read()
            if (cr != -1) output.write(cr)
            if (lf != -1) output.write(lf)
        }
    }

    private fun isLoopbackSelfTarget(host: String, targetPort: Int): Boolean {
        if (targetPort != port || port <= 0) return false
        val h = host.trim().lowercase(Locale.US).removePrefix("[").removeSuffix("]")
        return h == "127.0.0.1" || h == "localhost" || h == "::1"
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 12_000
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val TUNNEL_IDLE_TIMEOUT_MS = 180_000
        private const val PIPE_BUFFER_SIZE = 32 * 1024
        private const val MAX_HEADER_BYTES = 64 * 1024

        fun openDirectSocketWithDns(
            targetHost: String,
            targetPort: Int,
            activeDns: CloudflareWarpDns?
        ): Socket {
            val addresses = resolveTargetAddresses(targetHost, activeDns)
            if (addresses.isEmpty()) {
                throw UnknownHostException("No addresses resolved for $targetHost")
            }
            var lastError: Throwable? = null
            for (addr in addresses) {
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.connect(InetSocketAddress(addr, targetPort), CONNECT_TIMEOUT_MS)
                    return s
                } catch (err: Throwable) {
                    lastError = err
                    runCatching { s.close() }
                }
            }
            throw (lastError as? IOException) ?: IOException("Failed to connect to $targetHost:$targetPort", lastError)
        }

        fun resolveTargetAddresses(
            targetHost: String,
            activeDns: CloudflareWarpDns?
        ): List<InetAddress> {
            val clean = targetHost.trim().removePrefix("[").removeSuffix("]")
            if (CloudflareWarpDns.isIpLiteral(clean)) {
                return listOf(InetAddress.getByName(clean))
            }
            val resolved = if (activeDns != null && activeDns.isAnyCustomDnsActive) {
                activeDns.lookup(clean)
            } else {
                Dns.SYSTEM.lookup(clean)
            }
            return resolved.sortedBy { if (it is Inet4Address) 0 else 1 }
        }

        private fun resolveProxyHostAddresses(
            proxyHost: String,
            activeDns: CloudflareWarpDns?
        ): List<InetAddress> {
            val clean = proxyHost.trim().removePrefix("[").removeSuffix("]")
            if (CloudflareWarpDns.isIpLiteral(clean)) {
                return listOf(InetAddress.getByName(clean))
            }
            if (activeDns != null && activeDns.isAnyCustomDnsActive) {
                val viaCustom = runCatching { activeDns.lookup(clean) }.getOrNull()
                if (!viaCustom.isNullOrEmpty()) {
                    return viaCustom.sortedBy { if (it is Inet4Address) 0 else 1 }
                }
            }
            return Dns.SYSTEM.lookup(clean).sortedBy { if (it is Inet4Address) 0 else 1 }
        }

        private fun connectToProxyRawSocket(
            proxy: BrowserProxyProfile,
            activeDns: CloudflareWarpDns?
        ): Socket {
            val addrs = resolveProxyHostAddresses(proxy.host, activeDns)
            var lastErr: Throwable? = null
            for (addr in addrs) {
                val s = Socket()
                try {
                    s.tcpNoDelay = true
                    s.soTimeout = HANDSHAKE_TIMEOUT_MS
                    s.connect(InetSocketAddress(addr, proxy.port), CONNECT_TIMEOUT_MS)
                    return s
                } catch (err: Throwable) {
                    lastErr = err
                    runCatching { s.close() }
                }
            }
            throw (lastErr as? IOException) ?: IOException("Unable to reach proxy ${proxy.toDisplayEndpoint()}", lastErr)
        }

        fun openHttpOrHttpsProxyBaseSocket(
            proxy: BrowserProxyProfile,
            activeDns: CloudflareWarpDns?
        ): Socket {
            val raw = connectToProxyRawSocket(proxy, activeDns)
            if (proxy.type != BrowserProxyType.HTTPS) {
                return raw
            }
            return try {
                val cleanHost = proxy.host.trim().removePrefix("[").removeSuffix("]")
                val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val sslSocket = sslFactory.createSocket(raw, cleanHost, proxy.port, true) as SSLSocket
                sslSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
                if (!CloudflareWarpDns.isIpLiteral(cleanHost)) {
                    runCatching {
                        val params = sslSocket.sslParameters
                        params.serverNames = listOf(SNIHostName(cleanHost))
                        sslSocket.sslParameters = params
                    }
                }
                sslSocket.startHandshake()
                sslSocket
            } catch (err: Throwable) {
                runCatching { raw.close() }
                throw err
            }
        }

        /**
         * Opens an end-to-end TCP tunnel to [targetHost]:[targetPort] through ANY supported
         * upstream proxy type: HTTP, HTTPS (TLS), SOCKS5 (with optional RFC 1929 auth & remote DNS),
         * or SOCKS4/SOCKS4a.
         */
        fun openUpstreamProxyTunnel(
            proxy: BrowserProxyProfile,
            targetHost: String,
            targetPort: Int,
            activeDns: CloudflareWarpDns?
        ): Socket {
            return when (proxy.type) {
                BrowserProxyType.HTTP, BrowserProxyType.HTTPS -> {
                    openHttpConnectTunnel(proxy, targetHost, targetPort, activeDns)
                }
                BrowserProxyType.SOCKS5 -> {
                    openSocks5Tunnel(proxy, targetHost, targetPort, activeDns)
                }
                BrowserProxyType.SOCKS4 -> {
                    openSocks4Tunnel(proxy, targetHost, targetPort, activeDns)
                }
            }
        }

        private fun openHttpConnectTunnel(
            proxy: BrowserProxyProfile,
            targetHost: String,
            targetPort: Int,
            activeDns: CloudflareWarpDns?
        ): Socket {
            val socket = openHttpOrHttpsProxyBaseSocket(proxy, activeDns)
            try {
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                val formattedTarget = if (targetHost.contains(':')) "[$targetHost]" else targetHost
                val connectReq = buildString {
                    append("CONNECT ").append(formattedTarget).append(':').append(targetPort).append(" HTTP/1.1\r\n")
                    append("Host: ").append(formattedTarget).append(':').append(targetPort).append("\r\n")
                    if (proxy.hasAuth()) {
                        append("Proxy-Authorization: ").append(buildBasicAuthHeader(proxy.username, proxy.password)).append("\r\n")
                    }
                    append("Proxy-Connection: keep-alive\r\n\r\n")
                }
                out.write(connectReq.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()

                val respHeaders = readHttpHeaderBlock(inp)
                    ?: throw IOException("Proxy closed connection during CONNECT handshake")
                val statusLine = String(respHeaders, StandardCharsets.ISO_8859_1).substringBefore("\r\n")
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (statusCode !in 200..299) {
                    throw IOException("Upstream ${proxy.type.label} proxy rejected CONNECT: $statusLine")
                }
                return socket
            } catch (err: Throwable) {
                runCatching { socket.close() }
                throw err
            }
        }

        private fun openSocks5Tunnel(
            proxy: BrowserProxyProfile,
            targetHost: String,
            targetPort: Int,
            activeDns: CloudflareWarpDns?
        ): Socket {
            val socket = connectToProxyRawSocket(proxy, activeDns)
            try {
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                // 1. Greeting
                val greeting = if (proxy.hasAuth()) {
                    byteArrayOf(0x05, 0x02, 0x00, 0x02) // No-Auth + Username/Password
                } else {
                    byteArrayOf(0x05, 0x01, 0x00)
                }
                out.write(greeting)
                out.flush()

                val ver = inp.read()
                val method = inp.read()
                if (ver != 0x05) {
                    throw IOException("Invalid SOCKS5 version reply: $ver")
                }
                when (method) {
                    0x00 -> Unit
                    0x02 -> {
                        if (!proxy.hasAuth()) {
                            throw IOException("SOCKS5 proxy requires authentication")
                        }
                        val uBytes = proxy.username.toByteArray(StandardCharsets.UTF_8).take(255).toByteArray()
                        val pBytes = proxy.password.toByteArray(StandardCharsets.UTF_8).take(255).toByteArray()
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
                        if (authVer != 0x01 || authStatus != 0x00) {
                            throw IOException("SOCKS5 username/password authentication rejected (status=$authStatus)")
                        }
                    }
                    else -> throw IOException("SOCKS5 proxy rejected authentication method: $method")
                }

                // 2. CONNECT Request (prefer remote domain ATYP=0x03 for zero DNS leaks)
                val cleanHost = targetHost.trim().removePrefix("[").removeSuffix("]")
                val req = ByteArrayOutputStream(64)
                req.write(0x05) // VER
                req.write(0x01) // CMD = CONNECT
                req.write(0x00) // RSV

                val ipv4Bytes = CloudflareWarpDns.parseIpv4Bytes(cleanHost)
                if (ipv4Bytes != null) {
                    req.write(0x01) // IPv4
                    req.write(ipv4Bytes)
                } else if (cleanHost.contains(':')) {
                    val v6 = InetAddress.getByName(cleanHost)
                    if (v6 is Inet6Address) {
                        req.write(0x04) // IPv6
                        req.write(v6.address)
                    } else {
                        val hostBytes = cleanHost.toByteArray(StandardCharsets.UTF_8)
                        req.write(0x03)
                        req.write(hostBytes.size)
                        req.write(hostBytes)
                    }
                } else {
                    val hostBytes = cleanHost.toByteArray(StandardCharsets.UTF_8)
                    if (hostBytes.size > 255) throw IOException("Hostname too long for SOCKS5")
                    req.write(0x03) // DOMAINNAME (SOCKS5h)
                    req.write(hostBytes.size)
                    req.write(hostBytes)
                }
                req.write((targetPort shr 8) and 0xFF)
                req.write(targetPort and 0xFF)
                out.write(req.toByteArray())
                out.flush()

                // 3. Read CONNECT reply
                val repHeader = ByteArray(4)
                readFully(inp, repHeader)
                if (repHeader[0].toInt() != 0x05 || repHeader[1].toInt() != 0x00) {
                    throw IOException("SOCKS5 CONNECT failed (rep=${repHeader[1].toInt() and 0xFF})")
                }
                when (repHeader[3].toInt() and 0xFF) {
                    0x01 -> readFully(inp, ByteArray(4 + 2)) // IPv4 + port
                    0x04 -> readFully(inp, ByteArray(16 + 2)) // IPv6 + port
                    0x03 -> {
                        val len = inp.read()
                        if (len < 0) throw EOFException("Unexpected EOF reading SOCKS5 bind domain length")
                        readFully(inp, ByteArray(len + 2))
                    }
                    else -> throw IOException("Unknown SOCKS5 reply ATYP: ${repHeader[3]}")
                }
                return socket
            } catch (err: Throwable) {
                runCatching { socket.close() }
                throw err
            }
        }

        private fun openSocks4Tunnel(
            proxy: BrowserProxyProfile,
            targetHost: String,
            targetPort: Int,
            activeDns: CloudflareWarpDns?
        ): Socket {
            val cleanHost = targetHost.trim().removePrefix("[").removeSuffix("]")
            val directIpv4 = CloudflareWarpDns.parseIpv4Bytes(cleanHost)
            if (directIpv4 != null) {
                return performSocks4Handshake(proxy, directIpv4, null, targetPort, activeDns)
            }
            // Try SOCKS4a first (remote DNS resolution via 0.0.0.1 sentinel)
            return try {
                performSocks4Handshake(proxy, byteArrayOf(0, 0, 0, 1), cleanHost, targetPort, activeDns)
            } catch (_: Throwable) {
                // Fallback to standard SOCKS4 by resolving IPv4 via active DNS
                val ipv4 = resolveTargetAddresses(cleanHost, activeDns)
                    .firstOrNull { it is Inet4Address }?.address
                    ?: throw UnknownHostException("SOCKS4 requires IPv4 address for $cleanHost")
                performSocks4Handshake(proxy, ipv4, null, targetPort, activeDns)
            }
        }

        private fun performSocks4Handshake(
            proxy: BrowserProxyProfile,
            ipBytes: ByteArray,
            socks4aHostname: String?,
            targetPort: Int,
            activeDns: CloudflareWarpDns?
        ): Socket {
            val socket = connectToProxyRawSocket(proxy, activeDns)
            try {
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                val req = ByteArrayOutputStream(64)
                req.write(0x04) // VN = 4
                req.write(0x01) // CD = CONNECT
                req.write((targetPort shr 8) and 0xFF)
                req.write(targetPort and 0xFF)
                req.write(ipBytes)
                if (proxy.username.isNotBlank()) {
                    req.write(proxy.username.toByteArray(StandardCharsets.UTF_8))
                }
                req.write(0x00) // NULL terminator for USERID
                if (!socks4aHostname.isNullOrBlank()) {
                    req.write(socks4aHostname.toByteArray(StandardCharsets.UTF_8))
                    req.write(0x00) // NULL terminator for SOCKS4a domain
                }
                out.write(req.toByteArray())
                out.flush()

                val reply = ByteArray(8)
                readFully(inp, reply)
                if ((reply[1].toInt() and 0xFF) != 0x5A) {
                    throw IOException("SOCKS4 CONNECT rejected (status=${reply[1].toInt() and 0xFF})")
                }
                return socket
            } catch (err: Throwable) {
                runCatching { socket.close() }
                throw err
            }
        }

        internal fun buildBasicAuthHeader(username: String, password: String): String {
            val raw = "$username:$password".toByteArray(StandardCharsets.UTF_8)
            val encoded = try {
                Base64.getEncoder().encodeToString(raw)
            } catch (_: Throwable) {
                android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP)
            }
            return "Basic $encoded"
        }

        internal fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int> {
            val trimmed = authority.trim()
            if (trimmed.startsWith("[")) {
                val endBracket = trimmed.indexOf(']')
                if (endBracket > 1) {
                    val host = trimmed.substring(1, endBracket)
                    val rest = trimmed.substring(endBracket + 1)
                    val p = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
                    return host to p
                }
            }
            val colon = trimmed.lastIndexOf(':')
            return if (colon > 0 && trimmed.indexOf(':') == colon) {
                val host = trimmed.substring(0, colon).trim()
                val p = trimmed.substring(colon + 1).trim().toIntOrNull() ?: defaultPort
                host to p
            } else {
                trimmed to defaultPort
            }
        }

        private fun readHttpHeaderBlock(input: InputStream): ByteArray? {
            val out = ByteArrayOutputStream(512)
            var state = 0 // matching \r\n\r\n
            while (out.size() < MAX_HEADER_BYTES) {
                val b = input.read()
                if (b == -1) {
                    return if (out.size() == 0) null else out.toByteArray()
                }
                out.write(b)
                state = when (state) {
                    0 -> if (b == '\r'.code) 1 else 0
                    1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                    2 -> if (b == '\r'.code) 3 else 0
                    3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                    else -> 0
                }
                if (state == 4) {
                    return out.toByteArray()
                }
            }
            return null
        }

        private fun readCrLfLine(input: InputStream): String? {
            val out = ByteArrayOutputStream(64)
            var prev = -1
            while (out.size() < 8192) {
                val b = input.read()
                if (b == -1) return if (out.size() == 0) null else String(out.toByteArray(), StandardCharsets.ISO_8859_1)
                if (b == '\n'.code) {
                    val bytes = out.toByteArray()
                    val len = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                    return String(bytes, 0, len, StandardCharsets.ISO_8859_1)
                }
                out.write(b)
                prev = b
            }
            return null
        }

        private fun parseHeaders(lines: List<String>): List<Pair<String, String>> {
            val result = ArrayList<Pair<String, String>>(lines.size)
            for (line in lines) {
                if (line.isBlank()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    result.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
                }
            }
            return result
        }

        private fun readFully(input: InputStream, dest: ByteArray) {
            var offset = 0
            while (offset < dest.size) {
                val read = input.read(dest, offset, dest.size - offset)
                if (read == -1) throw EOFException("Unexpected EOF while reading proxy response")
                offset += read
            }
        }

        private fun writeSimpleHttpError(out: OutputStream, code: Int, message: String) {
            runCatching {
                val resp = "HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                out.write(resp.toByteArray(StandardCharsets.ISO_8859_1))
                out.flush()
            }
        }
    }
}
