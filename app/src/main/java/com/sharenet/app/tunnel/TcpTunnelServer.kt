package com.sharenet.app.tunnel

import com.sharenet.app.proxy.DestinationPolicy
import com.sharenet.app.proxy.Ipv4Codec
import com.sharenet.app.proxy.ProxyBindException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Host side of the Tier-2 TCP tunnel.
 *
 * Each client phone opens ONE reliable TCP control connection here (over the
 * Wi-Fi Direct link, which is already reliable). Over it, app connections are
 * multiplexed with [TunnelProtocol] frames carrying a connId. For every
 * CONNECT the host opens a REAL socket to the destination and pumps bytes
 * both ways — the client's mini-TCP-stack ([TcpTunnelCore]) does the sequence
 * translation, so the host is a plain byte relay.
 *
 * Pure JVM (java.net only) — integration-tested with real sockets.
 */
class TcpTunnelServer(
    private val bindHost: String,
    private val port: Int,
    private val authPin: String? = null,
    private val destinationPolicy: DestinationPolicy = DestinationPolicy.STRICT,
    private val log: (String) -> Unit = {},
) {
    init {
        require(authPin == null || authPin.length >= MIN_PIN_LENGTH) {
            "pairing PIN must be at least $MIN_PIN_LENGTH characters"
        }
    }

    private val running = AtomicBoolean(false)
    private var listenSocket: ServerSocket? = null
    // Live control connections, so stop() can close them and every client
    // learns immediately instead of hanging until its heartbeat times out.
    private val controlSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val realConns = ConcurrentHashMap<Int, RealConn>()
    // connIds whose app side reset before/while the real socket connects;
    // CONNECTED is suppressed and the socket closed when it lands.
    private val abortedConnIds = ConcurrentHashMap.newKeySet<Int>()
    // App DATA frames that arrived while the real socket was still opening;
    // flushed once the connection is established (the client ACKs app data
    // optimistically, so we must not drop it). Bounded per connection so a
    // stuck connect (or a malicious client) cannot grow host memory forever.
    private val pendingData = ConcurrentHashMap<Int, PendingBuffer>()

    private class PendingBuffer {
        val queue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        @Volatile var bytes = 0L
    }

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "sharenet-tcp-relay").apply { isDaemon = true }
    }

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    // Stats.
    val connsAccepted = AtomicLong(0)
    val connsRejected = AtomicLong(0)
    val bytesClientToServer = AtomicLong(0)
    val bytesServerToClient = AtomicLong(0)

    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindHost, port))
            listenSocket = ss
            boundPort = ss.localPort
            log("tcp tunnel relay listening on $bindHost:$boundPort")
            executor.execute { acceptLoop(ss) }
        } catch (e: IOException) {
            running.set(false)
            throw ProxyBindException("tcp relay bind failed on $bindHost:$port", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { listenSocket?.close() }
        listenSocket = null
        for (control in controlSockets) {
            runCatching { control.close() }
        }
        controlSockets.clear()
        for (conn in realConns.values) {
            runCatching { conn.real.close() }
        }
        realConns.clear()
        abortedConnIds.clear()
        pendingData.clear()
        executor.shutdownNow()
        log("tcp tunnel relay stopped")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val control = try {
                ss.accept()
            } catch (e: IOException) {
                if (running.get()) log("accept failed: ${e.message}")
                continue
            }
            try {
                executor.execute { handleControlConnection(control) }
            } catch (_: Exception) {
                runCatching { control.close() }
            }
        }
    }

    /**
     * One client phone. Reads [TunnelProtocol] frames, opens/closes real
     * sockets, and pumps data back as REMOTE_* frames.
     */
    private fun handleControlConnection(control: Socket) {
        controlSockets.add(control)
        try {
            // The client heartbeats every HEARTBEAT_MS; a read timeout means
            // the client vanished (radio drop, app killed) and we must release
            // every real socket it owned instead of leaking them.
            control.soTimeout = CONTROL_IDLE_MS
            val input = BufferedInputStream(control.getInputStream(), BUFFER)
            val output = BufferedOutputStream(control.getOutputStream(), BUFFER)
            val lock = Any()
            // A client must prove knowledge of the host's pairing PIN before
            // it may open any real connection; until then only AUTH (and
            // heartbeats) are accepted.
            var authenticated = authPin == null
            while (running.get()) {
                val header = ByteArray(TunnelProtocol.HEADER_LEN)
                readFully(input, header) ?: break
                val connId = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                val type = header[2].toInt() and 0xFF
                val len = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
                if (len > TunnelProtocol.MAX_PAYLOAD) break
                val payload = if (len > 0) {
                    val p = ByteArray(len)
                    readFully(input, p) ?: break
                    p
                } else {
                    ByteArray(0)
                }

                if (!authenticated) {
                    when (type) {
                        TunnelProtocol.TYPE_AUTH -> {
                            val expected = authPin?.toByteArray(Charsets.ISO_8859_1)
                            val ok = expected != null && payload.size == expected.size &&
                                MessageDigest.isEqual(payload, expected)
                            if (ok) {
                                authenticated = true
                            } else {
                                // Wrong PIN: tell the client and close. The
                                // client sends AUTH once per connect, so there
                                // is no point waiting for retries — it will
                                // reconnect and try again with a new PIN.
                                synchronized(lock) {
                                    runCatching {
                                        writeFrame(output, 0, TunnelProtocol.TYPE_AUTH_REJECTED, ByteArray(0))
                                        output.flush()
                                    }
                                }
                                break
                            }
                        }
                        TunnelProtocol.TYPE_PING -> {
                            // Heartbeat: answer so the client knows we are alive.
                            synchronized(lock) {
                                writeFrame(output, 0, TunnelProtocol.TYPE_PONG, ByteArray(0))
                                output.flush()
                            }
                        }
                        else -> break // protocol violation before auth: refuse
                    }
                    continue
                }

                when (type) {
                    TunnelProtocol.TYPE_PING -> {
                        // Heartbeat: answer so the client knows we are alive.
                        synchronized(lock) {
                            writeFrame(output, 0, TunnelProtocol.TYPE_PONG, ByteArray(0))
                            output.flush()
                        }
                    }
                    TunnelProtocol.TYPE_CONNECT -> {
                        if (payload.size < 6) continue
                        val dstIp = Ipv4Codec.inet4(payload, 0)
                        val dstPort = ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
                        openRealConn(control, output, lock, connId, dstIp, dstPort)
                    }
                    TunnelProtocol.TYPE_DATA -> {
                        if (payload.isEmpty()) continue
                        val conn = realConns[connId]
                        if (conn != null) {
                            writeReal(connId, conn, payload)
                        } else if (abortedConnIds.contains(connId)) {
                            // Client reset before the socket opened: drop.
                        } else {
                            // Still connecting: buffer, but bounded.
                            val buf = pendingData.computeIfAbsent(connId) { PendingBuffer() }
                            if (buf.bytes + payload.size > MAX_PENDING_BYTES) {
                                // The real socket is not coming up in time.
                                // Reject rather than buffer unboundedly (the
                                // client ACKed optimistically, so dropping the
                                // stream would corrupt it — a reset is the
                                // honest failure).
                                abortedConnIds.add(connId)
                                pendingData.remove(connId)
                                closeRealConn(connId, sendRst = false)
                                synchronized(lock) {
                                    runCatching {
                                        writeFrame(output, connId, TunnelProtocol.TYPE_REJECTED, ByteArray(0))
                                        output.flush()
                                    }
                                }
                            } else {
                                buf.queue.add(payload)
                                buf.bytes += payload.size
                            }
                        }
                    }
                    TunnelProtocol.TYPE_CLOSE -> {
                        val conn = realConns[connId] ?: continue
                        runCatching { conn.real.shutdownOutput() }
                    }
                    TunnelProtocol.TYPE_RST -> {
                        abortedConnIds.add(connId)
                        closeRealConn(connId, sendRst = false)
                    }
                }
            }
        } catch (_: Exception) {
            // control connection died — tear down everything it owned
        } finally {
            // A phone going away takes all of its connections with it.
            for (conn in realConns.values.toList()) {
                if (conn.control === control) {
                    closeRealConn(conn.id, sendRst = false)
                }
            }
            runCatching { control.close() }
            controlSockets.remove(control)
        }
    }

    private fun openRealConn(
        control: Socket,
        controlOut: OutputStream,
        lock: Any,
        connId: Int,
        dstIp: String,
        dstPort: Int,
    ) {
        executor.execute {
            // Never open a real socket into the host's private network.
            if (!destinationPolicy.allow(dstIp)) {
                connsRejected.incrementAndGet()
                abortedConnIds.remove(connId)
                pendingData.remove(connId)
                synchronized(lock) {
                    runCatching {
                        writeFrame(controlOut, connId, TunnelProtocol.TYPE_REJECTED, ByteArray(0))
                        controlOut.flush()
                    }
                }
                return@execute
            }
            val real = Socket()
            try {
                real.connect(InetSocketAddress(InetAddress.getByName(dstIp), dstPort), CONNECT_TIMEOUT_MS)
                if (abortedConnIds.remove(connId)) {
                    runCatching { real.close() }
                    return@execute
                }
                real.soTimeout = 0
                val conn = RealConn(connId, control, controlOut, lock, real)
                realConns[connId] = conn
                // Flush any app data that arrived while connecting.
                pendingData.remove(connId)?.queue?.forEach {
                    writeReal(connId, conn, it)
                }
                synchronized(lock) {
                    writeFrame(controlOut, connId, TunnelProtocol.TYPE_CONNECTED, ByteArray(0))
                    controlOut.flush()
                }
                connsAccepted.incrementAndGet()
                log("tcp conn $connId -> $dstIp:$dstPort established")
                pumpServerToClient(conn)
            } catch (e: Exception) {
                connsRejected.incrementAndGet()
                abortedConnIds.remove(connId)
                pendingData.remove(connId)
                synchronized(lock) {
                    runCatching {
                        writeFrame(controlOut, connId, TunnelProtocol.TYPE_REJECTED, ByteArray(0))
                        controlOut.flush()
                    }
                }
                log("tcp conn $connId -> $dstIp:$dstPort rejected: ${e.message}")
                runCatching { real.close() }
            }
        }
    }

    private fun writeReal(connId: Int, conn: RealConn, payload: ByteArray) {
        try {
            conn.real.getOutputStream().write(payload)
            conn.real.getOutputStream().flush()
            bytesClientToServer.addAndGet(payload.size.toLong())
        } catch (_: Exception) {
            closeRealConn(connId, sendRst = false)
        }
    }

    /** real socket -> client (as REMOTE_DATA frames; REMOTE_CLOSE on EOF). */
    private fun pumpServerToClient(conn: RealConn) {
        val buf = ByteArray(BUFFER)
        try {
            val realIn = BufferedInputStream(conn.real.getInputStream(), BUFFER)
            while (running.get()) {
                val n = realIn.read(buf)
                if (n < 0) {
                    synchronized(conn.lock) {
                        writeFrame(conn.controlOut, conn.id, TunnelProtocol.TYPE_REMOTE_CLOSE, ByteArray(0))
                        conn.controlOut.flush()
                    }
                    break
                }
                if (n > 0) {
                    bytesServerToClient.addAndGet(n.toLong())
                    synchronized(conn.lock) {
                        writeFrame(conn.controlOut, conn.id, TunnelProtocol.TYPE_REMOTE_DATA, buf, n)
                        conn.controlOut.flush()
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(conn.lock) {
                runCatching {
                    writeFrame(conn.controlOut, conn.id, TunnelProtocol.TYPE_REMOTE_RST, ByteArray(0))
                    conn.controlOut.flush()
                }
            }
        } finally {
            closeRealConn(conn.id, sendRst = false)
        }
    }

    private fun closeRealConn(connId: Int, sendRst: Boolean) {
        val conn = realConns.remove(connId) ?: return
        runCatching { conn.real.close() }
    }

    private fun writeFrame(out: OutputStream, connId: Int, type: Int, payload: ByteArray, len: Int = payload.size) {
        out.write((connId shr 8) and 0xFF)
        out.write(connId and 0xFF)
        out.write(type)
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        if (len > 0) out.write(payload, 0, len)
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val n = try {
                input.read(buf, read, buf.size - read)
            } catch (e: IOException) {
                if (e is java.net.SocketTimeoutException) throw e
                return false
            }
            if (n < 0) return false
            read += n
        }
        return true
    }

    private class RealConn(
        val id: Int,
        val control: Socket,
        val controlOut: OutputStream,
        val lock: Any,
        val real: Socket,
    )

    companion object {
        private const val BUFFER = 8192
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val MIN_PIN_LENGTH = 4
        private const val MAX_PENDING_BYTES = 512 * 1024L
        // Client PINGs every 15s; allow ~3 missed cycles before declaring the
        // control connection dead and releasing its sockets.
        private const val CONTROL_IDLE_MS = 60_000
    }
}
