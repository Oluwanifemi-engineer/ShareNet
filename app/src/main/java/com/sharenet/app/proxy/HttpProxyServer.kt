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
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)
            serve(input, output)
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
                if (isHopByHop(lower) && !(lower == "transfer-encoding" && req.chunked)) continue
                if (lower == "host") hasHost = true
                head.append(name).append(": ").append(value).append("\r\n")
            }
            if (!hasHost) head.append("Host: ").append(req.host).append("\r\n")
            head.append("Connection: close\r\n\r\n")
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
            origin.connect(InetSocketAddress(req.host, req.port), CONNECT_TIMEOUT_MS)
            origin.soTimeout = IDLE_TIMEOUT_MS
            origin.tcpNoDelay = true
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
            "upgrade",
        )

        private val SETUP_PAGE_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ShareNet</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;
background:#111;color:#f5f5f5;min-height:100vh}
.wrap{max-width:520px;margin:0 auto;padding:56px 20px 40px}
.logo{display:flex;align-items:center;gap:10px;margin-bottom:32px}
.logo-mark{width:32px;height:32px;background:linear-gradient(135deg,#22c55e,#16a34a);
border-radius:8px;display:flex;align-items:center;justify-content:center;
font-size:16px;font-weight:700;color:#fff}
.logo-text{font-size:15px;font-weight:600;letter-spacing:-0.01em}
.hdr{margin-bottom:40px}
.hdr h1{font-size:24px;font-weight:700;letter-spacing:-0.02em;margin-bottom:6px}
.hdr p{font-size:14px;color:#888;line-height:1.5}
.chip{display:inline-flex;align-items:center;gap:6px;margin-top:10px;
font-size:12px;color:#22c55e;font-weight:500}
.dot{width:6px;height:6px;background:#22c55e;border-radius:50%;
animation:blink 2s infinite}
@keyframes blink{0%,100%{opacity:1}50%{opacity:.3}}
.field{margin-bottom:24px}
.field-label{font-size:11px;text-transform:uppercase;letter-spacing:.08em;
color:#555;font-weight:600;margin-bottom:6px}
.field-addr{background:#1a1a1a;border:1px solid #2a2a2a;border-radius:10px;
padding:14px 16px;display:flex;align-items:center;justify-content:space-between}
.field-addr span{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;
font-size:16px;font-weight:600;color:#22c55e;letter-spacing:0.5px}
.copy-btn{background:#222;border:1px solid #333;border-radius:8px;
padding:6px 12px;font-size:12px;color:#aaa;cursor:pointer;
transition:all .15s;font-weight:500}
.copy-btn:hover{background:#333;color:#fff;border-color:#444}
.copy-btn:active{transform:scale(.97)}
.copy-btn.copied{color:#22c55e;border-color:#22c55e}
.os-tabs{display:flex;gap:0;margin-bottom:0}
.os-tab{flex:1;padding:10px 0;text-align:center;font-size:13px;font-weight:500;
color:#555;background:#161616;border:1px solid #2a2a2a;cursor:pointer;
transition:all .15s;user-select:none}
.os-tab:first-child{border-radius:10px 0 0 0}
.os-tab:last-child{border-radius:0 10px 0 0}
.os-tab+.os-tab{border-left:none}
.os-tab:hover{color:#aaa}
.os-tab.active{color:#f5f5f5;background:#1a1a1a;border-bottom-color:#1a1a1a}
.os-panel{background:#1a1a1a;border:1px solid #2a2a2a;border-top:none;
border-radius:0 0 10px 10px;padding:20px;display:none}
.os-panel.active{display:block}
.os-panel p{font-size:13px;color:#999;line-height:1.6;margin-bottom:12px}
.os-panel ol{padding-left:18px;margin:0}
.os-panel li{font-size:13px;color:#ccc;line-height:2}
.os-panel li strong{color:#f5f5f5}
.step-cmd{background:#111;border:1px solid #2a2a2a;border-radius:8px;
padding:10px 14px;margin:10px 0;font-family:ui-monospace,SFMono-Regular,monospace;
font-size:12px;color:#22c55e;word-break:break-all;line-height:1.5;
cursor:pointer;transition:border-color .15s;position:relative}
.step-cmd:hover{border-color:#3a3a3a}
.step-cmd .tag{position:absolute;top:8px;right:10px;font-size:10px;
color:#555;font-family:system-ui,sans-serif;text-transform:uppercase;
letter-spacing:.06em}
.divider{height:1px;background:#222;margin:28px 0}
.foot{text-align:center;margin-top:28px}
.foot p{font-size:11px;color:#444;line-height:1.6}
.foot a{color:#555;text-decoration:none}
</style>
</head>
<body>
<div class="wrap">
  <div class="logo">
    <div class="logo-mark">S</div>
    <div class="logo-text">ShareNet</div>
  </div>

  <div class="hdr">
    <h1>Connected to ShareNet</h1>
    <p>Set up your proxy to access the internet through this device.</p>
    <div class="chip"><div class="dot"></div> Internet is available</div>
  </div>

  <div class="field">
    <div class="field-label">Proxy Address</div>
    <div class="field-addr">
      <span>{{PROXY_ADDR}}</span>
      <button class="copy-btn" onclick="copyText('{{PROXY_ADDR}}',this)">Copy</button>
    </div>
  </div>

  <div>
    <div class="os-tabs">
      <div class="os-tab active" onclick="switchOS('win',this)">Windows</div>
      <div class="os-tab" onclick="switchOS('mac',this)">macOS</div>
      <div class="os-tab" onclick="switchOS('lin',this)">Linux</div>
      <div class="os-tab" onclick="switchOS('and',this)">Android</div>
    </div>

    <div class="os-panel active" id="p-win">
      <p>One-click setup via PowerShell:</p>
      <div class="step-cmd" onclick="copyText(this.getAttribute('data-cmd'),this)">
        <span class="tag">Copy</span>
        powershell -Command "Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\Internet Settings' -Name ProxyEnable -Value 1; Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\Internet Settings' -Name ProxyServer -Value '{{PROXY_ADDR}}'"
      </div>
      <p style="margin-top:8px">Then open <strong>Internet Options</strong> → Connections → LAN Settings to verify. This configures the system proxy for browsers and most apps.</p>
      <div class="divider"></div>
      <p>To undo:</p>
      <div class="step-cmd" onclick="copyText(this.getAttribute('data-cmd'),this)">
        <span class="tag">Copy</span>
        powershell -Command "Set-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\Internet Settings' -Name ProxyEnable -Value 0"
      </div>
    </div>

    <div class="os-panel" id="p-mac">
      <p>One-click setup via Terminal:</p>
      <div class="step-cmd" onclick="copyText(this.getAttribute('data-cmd'),this)">
        <span class="tag">Copy</span>
        networksetup -setwebproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} &amp;&amp; networksetup -setsecurewebproxy Wi-Fi {{PROXY_HOST}} {{PROXY_PORT}} &amp;&amp; networksetup -setwebproxystate Wi-Fi on &amp;&amp; networksetup -setsecurewebproxystate Wi-Fi on
      </div>
      <p style="margin-top:8px">Paste into <strong>Terminal</strong>. You may be prompted for your Mac password.</p>
      <div class="divider"></div>
      <p>To undo:</p>
      <div class="step-cmd" onclick="copyText(this.getAttribute('data-cmd'),this)">
        <span class="tag">Copy</span>
        networksetup -setwebproxystate Wi-Fi off &amp;&amp; networksetup -setsecurewebproxystate Wi-Fi off
      </div>
    </div>

    <div class="os-panel" id="p-lin">
      <p>One-click setup via Terminal:</p>
      <div class="step-cmd" onclick="copyText(this.getAttribute('data-cmd'),this)">
        <span class="tag">Copy</span>
        gsettings set org.gnome.system.proxy mode 'manual' &amp;&amp; gsettings set org.gnome.system.proxy.http host '{{PROXY_HOST}}' &amp;&amp; gsettings set org.gnome.system.proxy.http port {{PROXY_PORT}} &amp;&amp; gsettings set org.gnome.system.proxy.https host '{{PROXY_HOST}}' &amp;&amp; gsettings set org.gnome.system.proxy.https port {{PROXY_PORT}}
      </div>
      <p style="margin-top:8px">Paste into <strong>Terminal</strong>. Works for GNOME-based desktops (Ubuntu, Fedora).</p>
      <div class="divider"></div>
      <p>To undo:</p>
      <div class="step-cmd" onclick="copyText(this.getAttribute('data-cmd'),this)">
        <span class="tag">Copy</span>
        gsettings set org.gnome.system.proxy mode 'none'
      </div>
    </div>

    <div class="os-panel" id="p-and">
      <ol>
        <li>Open <strong>Settings</strong> → <strong>Wi-Fi</strong></li>
        <li>Tap the connected network → <strong>Modify</strong></li>
        <li>Advanced → <strong>Proxy</strong> → <strong>Manual</strong></li>
        <li>Set Hostname: <strong>{{PROXY_HOST}}</strong></li>
        <li>Set Port: <strong>{{PROXY_PORT}}</strong></li>
        <li>Tap <strong>Save</strong></li>
      </ol>
    </div>
  </div>

  <div class="foot">
    <p>To undo: disable the proxy or set mode back to Off / Automatic</p>
  </div>
</div>

<script>
function switchOS(os,el){
  document.querySelectorAll('.os-panel').forEach(p=>p.classList.remove('active'));
  document.querySelectorAll('.os-tab').forEach(t=>t.classList.remove('active'));
  document.getElementById('p-'+os).classList.add('active');
  el.classList.add('active');
}
function copyText(txt,el){
  navigator.clipboard.writeText(txt).then(()=>{
    el.classList.add('copied');
    var orig=el.innerHTML;
    el.innerHTML='Copied';
    setTimeout(()=>{el.innerHTML=orig;el.classList.remove('copied')},1500);
  });
}
</script>
</body>
</html>
""".trimIndent()
    }
}

class ProxyBindException(message: String, cause: Throwable) : Exception(message, cause)
