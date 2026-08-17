package com.sharenet.app.tunnel

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Client-side transport for the Tier-2 TCP tunnel.
 *
 * Owns the control socket to the host ([TcpTunnelServer]) and the threads
 * that move [TunnelProtocol] frames between it and [TcpTunnelCore]:
 *
 *  - a writer thread: core -> frames -> control socket (bounded queue)
 *  - a reader thread: control socket -> frames -> core
 *  - a ticker thread: drives the core's retransmission timer
 *
 * The core's `output` callback writes reply IP packets into the tun device
 * (via a callback supplied by the service). `onPayload` hands app bytes to
 * the service, which forwards them to the host's real connection. Since
 * payloads pass through a bounded queue, a slow host naturally backpressures
 * the app's TCP (the core ACKs only what it can enqueue).
 */
/** Why the control connection ended, surfaced to the owner. */
enum class DisconnectReason { CONTROL_LOST, AUTH_REJECTED }

class TcpTunnelClient(
    private val host: String,
    private val port: Int,
    private val writeToTun: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {},
    private val onDisconnected: (DisconnectReason) -> Unit = {},
    private val authPin: String? = null,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {

    private val running = AtomicBoolean(false)
    private var control: Socket? = null
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var tickerThread: Thread? = null

    private val outQueue = java.util.concurrent.LinkedBlockingQueue<Frame>()
    private val core = TcpTunnelCore(
        output = { packet -> writeToTun(packet) },
        onConnect = { connId, dstIp, dstPort ->
            log("tcp conn $connId -> $dstIp:$dstPort opening")
            enqueue(Frame(connId, TunnelProtocol.TYPE_CONNECT, connectPayload(dstIp, dstPort)))
        },
        onPayload = { connId, payload ->
            enqueue(Frame(connId, TunnelProtocol.TYPE_DATA, payload))
        },
        onClose = { connId ->
            enqueue(Frame(connId, TunnelProtocol.TYPE_CLOSE, ByteArray(0)))
        },
        onReset = { connId ->
            enqueue(Frame(connId, TunnelProtocol.TYPE_RST, ByteArray(0)))
        },
    )

    val stats: TcpTunnelCore get() = core
    val framesSent = AtomicLong(0)
    val framesReceived = AtomicLong(0)

    /** Feeds a captured TCP IP packet from the tun device. */
    fun onIpPacket(packet: ByteArray, length: Int = packet.size) {
        core.onIpPacket(packet, length)
    }

    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        val socket = try {
            Socket(host, port).apply {
                tcpNoDelay = true
                // Reads time out so the reader can detect a dead peer between
                // heartbeats (the server PONGs every HEARTBEAT_MS, so silence
                // for READ_TIMEOUT_MS means the control connection is gone).
                soTimeout = READ_TIMEOUT_MS.toInt()
            }
        } catch (e: Exception) {
            log("tcp tunnel connect to $host:$port failed: ${e.message}")
            return false
        }
        control = socket
        running.set(true)
        // Prove knowledge of the host's pairing PIN first — the host refuses
        // everything until it sees a valid AUTH frame, so this must be the
        // first frame on the connection.
        if (authPin != null) {
            enqueue(Frame(0, TunnelProtocol.TYPE_AUTH, authPin.toByteArray(Charsets.ISO_8859_1)))
        }
        readerThread = Thread { readLoop(socket) }
            .apply { name = "sharenet-tcp-read"; isDaemon = true }
        writerThread = Thread { writeLoop(socket) }
            .apply { name = "sharenet-tcp-write"; isDaemon = true }
        tickerThread = Thread { tickerLoop() }
            .apply { name = "sharenet-tcp-tick"; isDaemon = true }
        readerThread?.start()
        writerThread?.start()
        tickerThread?.start()
        log("tcp tunnel connected to $host:$port")
        return true
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        core.closeAll()
        runCatching { control?.close() }
        control = null
        outQueue.clear()
        log("tcp tunnel stopped")
    }

    // ── Writer ──────────────────────────────────────────────────────────────

    private fun enqueue(frame: Frame) {
        if (!running.get()) return
        try {
            outQueue.put(frame)
            framesSent.incrementAndGet()
        } catch (_: InterruptedException) {
        }
    }

    private fun writeLoop(socket: Socket) {
        val out = BufferedOutputStream(socket.getOutputStream(), BUFFER)
        while (running.get()) {
            val frame = try {
                outQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (!running.get()) break
            try {
                writeFrame(out, frame)
                out.flush()
            } catch (_: Exception) {
                break
            }
        }
    }

    // ── Reader ──────────────────────────────────────────────────────────────

    private fun readLoop(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream(), BUFFER)
        var alive = true
        var authRejected = false
        while (alive && running.get()) {
            val header = ByteArray(TunnelProtocol.HEADER_LEN)
            when (readFully(input, header)) {
                READ_OK -> {}
                READ_TIMEOUT -> {
                    // Nothing from the host for a full timeout window: the
                    // control connection is dead (host gone, radio dropped).
                    alive = false
                    break
                }
                READ_EOF -> {
                    alive = false
                    break
                }
            }
            val connId = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            val type = header[2].toInt() and 0xFF
            val len = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
            if (len > TunnelProtocol.MAX_PAYLOAD) {
                alive = false
                break
            }
            val payload = if (len > 0) {
                val p = ByteArray(len)
                if (readFully(input, p) != READ_OK) {
                    alive = false
                    break
                }
                p
            } else {
                ByteArray(0)
            }
            framesReceived.incrementAndGet()
            when (type) {
                TunnelProtocol.TYPE_CONNECTED -> core.onRemoteConnected(connId)
                TunnelProtocol.TYPE_REJECTED -> core.onRemoteRejected(connId)
                TunnelProtocol.TYPE_REMOTE_DATA -> core.onRemoteData(connId, payload)
                TunnelProtocol.TYPE_REMOTE_CLOSE -> core.onRemoteClose(connId)
                TunnelProtocol.TYPE_REMOTE_RST -> core.onRemoteReset(connId)
                TunnelProtocol.TYPE_PONG -> Unit // heartbeat reply — activity is enough
                TunnelProtocol.TYPE_AUTH_REJECTED -> {
                    // The host said the pairing PIN was wrong.
                    authRejected = true
                    alive = false
                }
            }
        }
        // Control connection ended: reset every app connection we owned and
        // tell the owner so it can stop routing (no silent failure).
        if (running.getAndSet(false)) {
            core.closeAll()
            runCatching { socket.close() }
            onDisconnected(
                if (authRejected) DisconnectReason.AUTH_REJECTED else DisconnectReason.CONTROL_LOST,
            )
        }
    }

    private fun tickerLoop() {
        var lastPingMs = 0L
        while (running.get()) {
            try {
                Thread.sleep(TICK_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
            val nowMs = System.currentTimeMillis()
            core.tick(nowMs)
            // Heartbeat: keep the control connection provably alive so the
            // host (and we) can distinguish "idle" from "dead".
            if (nowMs - lastPingMs >= heartbeatMs) {
                lastPingMs = nowMs
                enqueue(Frame(0, TunnelProtocol.TYPE_PING, ByteArray(0)))
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private class Frame(val connId: Int, val type: Int, val payload: ByteArray)

    private fun connectPayload(dstIp: String, dstPort: Int): ByteArray {
        val ip = InetAddress.getByName(dstIp).address
        val payload = ByteArray(ip.size + 2)
        System.arraycopy(ip, 0, payload, 0, ip.size)
        payload[ip.size] = ((dstPort shr 8) and 0xFF).toByte()
        payload[ip.size + 1] = (dstPort and 0xFF).toByte()
        return payload
    }

    private fun writeFrame(out: OutputStream, frame: Frame) {
        val payload = frame.payload
        val len = payload.size
        if (len > TunnelProtocol.MAX_PAYLOAD) {
            // Very large app writes are chunked by the writer.
            var off = 0
            while (off < len) {
                val chunk = minOf(TunnelProtocol.MAX_PAYLOAD, len - off)
                out.write((frame.connId shr 8) and 0xFF)
                out.write(frame.connId and 0xFF)
                out.write(frame.type)
                out.write((chunk shr 8) and 0xFF)
                out.write(chunk and 0xFF)
                out.write(payload, off, chunk)
                off += chunk
                out.flush()
            }
            return
        }
        out.write((frame.connId shr 8) and 0xFF)
        out.write(frame.connId and 0xFF)
        out.write(frame.type)
        out.write((len shr 8) and 0xFF)
        out.write(len and 0xFF)
        if (len > 0) out.write(payload)
    }

    /**
     * Reads [buf] fully. Returns [READ_OK], [READ_EOF] on clean close, or
     * [READ_TIMEOUT] when the socket read timed out (peer silent).
     */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var read = 0
        while (read < buf.size) {
            val n = try {
                input.read(buf, read, buf.size - read)
            } catch (e: java.net.SocketTimeoutException) {
                return READ_TIMEOUT
            } catch (e: IOException) {
                return READ_EOF
            }
            if (n < 0) return READ_EOF
            read += n
        }
        return READ_OK
    }

    companion object {
        private const val BUFFER = 8192
        private const val TICK_INTERVAL_MS = 200L
        private const val HEARTBEAT_MS = 15_000L
        private const val READ_TIMEOUT_MS = 45_000L

        private const val READ_OK = 0
        private const val READ_EOF = 1
        private const val READ_TIMEOUT = 2
    }
}
