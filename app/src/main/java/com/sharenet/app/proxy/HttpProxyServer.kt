package com.sharenet.app.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val log: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var acceptSocket: ServerSocket? = null
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

    /**
     * Starts listening. Throws [ProxyBindException] if the address cannot be
     * bound (usually because the P2P interface is not up yet — callers retry).
     */
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

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            serve(input, output)
        } catch (_: Exception) {
            // EOF / timeout / reset — normal for proxy clients, not actionable.
        } finally {
            runCatching { socket.close() }
            clientSockets.remove(socket)
            stats.activeConnections.decrementAndGet()
        }
    }

    /** Keep-alive request loop for one client connection. */
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
                    return // tunnel ends the client connection
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

    /**
     * When captive portal is enabled, intercept plain HTTP requests and
     * return a setup page that auto-configures the proxy on the client.
     * This is the key consumer-friendly feature: when a PC opens a browser,
     * instead of "no internet," it sees a one-click setup page.
     *
     * Special paths:
     *   /proxy.pac  → PAC file for auto-proxy configuration
     *   /setup      → the setup page (HTML)
     *   everything else → 302 redirect to /setup
     */
    private fun handleCaptivePortal(
        request: Request,
        input: InputStream,
        output: OutputStream,
    ): Boolean {
        val path = request.path
        // Use the actual bound port, not the request port (which defaults to 80)
        val host = request.host
        val portNum = boundPort
        val proxyAddr = "$host:$portNum"

        // Absolute-form requests (GET http://example.com/ ...) are proxy
        // requests from configured clients — forward them to the internet.
        // Only intercept origin-form requests (GET / ...) from unconfigured
        // browsers.
        if (request.absoluteTarget) {
            // This is a properly configured proxy request — forward it
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
                // Redirect to setup page using the actual bind address (not the request Host)
                val setupUrl = "http://$bindHost:$portNum/setup"
                runCatching {
                    output.write(
                        "HTTP/1.1 302 Found\r\n".toByteArray(Charsets.ISO_8859_1),
                    )
                    output.write(
                        "Location: $setupUrl\r\n".toByteArray(Charsets.ISO_8859_1),
                    )
                    output.write(
                        "Content-Length: 0\r\n".toByteArray(Charsets.ISO_8859_1),
                    )
                    output.write(
                        "Connection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1),
                    )
                    output.flush()
                }
            }
        }
        return false // close after response
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
        /** True when the original request target was an absolute URL
         *  (e.g. GET http://example.com/path). */
        val absoluteTarget: Boolean = false,
    )

    private fun parseRequest(head: List<String>): Request? {
        val requestLine = head.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null
        val method = parts[0].uppercase()
        val target = parts[1]
        val version = parts[2]

        // Header names keep their ORIGINAL case (HTTP names are
        // case-insensitive and real servers may parse them case-sensitively,
        // so a faithful proxy forwards them as received); all internal
        // matching below is case-insensitive.
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
            // Origin-form: the target is the path; the Host header has the host.
            val hostHeader = headers
                .firstOrNull { it.first.equals("host", ignoreCase = true) }?.second
                ?: return null
            if (hostHeader.startsWith("[")) {
                // IPv6 literal [::1]:8080
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

    /** Returns whether the CLIENT connection may stay alive. */
    private fun handleHttp(req: Request, input: InputStream, output: OutputStream): Boolean {
        // Refuse destinations a joined client must never reach through the
        // phone (the host's own LAN, loopback, link-local).
        if (!destinationPolicy.allow(req.host)) {
            writeSimpleResponse(output, 403, "Forbidden")
            return false
        }
        val origin = Socket()
        try {
            origin.connect(InetSocketAddress(req.host, req.port), CONNECT_TIMEOUT_MS)
            origin.soTimeout = IDLE_TIMEOUT_MS
            val originInput = BufferedInputStream(origin.getInputStream(), BUFFER)
            val originOutput = BufferedOutputStream(origin.getOutputStream(), BUFFER)

            // Request head: forward the client's, minus hop-by-hop headers.
            val head = StringBuilder()
            head.append(req.method).append(' ').append(req.path).append(" HTTP/1.1\r\n")
            var hasHost = false
            for ((name, value) in req.headers) {
                val lower = name.lowercase()
                if (isHopByHop(lower) && !(lower == "transfer-encoding" && req.chunked)) continue
                if (lower == "host") hasHost = true
                head.append(name).append(": ").append(value).append("\r\n")
            }
            if (!hasHost) head.append("Host: ").append(req.host).append("\r\n")
            // One request per origin connection keeps response framing simple and
            // guarantees close-delimited responses are unambiguous.
            head.append("Connection: close\r\n\r\n")
            originOutput.write(head.toString().toByteArray(Charsets.ISO_8859_1))

            // Request body.
            when {
                req.chunked -> forwardChunked(input, originOutput, stats.bytesFromClients)
                req.contentLength > 0L -> copyN(input, originOutput, req.contentLength, stats.bytesFromClients)
            }
            originOutput.flush()

            // Response head.
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

            // Response body. ALWAYS flush after writing: the socket may be
            // closed right after this method returns, and closing the raw
            // socket discards anything still sitting in the BufferedOutputStream.
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
                    // Close-delimited body: the origin closes after the response,
                    // so the client connection cannot be kept alive.
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

    // ── CONNECT tunneling (HTTPS, WSS, anything over TLS) ───────────────────

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
            origin.connect(InetSocketAddress(req.host, req.port), CONNECT_TIMEOUT_MS)
            origin.soTimeout = IDLE_TIMEOUT_MS
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()

            val done = AtomicBoolean(false)
            // Both directions must be fully exception-safe: when one pump ends
            // (EOF, timeout, or the proxy stopping) it closes its stream, which
            // closes the shared socket — the peer thread may then hit a
            // "Socket is closed" on getInputStream()/getOutputStream() before
            // its own pump starts. An uncaught exception on either raw Thread
            // would crash the whole process, so swallow it here.
            val upstream = Thread {
                runCatching { pump(input, origin.getOutputStream(), stats.bytesFromClients, done) }
            }
            val downstream = Thread {
                runCatching { pump(origin.getInputStream(), output, stats.bytesToClients, done) }
            }
            upstream.start()
            downstream.start()
            upstream.join(TUNNEL_JOIN_TIMEOUT_MS)
            downstream.join(TUNNEL_JOIN_TIMEOUT_MS)
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
            runCatching { to.close() }
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
            counter.addAndGet(n.toLong())
        }
    }

    /** Forwards a chunked body verbatim (chunk sizes + data + trailers). */
    private fun forwardChunked(from: InputStream, to: OutputStream, counter: AtomicLong) {
        while (true) {
            val sizeLine = readLine(from) ?: return
            val size = sizeLine.substringBefore(';').trim().toLongOrNull() ?: return
            writeLine(to, sizeLine)
            counter.addAndGet(sizeLine.length.toLong() + 2)
            if (size == 0L) {
                // Trailers until the blank line.
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

    /**
     * Reads the request/response head (request line + headers) up to the blank
     * line. Returns null on EOF or when the head exceeds size limits.
     */
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

    /** Reads one CRLF/LF-terminated line, trimmed of the line ending. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArray(MAX_LINE_SIZE)
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (count == 0) null else String(buf, 0, count, Charsets.ISO_8859_1)
            if (b == '\n'.code) {
                // strip trailing \r
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
        private const val BUFFER = 8192
        private const val MAX_CONNECTIONS = 32
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TUNNEL_JOIN_TIMEOUT_MS = 130_000L
        private const val MAX_LINE_SIZE = 16 * 1024
        private const val MAX_HEAD_SIZE = 64 * 1024

        private val CRLF = byteArrayOf(0x0D, 0x0A)

        // RFC 2616 §13.5.1 hop-by-hop headers — must not be forwarded.
        private val HOP_BY_HOP_HEADERS = setOf(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )

        /**
         * Consumer-friendly setup page served by the captive portal.
         * When a PC opens a browser on the ShareNet hotspot, this page
         * auto-configures the proxy so the user can browse immediately.
         */
        private val SETUP_PAGE_HTML = """
            |<!DOCTYPE html>
            |<html lang="en">
            |<head>
            |  <meta charset="utf-8">
            |  <meta name="viewport" content="width=device-width, initial-scale=1">
            |  <title>ShareNet — Internet Setup</title>
            |  <style>
            |    * { box-sizing: border-box; margin: 0; padding: 0; }
            |    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            |           background: #0f0f0f; color: #e0e0e0; padding: 24px; max-width: 640px; margin: 0 auto; }
            |    h1 { font-size: 1.5em; margin-bottom: 8px; color: #fff; }
            |    .subtitle { color: #9e9e9e; margin-bottom: 24px; font-size: 0.95em; }
            |    .card { background: #1a1a1a; border-radius: 12px; padding: 20px; margin-bottom: 16px;
            |            border: 1px solid #333; }
            |    .card h2 { font-size: 1.1em; margin-bottom: 12px; color: #4fc3f7; }
            |    .step { display: flex; align-items: flex-start; margin-bottom: 10px; }
            |    .step-num { background: #4fc3f7; color: #000; width: 24px; height: 24px;
            |                border-radius: 50%; display: flex; align-items: center;
            |                justify-content: center; font-weight: bold; font-size: 0.85em;
            |                flex-shrink: 0; margin-right: 10px; margin-top: 2px; }
            |    .step-text { line-height: 1.5; }
            |    code { background: #2a2a2a; padding: 2px 6px; border-radius: 4px;
            |           font-family: 'SF Mono', Consolas, monospace; font-size: 0.9em;
            |           color: #81c784; }
            |    .btn { display: inline-block; background: #4fc3f7; color: #000; padding: 12px 24px;
            |           border-radius: 8px; text-decoration: none; font-weight: 600;
            |           margin-top: 8px; font-size: 1em; }
            |    .btn:hover { background: #81d4fa; }
            |    .note { color: #9e9e9e; font-size: 0.85em; margin-top: 16px; line-height: 1.5; }
            |    .proxy-info { background: #1b2a1b; border: 1px solid #2e7d32; border-radius: 8px;
            |                  padding: 12px 16px; margin-top: 12px; font-size: 0.95em; }
            |    .proxy-info strong { color: #81c784; }
            |    .tabs { display: flex; gap: 0; margin-bottom: 0; }
            |    .tab { padding: 10px 16px; background: #222; cursor: pointer; border-radius: 8px 8px 0 0;
            |           font-size: 0.9em; color: #999; border: 1px solid #333; border-bottom: none; }
            |    .tab.active { background: #1a1a1a; color: #4fc3f7; font-weight: 600; }
            |    .tab-content { display: none; }
            |    .tab-content.active { display: block; }
            |    .platform { display: none; }
            |    .platform.active { display: block; }
            |  </style>
            |</head>
            |<body>
            |  <h1>🌐 ShareNet</h1>
            |  <p class="subtitle">Internet is available — just configure the proxy below.</p>
            |
            |  <div class="card">
            |    <h2>⚡ Quick Setup (Recommended)</h2>
            |    <p style="margin-bottom:12px">Copy and paste this command in a terminal to auto-configure:</p>
            |    <div style="background:#1a2a1a;border:1px solid #2e7d32;border-radius:8px;padding:12px 16px;margin-top:8px;">
            |      <code style="font-size:0.85em;color:#81c784;word-break:break-all;">gsettings set org.gnome.system.proxy mode 'manual' && gsettings set org.gnome.system.proxy.http host '192.168.49.1' && gsettings set org.gnome.system.proxy.http port 8080 && gsettings set org.gnome.system.proxy.https host '192.168.49.1' && gsettings set org.gnome.system.proxy.https port 8080</code>
            |    </div>
            |    <p class="note" style="margin-top:8px">For Firefox: Settings → General → Network Settings → Manual proxy → <code>192.168.49.1:8080</code></p>
            |  </div>
            |
            |  <div class="card">
            |    <h2>📋 Manual Setup</h2>
            |    <div class="proxy-info">
            |      Proxy Address: <strong>{{PROXY_ADDR}}</strong>
            |    </div>
            |    <div class="tabs" id="tabs">
            |      <div class="tab active" onclick="showTab('windows')">Windows</div>
            |      <div class="tab" onclick="showTab('mac')">Mac</div>
            |      <div class="tab" onclick="showTab('linux')">Linux</div>
            |      <div class="tab" onclick="showTab('android')">Android</div>
            |    </div>
            |
            |    <div class="platform active" id="windows">
            |      <div class="step"><div class="step-num">1</div><div class="step-text">Open <strong>Settings</strong> → Network & Internet → <strong>Proxy</strong></div></div>
            |      <div class="step"><div class="step-num">2</div><div class="step-text">Turn on <strong>Use a proxy server</strong></div></div>
            |      <div class="step"><div class="step-num">3</div><div class="step-text">Enter Address: <code>{{PROXY_ADDR}}</code></div></div>
            |      <div class="step"><div class="step-num">4</div><div class="step-text">Click <strong>Save</strong></div></div>
            |    </div>
            |
            |    <div class="platform" id="mac">
            |      <div class="step"><div class="step-num">1</div><div class="step-text">Open <strong>System Settings</strong> → Network → Wi-Fi → <strong>Details</strong></div></div>
            |      <div class="step"><div class="step-num">2</div><div class="step-text">Go to <strong>Proxies</strong> tab</div></div>
            |      <div class="step"><div class="step-num">3</div><div class="step-text">Enable <strong>Web Proxy (HTTP)</strong> and <strong>Secure Web Proxy (HTTPS)</strong></div></div>
            |      <div class="step"><div class="step-num">4</div><div class="step-text">Enter: <code>{{PROXY_ADDR}}</code></div></div>
            |    </div>
            |
            |    <div class="platform" id="linux">
            |      <div class="step"><div class="step-num">1</div><div class="step-text">Open <strong>Settings</strong> → Network → Network Proxy</div></div>
            |      <div class="step"><div class="step-num">2</div><div class="step-text">Set Method to <strong>Manual</strong></div></div>
            |      <div class="step"><div class="step-num">3</div><div class="step-text">Enter HTTP Proxy: <code>{{PROXY_ADDR}}</code></div></div>
            |      <div class="step"><div class="step-num">4</div><div class="step-text">Click <strong>Apply</strong></div></div>
            |    </div>
            |
            |    <div class="platform" id="android">
            |      <div class="step"><div class="step-num">1</div><div class="step-text">Long-press the connected Wi-Fi network → <strong>Modify</strong></div></div>
            |      <div class="step"><div class="step-num">2</div><div class="step-text">Advanced → Proxy → <strong>Manual</strong></div></div>
            |      <div class="step"><div class="step-num">3</div><div class="step-text">Enter: <code>{{PROXY_ADDR}}</code></div></div>
            |    </div>
            |  </div>
            |
            |  <p class="note">Once configured, all your internet traffic will flow through this connection. To undo, set Proxy back to "Off" or "Automatic".</p>
            |
            |  <script>
            |    function showTab(id) {
            |      document.querySelectorAll('.platform').forEach(p => p.classList.remove('active'));
            |      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
            |      document.getElementById(id).classList.add('active');
            |      event.target.classList.add('active');
            |    }
            |  </script>
            |</body>
            |</html>
        """.trimMargin()
    }
}

class ProxyBindException(message: String, cause: Throwable) : Exception(message, cause)
