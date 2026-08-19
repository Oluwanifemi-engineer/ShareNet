package com.sharenet.app.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A small, dependency-free HTTP proxy server — the heart of ShareNet.
 *
 * Clients (laptops, phones, tablets) join the phone's Wi-Fi Direct network and
 * point their HTTP proxy at this server. The proxy then forwards their traffic
 * over the phone's real upstream connection (Wi-Fi or cellular).
 *
 * Supported:
 *  - Plain HTTP requests (absolute-form or origin-form + Host) with keep-alive.
 *  - HTTPS via the CONNECT method (bidirectional byte tunnel).
 *  - Content-Length and chunked request/response bodies.
 *
 * Deliberately NOT supported (by design, v1): UDP, raw TCP without CONNECT,
 * proxy authentication. That is the "proxy-aware apps only" limitation of the
 * whole Tier-1 approach.
 *
 * Pure JVM — no Android imports — so it is unit-tested on the plain JVM.
 */
class HttpProxyServer(
    private val bindHost: String,
    private val port: Int,
    private val stats: ProxyStats,
    private val destinationPolicy: DestinationPolicy = DestinationPolicy.STRICT,
    private val captivePortalEnabled: Boolean = false,
    private val hotspotMode: Boolean = false,
    private val log: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var acceptSocket: ServerSocket? = null
    private var captivePortalSocket: ServerSocket? = null
    private val clientSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    private val executor = ThreadPoolExecutor(
        0,
        MAX_CONNECTIONS,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue(),
    ) { r -> Thread(r, "sharenet-proxy").apply { isDaemon = true } }

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindHost, port))
            acceptSocket = ss
            boundPort = ss.localPort
            log("proxy listening on $bindHost:$boundPort")
            Thread { acceptLoop(ss) }
                .apply { name = "sharenet-proxy-accept"; isDaemon = true }
                .start()


        } catch (e: IOException) {
            running.set(false)
            throw ProxyBindException("bind failed on $bindHost:$port", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        try {
            acceptSocket?.close()
        } catch (_: IOException) {
        }
        acceptSocket = null
        try {
            captivePortalSocket?.close()
        } catch (_: IOException) {}
        captivePortalSocket = null
        synchronized(clientSockets) {
            clientSockets.toList().forEach { runCatching { it.close() } }
            clientSockets.clear()
        }
        executor.shutdownNow()
        log("proxy stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val socket = ss.accept()
                if (!running.get()) {
                    runCatching { socket.close() }
                    break
                }
                stats.connectionsAccepted.incrementAndGet()
                stats.activeConnections.incrementAndGet()
                clientSockets.add(socket)
                try {
                    executor.execute { handleClient(socket) }
                } catch (e: RejectedExecutionException) {
                    runCatching { socket.close() }
                    clientSockets.remove(socket)
                    stats.activeConnections.decrementAndGet()
                }
            } catch (e: IOException) {
                if (running.get()) log("accept failed: ${e.message}")
            }
        }
    }

    /**
     * Captive portal loop on port 80. OSes probe known URLs on port 80 to
     * detect captive portals. We redirect every request to the setup page
     * on port 8080.
     */
    private fun captivePortalLoop(ss: ServerSocket) {
        val setupUrl = "http://$bindHost:$boundPort/setup"
        val redirect = (
            "HTTP/1.1 302 Found\r\n" +
            "Location: $setupUrl\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
            ).toByteArray(Charsets.ISO_8859_1)
        while (running.get()) {
            try {
                val socket = ss.accept()
                if (!running.get()) { runCatching { socket.close() }; break }
                Thread {
                    try {
                        socket.soTimeout = 5000
                        val inp = socket.getInputStream()
                        // Read the request line to drain the socket
                        val buf = ByteArray(2048)
                        inp.read(buf)
                        // Send redirect to setup page
                        socket.getOutputStream().write(redirect)
                        socket.getOutputStream().flush()
                    } catch (_: Exception) {
                    } finally {
                        runCatching { socket.close() }
                    }
                }.apply { isDaemon = true; start() }
            } catch (e: IOException) {
                if (running.get()) log("captive portal accept failed: ${e.message}")
            }
        }
    }

    /** SOCKS5 handler, inlined from Socks5ProxyServer for unified port. */
    private val socks5Handler = Socks5InlineHandler(stats, destinationPolicy) { log("socks5: $it") }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)

            // Protocol auto-detection: peek first byte
            // SOCKS5 starts with 0x05; HTTP starts with ASCII letters (G/C/P/O/H etc.)
            val firstByte = input.read()
            if (firstByte < 0) return
            if (firstByte == 0x05) {
                // SOCKS5 protocol — route to inline handler
                stats.activeConnections.decrementAndGet() // SOCKS5 handler tracks its own
                socks5Handler.handleClient(socket, firstByte, input, output)
                return
            }
            // HTTP protocol — reconstruct by wrapping first byte back
            val wrappedInput = FirstByteInputStream(firstByte, input)
            serve(wrappedInput, output)
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
            clientSockets.remove(socket)
            stats.activeConnections.decrementAndGet()
        }
    }

    private fun serve(input: InputStream, output: OutputStream) {
        var keepAlive = true
        while (keepAlive && running.get()) {
            val head = readHead(input) ?: return
            val request = parseRequest(head)
            if (request == null) {
                writeSimpleResponse(output, 400, "Bad Request")
                return
            }
            when (request.kind) {
                RequestKind.CONNECT -> {
                    handleConnect(request, input, output)
                    return
                }
                RequestKind.PLAIN -> {
                    if (captivePortalEnabled) {
                        keepAlive = handleCaptivePortal(request, input, output)
                    } else {
                        keepAlive = handleHttp(request, input, output)
                    }
                }
            }
        }
    }

    // ── Captive portal ────────────────────────────────────────────────────

    private fun handleCaptivePortal(
        request: Request,
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        val path = request.path
        val host = request.host
        val portNum = boundPort
        val proxyAddr = "$host:$portNum"

        if (request.absoluteTarget) {
            return handleHttp(request, input, output)
        }

        val lowerPath = path.lowercase()
        when {
            lowerPath == "/proxy.pac" || lowerPath.endsWith("/proxy.pac?") -> {
                servePacFile(output, host, portNum)
            }
            lowerPath == "/setup" || lowerPath.endsWith("/setup?") -> {
                serveSetupPage(output, proxyAddr)
            }
            else -> {
                val setupUrl = "http://$bindHost:$portNum/setup"
                runCatching {
                    output.write("HTTP/1.1 302 Found\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.write("Location: $setupUrl\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.write("Content-Length: 0\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.write("Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                }
            }
        }
        return false
    }

    private fun servePacFile(output: OutputStream, host: String, port: Int) {
        val pac = """
            |function FindProxyForURL(url, host) {
            |    return "PROXY $host:$port";
            |}
        """.trimMargin()
        val body = pac.toByteArray(Charsets.UTF_8)
        runCatching {
            output.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Content-Type: application/x-ns-proxy-autoconfig\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Content-Length: ${body.size}\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        }
    }

    private fun serveSetupPage(output: OutputStream, proxyAddr: String) {
        val pacUrl = "http://$proxyAddr/proxy.pac"
        val html = SETUP_PAGE_HTML
            .replace("{{PROXY_ADDR}}", proxyAddr)
            .replace("{{PAC_URL}}", pacUrl)
            .replace("{{PROXY_HOST}}", proxyAddr.substringBefore(':'))
            .replace("{{PROXY_PORT}}", proxyAddr.substringAfter(':'))
            .replace("{{HOTSPOT_MODE}}", if (hotspotMode) "true" else "false")
        val body = html.toByteArray(Charsets.UTF_8)
        runCatching {
            output.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Content-Length: ${body.size}\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write("Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        }
    }

    // ── Request parsing ─────────────────────────────────────────────────────

    private enum class RequestKind { PLAIN, CONNECT }

    private class Request(
        val kind: RequestKind,
        val method: String,
        val host: String,
        val port: Int,
        val path: String,
        val httpVersion: String,
        val headers: List<Pair<String, String>>,
        val keepAlive: Boolean,
        val contentLength: Long,
        val chunked: Boolean,
        val absoluteTarget: Boolean = false,
    )

    private fun parseRequest(head: List<String>): Request? {
        val requestLine = head.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts[2]

        val headers = ArrayList<Pair<String, String>>(head.size - 1)
        for (i in 1 until head.size) {
            val idx = head[i].indexOf(':')
            if (idx <= 0) continue
            headers.add(
                head[i].substring(0, idx).trim() to
                    head[i].substring(idx + 1).trim(),
            )
        }

        val connectionTokens = headers
            .firstOrNull { it.first.equals("connection", ignoreCase = true) }?.second?.lowercase()
            ?.split(",")?.map { it.trim() } ?: emptyList()
        val keepAlive = if (version == "HTTP/1.0") {
            connectionTokens.contains("keep-alive")
        } else {
            !connectionTokens.contains("close")
        }
        val contentLength = headers
            .firstOrNull { it.first.equals("content-length", ignoreCase = true) }
            ?.second?.toLongOrNull() ?: 0L
        val chunked = headers.any {
            it.first.equals("transfer-encoding", ignoreCase = true) &&
                it.second.lowercase().contains("chunked")
        }

        if (method == "CONNECT") {
            val hostPort = target.split(":")
            val host = hostPort[0].ifEmpty { return null }
            val port = hostPort.getOrNull(1)?.toIntOrNull() ?: 443
            return Request(
                RequestKind.CONNECT, method, host, port, "", version,
                headers, keepAlive, 0L, false,
            )
        }

        var host: String
        var port: Int
        var path: String
        if (target.startsWith("http://") || target.startsWith("https://")) {
            val uri = try {
                URI(target)
            } catch (e: Exception) {
                return null
            }
            host = uri.host ?: return null
            port = when {
                uri.port > 0 -> uri.port
                uri.scheme == "https" -> 443
                else -> 80
            }
            path = if (uri.rawPath.isNullOrEmpty()) "/" else {
                uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
            }
            return Request(
                RequestKind.PLAIN, method, host, port, path, version,
                headers, keepAlive, contentLength, chunked,
                absoluteTarget = true,
            )
        } else {
            val hostHeader = headers
                .firstOrNull { it.first.equals("host", ignoreCase = true) }?.second
                ?: return null
            if (hostHeader.startsWith("[")) {
                val close = hostHeader.indexOf(']')
                if (close < 0) return null
                host = hostHeader.substring(0, close + 1)
                port = hostHeader.substring(close + 1).removePrefix(":").toIntOrNull() ?: 80
            } else {
                val colon = hostHeader.lastIndexOf(':')
                if (colon > 0 && hostHeader.indexOf(':') == colon) {
                    host = hostHeader.substring(0, colon)
                    port = hostHeader.substring(colon + 1).toIntOrNull() ?: 80
                } else {
                    host = hostHeader
                    port = 80
                }
            }
            path = target
        }
        if (host.isEmpty()) return null

        return Request(
            RequestKind.PLAIN, method, host, port, path, version,
            headers, keepAlive, contentLength, chunked,
        )
    }

    // ── Plain HTTP forwarding ───────────────────────────────────────────────

    private fun handleHttp(req: Request, input: InputStream, output: OutputStream): Boolean {
        if (!destinationPolicy.allow(req.host)) {
            writeSimpleResponse(output, 403, "Forbidden")
            return false
        }

        // Detect WebSocket upgrade: preserve Connection/Upgrade headers and switch to pump mode
        val isWebSocket = req.headers.any { it.first.equals("upgrade", ignoreCase = true) && it.second.lowercase().contains("websocket") }

        val origin = Socket()
        try {
            origin.connect(InetSocketAddress(req.host, req.port), CONNECT_TIMEOUT_MS)
            origin.soTimeout = IDLE_TIMEOUT_MS
            origin.tcpNoDelay = true
            val originInput = BufferedInputStream(origin.getInputStream(), BUFFER)
            val originOutput = BufferedOutputStream(origin.getOutputStream(), BUFFER)

            val head = StringBuilder()
            head.append(req.method).append(' ').append(req.path).append(" HTTP/1.1\r\n")
            var hasHost = false
            for ((name, value) in req.headers) {
                val lower = name.lowercase()
                // For WebSocket: preserve Connection and Upgrade headers
                if (isHopByHop(lower) && !(isWebSocket && (lower == "connection" || lower == "upgrade"))) continue
                if (lower == "transfer-encoding" && !req.chunked) continue
                if (lower == "host") hasHost = true
                head.append(name).append(": ").append(value).append("\r\n")
            }
            if (!hasHost) head.append("Host: ").append(req.host).append("\r\n")
            if (isWebSocket) {
                head.append("Connection: Upgrade\r\n")
                head.append("Upgrade: websocket\r\n")
            } else {
                head.append("Connection: close\r\n")
            }
            head.append("\r\n")
            originOutput.write(head.toString().toByteArray(Charsets.ISO_8859_1))

            when {
                req.chunked -> forwardChunked(input, originOutput, stats.bytesFromClients)
                req.contentLength > 0L -> copyN(input, originOutput, req.contentLength, stats.bytesFromClients)
            }
            originOutput.flush()

            val responseHead = readHead(originInput) ?: return false
            if (responseHead.isEmpty()) return false
            val responseHeaders = ArrayList<Pair<String, String>>(responseHead.size - 1)
            for (i in 1 until responseHead.size) {
                val idx = responseHead[i].indexOf(':')
                if (idx <= 0) continue
                responseHeaders.add(
                    responseHead[i].substring(0, idx).trim().lowercase() to
                        responseHead[i].substring(idx + 1).trim(),
                )
            }
            val contentLength = responseHeaders
                .firstOrNull { it.first.equals("content-length", ignoreCase = true) }
                ?.second?.toLongOrNull()
            val responseChunked = responseHeaders.any {
                it.first.equals("transfer-encoding", ignoreCase = true) &&
                    it.second.lowercase().contains("chunked")
            }
            val isHead = req.method == "HEAD"

            val outHead = StringBuilder()
            outHead.append(responseHead[0]).append("\r\n")
            for ((name, value) in responseHeaders) {
                val lower = name.lowercase()
                if (isHopByHop(lower) && !(lower == "transfer-encoding" && responseChunked)) continue
                outHead.append(name).append(": ").append(value).append("\r\n")
            }
            outHead.append("\r\n")
            output.write(outHead.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()

            // WebSocket upgrade: after 101, switch to bidirectional pump
            val isUpgrade = responseHead[0].contains("101")
            if (isWebSocket && isUpgrade) {
                val done = AtomicBoolean(false)
                val upstream = Thread {
                    runCatching { pump(input, origin.getOutputStream(), stats.bytesFromClients, done) }
                }
                val downstream = Thread {
                    runCatching { pump(originInput, output, stats.bytesToClients, done) }
                }
                upstream.start()
                downstream.start()
                upstream.join()
                downstream.join()
                return false
            }

            return when {
                isHead -> req.keepAlive
                responseChunked -> {
                    forwardChunked(originInput, output, stats.bytesToClients)
                    output.flush()
                    req.keepAlive
                }
                contentLength != null -> {
                    copyN(originInput, output, contentLength, stats.bytesToClients)
                    output.flush()
                    req.keepAlive
                }
                else -> {
                    copyUntilEof(originInput, output, stats.bytesToClients)
                    output.flush()
                    false
                }
            }
        } catch (_: Exception) {
            return false
        } finally {
            runCatching { origin.close() }
        }
    }

    // ── CONNECT tunneling ───────────────────────────────────────────────────

    private fun handleConnect(req: Request, input: InputStream, output: OutputStream) {
        if (!destinationPolicy.allow(req.host)) {
            runCatching {
                output.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
            return
        }
        val origin = Socket()
        try {
            // Use DNS cache for faster lookups
            val cachedAddr = dnsCache[req.host] ?: runCatching {
                java.net.InetAddress.getByName(req.host)
            }.getOrNull()?.also { dnsCache[req.host] = it }
            val addr = cachedAddr ?: java.net.InetAddress.getByName(req.host)
            origin.connect(java.net.InetSocketAddress(addr, req.port), CONNECT_TIMEOUT_MS)
            origin.soTimeout = IDLE_TIMEOUT_MS
            origin.tcpNoDelay = true
            // Larger socket buffers for WiFi throughput
            runCatching {
                origin.sendBufferSize = SOCKET_BUFFER_SIZE
                origin.receiveBufferSize = SOCKET_BUFFER_SIZE
            }
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()

            val done = AtomicBoolean(false)
            val upstream = Thread {
                runCatching { pump(input, origin.getOutputStream(), stats.bytesFromClients, done) }
            }
            val downstream = Thread {
                runCatching { pump(origin.getInputStream(), output, stats.bytesToClients, done) }
            }
            upstream.start()
            downstream.start()
            // Wait indefinitely — let pump threads run as long as the
            // connection is alive. Idle detection handled by socket soTimeout.
            upstream.join()
            downstream.join()
        } catch (_: Exception) {
            runCatching {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
        } finally {
            runCatching { origin.close() }
        }
    }

    private fun pump(from: InputStream, to: OutputStream, counter: AtomicLong, done: AtomicBoolean) {
        val buf = ByteArray(BUFFER)
        try {
            while (!done.get()) {
                val n = from.read(buf)
                if (n < 0) break
                if (n > 0) {
                    to.write(buf, 0, n)
                    to.flush()
                    counter.addAndGet(n.toLong())
                }
            }
        } catch (_: Exception) {
        } finally {
            done.set(true)
        }
    }

    // ── Stream helpers ──────────────────────────────────────────────────────

    private fun copyN(from: InputStream, to: OutputStream, length: Long, counter: AtomicLong) {
        val buf = ByteArray(BUFFER)
        var remaining = length
        while (remaining > 0L) {
            val n = from.read(buf, 0, minOf(BUFFER.toLong(), remaining).toInt())
            if (n < 0) return
            to.write(buf, 0, n)
            counter.addAndGet(n.toLong())
            remaining -= n
        }
    }

    private fun copyUntilEof(from: InputStream, to: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = from.read(buf)
            if (n < 0) return
            to.write(buf, 0, n)
            to.flush()
            counter.addAndGet(n.toLong())
        }
    }

    private fun forwardChunked(from: InputStream, to: OutputStream, counter: AtomicLong) {
        while (true) {
            val sizeLine = readLine(from) ?: return
            val size = sizeLine.substringBefore(';').trim().toLongOrNull() ?: return
            writeLine(to, sizeLine)
            counter.addAndGet(sizeLine.length.toLong() + 2)
            if (size == 0L) {
                while (true) {
                    val trailer = readLine(from) ?: return
                    writeLine(to, trailer)
                    counter.addAndGet(trailer.length.toLong() + 2)
                    if (trailer.isEmpty()) return
                }
            }
            copyN(from, to, size, counter)
            writeLine(to, "")
            counter.addAndGet(2L)
        }
    }

    private fun readHead(input: InputStream): List<String>? {
        val lines = ArrayList<String>(16)
        var total = 0
        while (true) {
            val line = readLine(input) ?: return null
            total += line.length + 1
            if (total > MAX_HEAD_SIZE) return null
            if (line.isEmpty()) return lines
            lines.add(line)
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArray(MAX_LINE_SIZE)
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (count == 0) null else String(buf, 0, count, Charsets.ISO_8859_1)
            if (b == '\n'.code) {
                val end = if (count > 0 && buf[count - 1] == '\r'.code.toByte()) count - 1 else count
                return String(buf, 0, end, Charsets.ISO_8859_1)
            }
            if (count >= MAX_LINE_SIZE) return null
            buf[count] = b.toByte()
            count++
        }
    }

    private fun writeLine(to: OutputStream, line: String) {
        to.write(line.toByteArray(Charsets.ISO_8859_1))
        to.write(CRLF)
    }

    private fun writeSimpleResponse(output: OutputStream, code: Int, reason: String) {
        val body = "$reason\n"
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ").append(body.length).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(body.toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    private fun isHopByHop(name: String): Boolean = name.lowercase() in HOP_BY_HOP_HEADERS

    companion object {
        private const val BUFFER = 64 * 1024
        private const val MAX_CONNECTIONS = 64
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TUNNEL_JOIN_TIMEOUT_MS = 130_000L
        private const val MAX_LINE_SIZE = 16 * 1024
        private const val MAX_HEAD_SIZE = 64 * 1024
        private const val SOCKET_BUFFER_SIZE = 256 * 1024  // 256 KB — optimised for WiFi

        /** Simple DNS cache to avoid repeated lookups for the same host. */
        private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, java.net.InetAddress>()

        private val CRLF = byteArrayOf(0x0D, 0x0A)

        private val HOP_BY_HOP_HEADERS = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            // NOTE: 'upgrade' is NOT here — it must be forwarded for WebSocket support
        )

        private val SETUP_PAGE_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<title>ShareNet — Connect</title>
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><rect width='100' height='100' rx='20' fill='%2310b981'/><text y='68' x='50' text-anchor='middle' font-size='52' font-family='system-ui' font-weight='700' fill='white'>S</text></svg>">
<style>
:root {
  --bg: #0a0a0a;
  --surface: #141414;
  --surface-2: #1c1c1c;
  --border: #262626;
  --border-focus: #404040;
  --text: #fafafa;
  --text-2: #a1a1a1;
  --text-3: #737373;
  --accent: #10b981;
  --accent-dim: rgba(16,185,129,0.12);
  --radius: 12px;
  --mono: 'SF Mono', 'Cascadia Code', 'JetBrains Mono', 'Fira Code', ui-monospace, monospace;
}
* { box-sizing: border-box; margin: 0; padding: 0; }
html { -webkit-font-smoothing: antialiased; }
body {
  font-family: -apple-system, 'Inter', 'Segoe UI', system-ui, sans-serif;
  background: var(--bg);
  color: var(--text);
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
}
.container { width: 100%; max-width: 480px; }

/* Header */
.header { text-align: center; margin-bottom: 40px; }
.brand { display: inline-flex; align-items: center; gap: 10px; margin-bottom: 20px; }
.brand-icon {
  width: 36px; height: 36px; border-radius: 10px;
  background: linear-gradient(135deg, #10b981 0%, #059669 100%);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: 800; color: #fff;
  box-shadow: 0 2px 12px rgba(16,185,129,0.25);
}
.brand-name {
  font-size: 17px; font-weight: 700; letter-spacing: -0.02em;
  color: var(--text);
}
.status-badge {
  display: inline-flex; align-items: center; gap: 7px;
  padding: 6px 14px; border-radius: 100px;
  background: var(--accent-dim);
  border: 1px solid rgba(16,185,129,0.2);
  font-size: 12px; font-weight: 600; color: var(--accent);
  margin-bottom: 20px;
}
.status-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--accent);
  animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:0.4} }
.header h1 {
  font-size: 26px; font-weight: 800; letter-spacing: -0.03em;
  line-height: 1.2; margin-bottom: 8px;
}
.header p { font-size: 14px; color: var(--text-3); line-height: 1.5; }

/* Proxy info card */
.info-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 20px;
  margin-bottom: 24px;
}
.info-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 0;
}
.info-row + .info-row { border-top: 1px solid var(--border); }
.info-label { font-size: 12px; color: var(--text-3); font-weight: 500; text-transform: uppercase; letter-spacing: 0.06em; }
.info-value {
  font-family: var(--mono); font-size: 14px; font-weight: 600;
  color: var(--accent); display: flex; align-items: center; gap: 8px;
}
.btn-copy {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 10px; border-radius: 6px;
  background: var(--surface-2); border: 1px solid var(--border);
  color: var(--text-3); font-size: 11px; font-weight: 600;
  cursor: pointer; transition: all 0.15s ease;
  font-family: -apple-system, system-ui, sans-serif;
}
.btn-copy:hover { background: var(--border); color: var(--text); border-color: var(--border-focus); }
.btn-copy:active { transform: scale(0.96); }
.btn-copy.done { color: var(--accent); border-color: rgba(16,185,129,0.3); }

/* OS tabs */
.tabs {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 0; margin-bottom: 0;
}
.tab {
  padding: 12px 0; text-align: center;
  font-size: 13px; font-weight: 600; color: var(--text-3);
  background: var(--surface); border: 1px solid var(--border);
  cursor: pointer; transition: all 0.15s ease;
  user-select: none;
}
.tab:first-child { border-radius: var(--radius) 0 0 0; }
.tab:last-child { border-radius: 0 var(--radius) 0 0; }
.tab + .tab { border-left: none; }
.tab:hover { color: var(--text-2); background: var(--surface-2); }
.tab.active {
  color: var(--text); background: var(--surface-2);
  border-bottom-color: var(--surface-2);
}
.tab-icon { font-size: 16px; display: block; margin-bottom: 2px; }

/* Panels */
.panel {
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-top: none;
  border-radius: 0 0 var(--radius) var(--radius);
  padding: 24px;
  display: none;
}
.panel.active { display: block; }
.panel-title {
  font-size: 15px; font-weight: 700; color: var(--text);
  margin-bottom: 4px; letter-spacing: -0.01em;
}
.panel-desc { font-size: 13px; color: var(--text-3); line-height: 1.5; margin-bottom: 16px; }

/* Action button */
.btn-action {
  display: flex; align-items: center; justify-content: center; gap: 8px;
  width: 100%; padding: 14px 20px;
  border-radius: 10px; border: none;
  background: var(--accent); color: #000;
  font-size: 14px; font-weight: 700;
  cursor: pointer; transition: all 0.15s ease;
  font-family: -apple-system, system-ui, sans-serif;
}
.btn-action:hover { background: #0ea572; transform: translateY(-1px); box-shadow: 0 4px 16px rgba(16,185,129,0.3); }
.btn-action:active { transform: translateY(0); }
.btn-action svg { width: 16px; height: 16px; }
.btn-action.done { background: var(--accent-dim); color: var(--accent); }

/* Steps */
.steps { margin-top: 16px; }
.step {
  display: flex; gap: 12px; padding: 10px 0;
}
.step + .step { border-top: 1px solid rgba(255,255,255,0.04); }
.step-num {
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--surface); border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; color: var(--text-3);
  flex-shrink: 0; margin-top: 1px;
}
.step-text { font-size: 13px; color: var(--text-2); line-height: 1.5; }
.step-text strong { color: var(--text); font-weight: 600; }

/* Code block */
.code-block {
  background: var(--bg); border: 1px solid var(--border);
  border-radius: 8px; padding: 12px 14px; margin: 12px 0;
  font-family: var(--mono); font-size: 12px; color: var(--accent);
  line-height: 1.6; word-break: break-all;
  cursor: pointer; transition: border-color 0.15s;
  position: relative;
}
.code-block:hover { border-color: var(--border-focus); }
.code-tag {
  position: absolute; top: 8px; right: 10px;
  font-family: -apple-system, system-ui, sans-serif;
  font-size: 10px; font-weight: 600; color: var(--text-3);
  text-transform: uppercase; letter-spacing: 0.06em;
}

/* Undo section */
.undo-section {
  margin-top: 20px; padding-top: 16px;
  border-top: 1px solid var(--border);
}
.undo-title { font-size: 12px; font-weight: 600; color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin-bottom: 8px; }

/* Footer */
.footer { text-align: center; margin-top: 32px; padding: 16px 0; }
.footer p { font-size: 11px; color: var(--text-3); line-height: 1.6; }
</style>
</head>
<body>
<div class="container">

  <div class="header">
    <div class="brand">
      <div class="brand-icon">S</div>
      <span class="brand-name">ShareNet</span>
    </div>
    <div class="status-badge"><div class="status-dot"></div> Internet Connected</div>
    <h1 id="heroTitle">You're Connected</h1>
    <p id="heroDesc">Follow the steps below to start browsing through this device's connection.</p>
  </div>

  <div id="hotspotBanner" style="display:none; background: linear-gradient(135deg, rgba(16,185,129,0.15) 0%, rgba(5,150,105,0.08) 100%); border: 1px solid rgba(16,185,129,0.3); border-radius: var(--radius); padding: 20px; margin-bottom: 24px; text-align: center;">
    <div style="font-size: 36px; margin-bottom: 8px;">&#10003;</div>
    <div style="font-size: 18px; font-weight: 700; color: var(--accent); margin-bottom: 8px;">All Apps Work</div>
    <div style="font-size: 13px; color: var(--text-2); line-height: 1.6;">This phone is sharing internet via <strong>Wi-Fi Hotspot</strong>.<br>All your apps work automatically — no setup needed.</div>
    <div style="margin-top: 12px; padding: 10px; background: var(--surface); border-radius: 8px; font-size: 12px; color: var(--text-3);">
      Just use the internet normally. WhatsApp, browsers, games — everything works.
    </div>
  </div>

  <div id="proxySection">
  <div class="info-card">
    <div class="info-row">
      <span class="info-label">Proxy</span>
      <span class="info-value">
        {{PROXY_ADDR}}
        <button class="btn-copy" onclick="copyValue('{{PROXY_ADDR}}', this)">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2"><rect x="5" y="5" width="9" height="9" rx="1.5"/><path d="M5 11H3.5A1.5 1.5 0 0 1 2 9.5v-7A1.5 1.5 0 0 1 3.5 1h7A1.5 1.5 0 0 1 12 2.5V5"/></svg>
          Copy
        </button>
      </span>
    </div>
  </div>

  <div>
    <div class="tabs">
      <div class="tab active" onclick="switchTab('win', this)">
        <span class="tab-icon">▶</span>Windows
      </div>
      <div class="tab" onclick="switchTab('mac', this)">
        <span class="tab-icon">●</span>macOS
      </div>
      <div class="tab" onclick="switchTab('lin', this)">
        <span class="tab-icon">◆</span>Linux
      </div>
      <div class="tab" onclick="switchTab('and', this)">
        <span class="tab-icon">●</span>Android
      </div>
    </div>

    <!-- Windows -->
    <div class="panel active" id="p-win">
      <div class="panel-title">Windows Setup</div>
      <div class="panel-desc">Configure your system proxy with one command.</div>
      <button class="btn-action" onclick="runWindows()">
        <svg viewBox="0 0 16 16" fill="currentColor"><path d="M0 2.4l7-1v7H0V2.4zm7.8-.9L16 0v8.5H7.8V1.5zM0 9.5h7v7l-7-1V9.5zm7.8 0H16V16l-8.2-1.1V9.5z"/></svg>
        Configure Proxy
      </button>
      <div class="steps">
        <div class="step">
          <div class="step-num">1</div>
          <div class="step-text">Open <strong>PowerShell</strong> as Administrator</div>
        </div>
        <div class="step">
          <div class="step-num">2</div>
          <div class="step-text">Paste the command and press Enter:</div>
        </div>
      </div>
      <div class="code-block" onclick="copyCode(this)" data-cmd="powershell -Command &quot;Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -Name ProxyEnable -Value 1; Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -Name ProxyServer -Value '{{PROXY_ADDR}}'&quot;">
        <span class="code-tag">Copy</span>
        powershell -Command "Set-ItemProperty ... -Name ProxyServer -Value '{{PROXY_ADDR}}'"
      </div>
      <p class="panel-desc" style="margin-top:12px">This sets the system-wide proxy for browsers, email, and most applications.</p>
      <div class="undo-section">
        <div class="undo-title">Disable</div>
        <div class="code-block" onclick="copyCode(this)" data-cmd="powershell -Command &quot;Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -Name ProxyEnable -Value 0&quot;">
          <span class="code-tag">Copy</span>
          powershell -Command "... -Name ProxyEnable -Value 0"
        </div>
      </div>
    </div>

    <!-- macOS -->
    <div class="panel" id="p-mac">
      <div class="panel-title">macOS Setup</div>
      <div class="panel-desc">Configure your system proxy with one command.</div>
      <button class="btn-action" onclick="runMac()">
        <svg viewBox="0 0 16 16" fill="currentColor"><path d="M15.2 12.5c-.3.7-.6 1.3-1 1.9-.6.8-1.1 1.3-1.6 1.6-.6.4-1.3.6-2 .6-.5 0-1.1-.1-1.8-.4-.7-.3-1.3-.4-1.8-.4-.5 0-1.1.1-1.8.4C4.3 16.1 3.7 16.2 3.2 16.2c-.7 0-1.3-.2-1.9-.6C.7 15.2.2 14.7-.3 13.9c-.6-1-1-2-1.2-3.1-.2-1.2-.2-2.3 0-3.4.3-1.5.9-2.7 1.8-3.6.9-.9 2-1.3 3.2-1.3.5 0 1.2.1 2 .4.8.3 1.3.4 1.5.4.2 0 .7-.2 1.6-.5.8-.3 1.5-.4 2-.4 1.6.1 2.8.8 3.6 1.9-1.4.9-2.1 2.1-2 3.7 0 1.2.5 2.2 1.3 3 .4.4.8.7 1.3.9-.1.3-.2.6-.3.9z"/></svg>
        Configure Proxy
      </button>
      <div class="steps">
        <div class="step">
          <div class="step-num">1</div>
          <div class="step-text">Open <strong>Terminal</strong></div>
        </div>
        <div class="step">
          <div class="step-num">2</div>
          <div class="step-text">Paste the command and press Enter:</div>
        </div>
      </div>
      <div class="code-block" onclick="copyCode(this)" data-cmd="networksetup -setwebproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} &amp;&amp; networksetup -setsecurewebproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} &amp;&amp; networksetup -setsocksfirewallproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} &amp;&amp; networksetup -setwebproxystate Wi-Fi on &amp;&amp; networksetup -setsecurewebproxystate Wi-Fi on &amp;&amp; networksetup -setsocksfirewallproxystate Wi-Fi on">
        <span class="code-tag">Copy</span>
        networksetup -setwebproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} &amp;&amp; ...
      </div>
      <p class="panel-desc" style="margin-top:12px">You may be prompted for your Mac password.</p>
      <div class="undo-section">
        <div class="undo-title">Disable</div>
        <div class="code-block" onclick="copyCode(this)" data-cmd="networksetup -setwebproxystate Wi-Fi off &amp;&amp; networksetup -setsecurewebproxystate Wi-Fi off &amp;&amp; networksetup -setsocksfirewallproxystate Wi-Fi off">
          <span class="code-tag">Copy</span>
          networksetup -setwebproxystate Wi-Fi off &amp;&amp; ...
        </div>
      </div>
    </div>

    <!-- Linux -->
    <div class="panel" id="p-lin">
      <div class="panel-title">Linux Setup</div>
      <div class="panel-desc">Configure your system proxy with one command.</div>
      <button class="btn-action" onclick="runLinux()">
        <svg viewBox="0 0 16 16" fill="currentColor"><path d="M8 0C3.6 0 0 3.6 0 8s3.6 8 8 8 8-3.6 8-8S12.4 0 8 0zm0 14.4c-3.5 0-6.4-2.9-6.4-6.4S4.5 1.6 8 1.6s6.4 2.9 6.4 6.4-2.9 6.4-6.4 6.4zM5.6 5.2l-2.4 2.8 2.4 2.8c.4.4 1 .4 1.4 0l2.4-2.8-2.4-2.8c-.4-.4-1-.4-1.4 0z"/></svg>
        Configure Proxy
      </button>
      <div class="steps">
        <div class="step">
          <div class="step-num">1</div>
          <div class="step-text">Open <strong>Terminal</strong></div>
        </div>
        <div class="step">
          <div class="step-num">2</div>
          <div class="step-text">Paste the command and press Enter:</div>
        </div>
      </div>
      <div class="code-block" onclick="copyCode(this)" data-cmd="gsettings set org.gnome.system.proxy mode 'manual' &amp;&amp; gsettings set org.gnome.system.proxy.http host '{{PROXY_HOST}}' &amp;&amp; gsettings set org.gnome.system.proxy.http port {{PROXY_PORT}} &amp;&amp; gsettings set org.gnome.system.proxy.https host '{{PROXY_HOST}}' &amp;&amp; gsettings set org.gnome.system.proxy.https port {{PROXY_PORT}} &amp;&amp; gsettings set org.gnome.system.proxy.socks host '{{PROXY_HOST}}' &amp;&amp; gsettings set org.gnome.system.proxy.socks port {{PROXY_PORT}} &amp;&amp; export http_proxy=http://{{PROXY_ADDR}} https_proxy=http://{{PROXY_ADDR}} ALL_PROXY=socks5://{{PROXY_HOST}}:{{PROXY_PORT}}">
        <span class="code-tag">Copy</span>
        gsettings set org.gnome.system.proxy mode 'manual' &amp;&amp; ...
      </div>
      <p class="panel-desc" style="margin-top:12px">Works on GNOME desktops (Ubuntu, Fedora). Also add the <strong style="font-family:var(--mono)">export</strong> lines to your shell profile for terminal apps.</p>
      <div class="undo-section">
        <div class="undo-title">Disable</div>
        <div class="code-block" onclick="copyCode(this)" data-cmd="gsettings set org.gnome.system.proxy mode 'none'">
          <span class="code-tag">Copy</span>
          gsettings set org.gnome.system.proxy mode 'none'
        </div>
      </div>
    </div>

    <!-- Android -->
    <div class="panel" id="p-and">
      <div class="panel-title">Android Setup</div>
      <div class="panel-desc">Configure your Wi-Fi proxy manually.</div>
      <div class="steps">
        <div class="step">
          <div class="step-num">1</div>
          <div class="step-text">Open <strong>Settings</strong> &rarr; <strong>Wi-Fi</strong></div>
        </div>
        <div class="step">
          <div class="step-num">2</div>
          <div class="step-text">Long-press the connected network &rarr; <strong>Modify</strong></div>
        </div>
        <div class="step">
          <div class="step-num">3</div>
          <div class="step-text">Expand <strong>Advanced</strong> &rarr; Proxy &rarr; <strong>Manual</strong></div>
        </div>
        <div class="step">
          <div class="step-num">4</div>
          <div class="step-text">Hostname: <strong>{{PROXY_HOST}}</strong></div>
        </div>
        <div class="step">
          <div class="step-num">5</div>
          <div class="step-text">Port: <strong>{{PROXY_PORT}}</strong></div>
        </div>
        <div class="step">
          <div class="step-num">6</div>
          <div class="step-text">Tap <strong>Save</strong></div>
        </div>
      </div>
    </div>
  </div>

  <div class="footer">
    <p>To disable: run the undo command or set proxy mode to Off / Automatic</p>
  </div>

  </div> <!-- end proxySection -->

</div>

<script>
// Hotspot mode detection
if ('{{HOTSPOT_MODE}}' === 'true') {
  document.getElementById('hotspotBanner').style.display = 'block';
  document.getElementById('proxySection').style.display = 'none';
  document.getElementById('heroTitle').textContent = 'You\'re Connected';
  document.getElementById('heroDesc').textContent = 'Internet is shared via Wi-Fi Hotspot.';
}
function switchTab(id, el) {
  document.querySelectorAll('.panel').forEach(function(p) { p.classList.remove('active'); });
  document.querySelectorAll('.tab').forEach(function(t) { t.classList.remove('active'); });
  document.getElementById('p-' + id).classList.add('active');
  el.classList.add('active');
}
function copyToClipboard(txt) {
  var ta = document.createElement('textarea');
  ta.value = txt;
  ta.style.position = 'fixed';
  ta.style.left = '-9999px';
  document.body.appendChild(ta);
  ta.select();
  var ok = false;
  try { ok = document.execCommand('copy'); } catch(e) {}
  document.body.removeChild(ta);
  if (!ok) { try { navigator.clipboard.writeText(txt); ok = true; } catch(e) {} }
  return ok;
}
function flashButton(el, msg) {
  el.classList.add('done');
  var orig = el.innerHTML;
  el.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2.5"><path d="M3 8l3.5 3.5L13 5"/></svg> ' + msg;
  setTimeout(function() { el.innerHTML = orig; el.classList.remove('done'); }, 2000);
}
function flashCode(el) {
  el.classList.add('done');
  el.querySelector('.code-tag').textContent = 'Copied!';
  setTimeout(function() { el.classList.remove('done'); el.querySelector('.code-tag').textContent = 'Copy'; }, 2000);
}
function copyValue(txt, el) {
  copyToClipboard(txt);
  flashButton(el, 'Copied! Paste in Terminal');
}
function copyCode(el) {
  var cmd = el.getAttribute('data-cmd');
  copyToClipboard(cmd);
  flashCode(el);
}
function runWindows() {
  var cmd = 'powershell -Command "Set-ItemProperty -Path \'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\' -Name ProxyEnable -Value 1; Set-ItemProperty -Path \'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\' -Name ProxyServer -Value \'{{PROXY_ADDR}}\'"';
  copyToClipboard(cmd);
  var btn = document.querySelector('#p-win .btn-action');
  flashButton(btn, 'Copied! Paste in PowerShell');
}
function runMac() {
  var cmd = 'networksetup -setwebproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} && networksetup -setsecurewebproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} && networksetup -setsocksfirewallproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} && networksetup -setwebproxystate Wi-Fi on && networksetup -setsecurewebproxystate Wi-Fi on && networksetup -setsocksfirewallproxystate Wi-Fi on';
  copyToClipboard(cmd);
  var btn = document.querySelector('#p-mac .btn-action');
  flashButton(btn, 'Copied! Paste in Terminal');
}
function runLinux() {
  var cmd = "gsettings set org.gnome.system.proxy mode 'manual' && gsettings set org.gnome.system.proxy.http host '{{PROXY_HOST}}' && gsettings set org.gnome.system.proxy.http port {{PROXY_PORT}} && gsettings set org.gnome.system.proxy.https host '{{PROXY_HOST}}' && gsettings set org.gnome.system.proxy.https port {{PROXY_PORT}} && gsettings set org.gnome.system.proxy.socks host '{{PROXY_HOST}}' && gsettings set org.gnome.system.proxy.socks port {{PROXY_PORT}}";
  copyToClipboard(cmd);
  var btn = document.querySelector('#p-lin .btn-action');
  flashButton(btn, 'Copied! Paste in Terminal');
}
</script>
</body>
</html>
""".trimIndent()
    }
}

class ProxyBindException(message: String, cause: Throwable) : Exception(message, cause)

/**
 * Wraps an InputStream that already had one byte read, re-inserting it.
 * Used by the unified proxy to peek at the first byte for protocol detection.
 */
private class FirstByteInputStream(private val firstByte: Int, private val delegate: InputStream) : InputStream() {
    private var consumed = false

    override fun read(): Int {
        if (!consumed) {
            consumed = true
            return firstByte
        }
        return delegate.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!consumed) {
            if (len <= 0) return 0
            b[off] = firstByte.toByte()
            consumed = true
            if (len == 1) return 1
            val n = delegate.read(b, off + 1, len - 1)
            return if (n < 0) 1 else n + 1
        }
        return delegate.read(b, off, len)
    }

    override fun available(): Int {
        val base = if (consumed) delegate.available() else 1
        return base
    }

    override fun close() = delegate.close()
}

/**
 * Inlined SOCKS5 handler for the unified proxy — handles SOCKS5 on the same port as HTTP.
 * Peeks at the first byte (already read as 0x05) and processes the SOCKS5 handshake.
 */
private class Socks5InlineHandler(
    private val stats: ProxyStats,
    private val destinationPolicy: DestinationPolicy,
    private val log: (String) -> Unit,
) {
    companion object {
        private const val BUFFER = 64 * 1024
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TUNNEL_JOIN_TIMEOUT_MS = 130_000L
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03
        private const val REP_SUCCESS = 0x00
        private const val REP_CONNECTION_NOT_ALLOWED = 0x02
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
        private val CRLF = byteArrayOf(0x0D, 0x0A)
    }

    fun handleClient(socket: Socket, firstByte: Int, input: BufferedInputStream, output: BufferedOutputStream) {
        try {
            stats.activeConnections.incrementAndGet()
            // First byte is already 0x05, so the version is known.
            // Read the rest of the method negotiation: NMETHODS + METHODS
            val nMethods = input.read()
            if (nMethods < 0) return
            repeat(nMethods) { input.read() }
            // Reply: no auth required
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // Read SOCKS5 request
            val ver = input.read()
            if (ver != 0x05) return
            val cmd = input.read()
            input.read() // reserved
            val atyp = input.read()

            when (cmd) {
                CMD_CONNECT -> handleConnect(input, output, atyp)
                CMD_UDP_ASSOCIATE -> {
                    // For simplicity, we don't support UDP ASSOCIATE yet in unified mode
                    sendReply(output, REP_COMMAND_NOT_SUPPORTED)
                }
                else -> sendReply(output, REP_COMMAND_NOT_SUPPORTED)
            }
        } catch (_: Exception) {
            // EOF / timeout
        } finally {
            // Don't close socket here — the caller (HttpProxyServer) does it
        }
    }

    private fun handleConnect(input: BufferedInputStream, output: BufferedOutputStream, atyp: Int) {
        val (destHost, destPort) = readAddress(input, atyp) ?: run {
            sendReply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
            return
        }
        if (!destinationPolicy.allow(destHost)) {
            sendReply(output, REP_CONNECTION_NOT_ALLOWED)
            return
        }
        val origin = Socket()
        try {
            origin.connect(InetSocketAddress(destHost, destPort), CONNECT_TIMEOUT_MS)
            origin.soTimeout = IDLE_TIMEOUT_MS
            origin.tcpNoDelay = true
            sendReply(output, REP_SUCCESS, origin.localAddress)

            val done = AtomicBoolean(false)
            val upstream = Thread {
                runCatching { pump(input, origin.getOutputStream(), stats.bytesFromClients, done) }
            }
            val downstream = Thread {
                runCatching { pump(origin.getInputStream(), output, stats.bytesToClients, done) }
            }
            upstream.start()
            downstream.start()
            upstream.join()
            downstream.join()
        } catch (_: Exception) {
            sendReply(output, REP_HOST_UNREACHABLE)
        } finally {
            runCatching { origin.close() }
        }
    }

    private fun readAddress(input: BufferedInputStream, atyp: Int): Pair<String, Int>? {
        return when (atyp) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                if (input.read(addr) < 4) return null
                val port = readPort(input)
                "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}" to port
            }
            ATYP_DOMAIN -> {
                val len = input.read()
                if (len < 0) return null
                val domain = ByteArray(len)
                if (input.read(domain) < len) return null
                val port = readPort(input)
                String(domain) to port
            }
            ATYP_IPV6 -> {
                val addr = ByteArray(16)
                if (input.read(addr) < 16) return null
                val port = readPort(input)
                val sb = StringBuilder("[")
                for (i in 0..7) {
                    if (i > 0) sb.append(':')
                    sb.append("%04x".format(((addr[i * 2].toInt() and 0xFF) shl 8) or (addr[i * 2 + 1].toInt() and 0xFF)))
                }
                sb.append(']')
                sb.toString() to port
            }
            else -> null
        }
    }

    private fun readPort(input: BufferedInputStream): Int {
        val hi = input.read()
        val lo = input.read()
        return ((hi and 0xFF) shl 8) or (lo and 0xFF)
    }

    private fun sendReply(output: BufferedOutputStream, rep: Int, bindAddr: InetAddress? = null, bindPort: Int = 0) {
        val reply = ByteArray(10)
        reply[0] = 0x05
        reply[1] = rep.toByte()
        reply[2] = 0x00
        reply[3] = ATYP_IPV4.toByte()
        if (bindAddr != null) {
            val bytes = bindAddr.address
            System.arraycopy(bytes, 0, reply, 4, 4)
        }
        reply[8] = ((bindPort shr 8) and 0xFF).toByte()
        reply[9] = (bindPort and 0xFF).toByte()
        output.write(reply)
        output.flush()
    }

    private fun pump(from: InputStream, to: OutputStream, counter: AtomicLong, done: AtomicBoolean) {
        val buf = ByteArray(BUFFER)
        try {
            while (!done.get()) {
                val n = from.read(buf)
                if (n < 0) break
                if (n > 0) {
                    to.write(buf, 0, n)
                    to.flush()
                    counter.addAndGet(n.toLong())
                }
            }
        } catch (_: Exception) {
        } finally {
            done.set(true)
        }
    }
}
