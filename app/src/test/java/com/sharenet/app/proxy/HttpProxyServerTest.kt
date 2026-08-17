package com.sharenet.app.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/**
 * Real-socket integration tests for the proxy. Runs entirely on the JVM —
 * no Android framework involved.
 */
class HttpProxyServerTest {

    private lateinit var proxy: HttpProxyServer
    private val origins = mutableListOf<ServerSocket>()

    @Before
    fun setUp() {
        // PERMISSIVE: these tests exercise loopback origins, which the
        // production policy refuses by design (see the policy tests below).
        proxy = HttpProxyServer("127.0.0.1", 0, ProxyStats(), DestinationPolicy.PERMISSIVE) {}
        proxy.start()
    }

    @After
    fun tearDown() {
        proxy.stop()
        origins.forEach { runCatching { it.close() } }
    }

    private fun startOrigin(handler: (Socket) -> Unit): ServerSocket {
        val ss = ServerSocket(0)
        origins.add(ss)
        Thread {
            while (!ss.isClosed) {
                try {
                    val socket = ss.accept()
                    Thread {
                        try {
                            handler(socket)
                        } finally {
                            runCatching { socket.close() }
                        }
                    }.apply { isDaemon = true }.start()
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true }.start()
        return ss
    }

    private fun proxySocket(): Socket =
        Socket("127.0.0.1", proxy.boundPort).apply { soTimeout = 10_000 }

    // ── Plain HTTP ──────────────────────────────────────────────────────────

    @Test
    fun `proxies a plain http get`() {
        val origin = startOrigin { socket ->
            val requestLine = readRequestLine(socket)
            assertTrue(requestLine.startsWith("GET /hello"))
            writeResponse(socket.getOutputStream(), "hello from origin")
        }

        val client = proxySocket()
        client.getOutputStream().write(
            ("GET http://127.0.0.1:${origin.localPort}/hello HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${origin.localPort}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()

        val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(response.contains("200 OK"))
        assertTrue(response.contains("hello from origin"))
        client.close()
    }

    @Test
    fun `origin-form request uses the host header`() {
        val origin = startOrigin { socket ->
            val requestLine = readRequestLine(socket)
            assertTrue(requestLine.startsWith("GET /path HTTP/1.1"))
            writeResponse(socket.getOutputStream(), "via host header")
        }

        val client = proxySocket()
        client.getOutputStream().write(
            ("GET /path HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${origin.localPort}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()

        val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(response.contains("via host header"))
        client.close()
    }

    @Test
    fun `post body is forwarded`() {
        val origin = startOrigin { socket ->
            // ONE reader for the whole exchange: a second reader would miss the
            // body bytes already buffered inside this one.
            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1),
            )
            assertTrue(reader.readLine().startsWith("POST /submit"))
            var contentLength = -1
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(':').trim().toInt()
                }
            }
            val body = CharArray(contentLength).also { reader.read(it) }
            writeResponse(socket.getOutputStream(), "got:${String(body)}")
        }

        val client = proxySocket()
        client.getOutputStream().write(
            ("POST http://127.0.0.1:${origin.localPort}/submit HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${origin.localPort}\r\n" +
                "Content-Length: 5\r\n" +
                "Connection: close\r\n\r\n" +
                "hello").toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()

        val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(response.contains("got:hello"))
        client.close()
    }

    @Test
    fun `keep-alive serves multiple requests on one connection`() {
        val origin = startOrigin { socket ->
            val requestLine = readRequestLine(socket)
            if (requestLine.startsWith("GET /one")) {
                writeResponse(socket.getOutputStream(), "first")
            } else {
                writeResponse(socket.getOutputStream(), "second")
            }
        }

        val client = proxySocket()
        val out = client.getOutputStream()
        val host = "127.0.0.1:${origin.localPort}"

        out.write(
            ("GET http://$host/one HTTP/1.1\r\nHost: $host\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        out.flush()
        val response1 = readResponse(client)
        assertTrue(response1.contains("first"))

        out.write(
            ("GET http://$host/two HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n")
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        out.flush()
        val response2 = readResponse(client)
        assertTrue(response2.contains("second"))
        client.close()
    }

    @Test
    fun `request without host is rejected with 400`() {
        val client = proxySocket()
        client.getOutputStream().write(
            "GET / HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()
        val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(response.contains("400"))
        client.close()
    }

    // ── CONNECT ─────────────────────────────────────────────────────────────

    @Test
    fun `connect establishes a tunnel and echoes bytes both ways`() {
        val origin = startOrigin { socket ->
            // Echo server: read everything, send it back.
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val buf = ByteArray(4096)
            var n: Int
            while (true) {
                n = input.read(buf)
                if (n < 0) break
                if (n > 0) {
                    output.write(buf, 0, n)
                    output.flush()
                }
            }
        }

        val client = proxySocket()
        val out = client.getOutputStream()
        out.write(
            ("CONNECT 127.0.0.1:${origin.localPort} HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${origin.localPort}\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        out.flush()

        val status = readResponseHead(client)
        assertTrue(status.contains("200 Connection Established"))

        out.write("ping-through-tunnel\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()

        // "ping-through-tunnel\n" is 20 bytes.
        val echoed = ByteArray(20)
        var read = 0
        while (read < echoed.size) {
            val n = client.getInputStream().read(echoed, read, echoed.size - read)
            if (n < 0) break
            read += n
        }
        assertEquals("ping-through-tunnel\n", String(echoed, 0, read, StandardCharsets.UTF_8))
        client.close()
    }

    @Test
    fun `connect to an unreachable host returns 502`() {
        // Grab a port, close it, then CONNECT there.
        val dead = ServerSocket(0)
        val deadPort = dead.localPort
        dead.close()

        val client = proxySocket()
        client.getOutputStream().write(
            ("CONNECT 127.0.0.1:$deadPort HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$deadPort\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()
        val status = readResponseHead(client)
        assertTrue(status.contains("502"))
        client.close()
    }

    // ── Destination policy (SSRF / LAN protection) ─────────────────────────

    @Test
    fun `strict policy refuses plain http to a private destination`() {
        val strictProxy = HttpProxyServer("127.0.0.1", 0, ProxyStats(), DestinationPolicy.STRICT) {}
        strictProxy.start()
        try {
            val client = Socket("127.0.0.1", strictProxy.boundPort).apply { soTimeout = 10_000 }
            client.getOutputStream().write(
                ("GET http://127.0.0.1:80/ HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
            )
            client.getOutputStream().flush()
            val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
            assertTrue("expected 403, got: ${response.take(60)}", response.contains("403"))
            client.close()
        } finally {
            strictProxy.stop()
        }
    }

    @Test
    fun `strict policy refuses CONNECT to a private destination`() {
        val strictProxy = HttpProxyServer("127.0.0.1", 0, ProxyStats(), DestinationPolicy.STRICT) {}
        strictProxy.start()
        try {
            val client = Socket("127.0.0.1", strictProxy.boundPort).apply { soTimeout = 10_000 }
            client.getOutputStream().write(
                ("CONNECT 127.0.0.1:443 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:443\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
            )
            client.getOutputStream().flush()
            val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
            assertTrue("expected 403, got: ${response.take(60)}", response.contains("403"))
            client.close()
        } finally {
            strictProxy.stop()
        }
    }

    // ── Robustness (malformed input must never take the proxy down) ────────

    @Test
    fun `survives random garbage input and keeps serving`() {
        val random = Random(42)
        // Random short streams (with line breaks): exercises the parser. EOF
        // is forced so the proxy bails out instead of waiting for more input.
        repeat(20) {
            val client = proxySocket()
            try {
                val out = client.getOutputStream()
                val bytes = ByteArray(random.nextInt(1, 512))
                random.nextBytes(bytes)
                out.write(bytes)
                out.flush()
                client.shutdownOutput()
                val buf = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < deadline) {
                    val n = client.getInputStream().read(buf)
                    if (n < 0) break
                }
            } finally {
                client.close()
            }
        }
        // Streams with no line break at all hit the line-size cap.
        repeat(5) {
            val client = proxySocket()
            try {
                val bytes = ByteArray(20_000) { 'A'.code.toByte() }
                client.getOutputStream().write(bytes)
                client.getOutputStream().flush()
                client.shutdownOutput()
                runCatching { client.getInputStream().readBytes() }
            } finally {
                client.close()
            }
        }
        assertTrue(proxy.isRunning)

        // ...and a real request still works afterwards.
        val origin = startOrigin { socket ->
            readRequestLine(socket)
            writeResponse(socket.getOutputStream(), "still alive")
        }
        val client = proxySocket()
        client.getOutputStream().write(
            ("GET http://127.0.0.1:${origin.localPort}/ HTTP/1.1\r\n" +
                "Host: 127.0.0.1:${origin.localPort}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()
        val response = client.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        assertTrue(response.contains("still alive"))
        client.close()
    }

    @Test
    fun `oversized header lines are rejected without crashing`() {
        val client = proxySocket()
        client.getOutputStream().write(
            ("GET http://example.com/ HTTP/1.1\r\n" +
                "X-Big: " + "A".repeat(100_000) + "\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()
        runCatching { client.getInputStream().readBytes() }
        client.close()
        assertTrue(proxy.isRunning)
    }

    @Test
    fun `malformed chunked framing does not crash the proxy`() {
        val client = proxySocket()
        // A chunked request whose size line is garbage: the proxy must bail
        // out of the body copy gracefully.
        client.getOutputStream().write(
            ("POST http://example.com/ HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n" +
                "not-a-hex-size\r\n" +
                "garbage").toByteArray(StandardCharsets.ISO_8859_1),
        )
        client.getOutputStream().flush()
        runCatching { client.getInputStream().readBytes() }
        client.close()
        assertTrue(proxy.isRunning)
    }

    @Test
    fun `stop closes the listener`() {
        assertTrue(proxy.isRunning)
        proxy.stop()
        assertFalse(proxy.isRunning)
        // Re-start on the same instance works too.
        proxy.start()
        assertTrue(proxy.isRunning)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun readRequestLine(socket: Socket): String {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        val line = reader.readLine()
        // Consume the rest of the head so the handler can read a body if needed.
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        return line
    }

    private fun writeResponse(out: OutputStream, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        out.write(bytes)
        out.flush()
    }

    private fun readResponseHead(socket: Socket): String {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        val sb = StringBuilder()
        var line: String?
        while (true) {
            line = reader.readLine() ?: break
            if (line.isEmpty()) break
            sb.append(line).append('\n')
        }
        return sb.toString()
    }

    private fun readResponse(socket: Socket): String {
        // Head
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
        val head = StringBuilder()
        var contentLength = -1
        var line: String?
        while (true) {
            line = reader.readLine() ?: break
            if (line.isEmpty()) break
            head.append(line).append('\n')
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        val body = if (contentLength >= 0) {
            CharArray(contentLength).also { reader.read(it) }
        } else {
            CharArray(0)
        }
        return head.toString() + String(body)
    }
}
