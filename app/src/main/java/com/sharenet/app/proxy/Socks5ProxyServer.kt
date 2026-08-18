package com.sharenet.app.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A lightweight SOCKS5 proxy server — enables ALL TCP/UDP traffic to flow
 * through ShareNet, not just HTTP.
 *
 * Supported SOCKS5 features:
 *  - Username/None authentication (no-auth for ease of use)
 *  - CONNECT command (TCP tunneling)
 *  - UDP ASSOCIATE (UDP relay)
 *  - IPv4 and domain name destinations
 *
 * When paired with a transparent tunnel (redsocks) on the PC, this enables
 * chat apps, games, and all non-HTTP apps to work seamlessly.
 *
 * Pure JVM — no Android imports.
 */
class Socks5ProxyServer(
    private val bindHost: String,
    private val port: Int,
    private val stats: ProxyStats,
    private val destinationPolicy: DestinationPolicy = DestinationPolicy.STRICT,
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
    ) { r -> Thread(r, "sharenet-socks5").apply { isDaemon = true } }

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
            log("socks5 listening on $bindHost:$boundPort")
            Thread { acceptLoop(ss) }
                .apply { name = "sharenet-socks5-accept"; isDaemon = true }
                .start()
        } catch (e: IOException) {
            running.set(false)
            throw ProxyBindException("socks5 bind failed on $bindHost:$port", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { acceptSocket?.close() }
        acceptSocket = null
        synchronized(clientSockets) {
            clientSockets.toList().forEach { runCatching { it.close() } }
            clientSockets.clear()
        }
        executor.shutdownNow()
        log("socks5 stopped")
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
                if (running.get()) log("socks5 accept failed: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream(), BUFFER)
            val output = BufferedOutputStream(socket.getOutputStream(), BUFFER)

            // SOCKS5 handshake: method negotiation
            if (!handleAuth(input, output)) return

            // SOCKS5 request
            handleRequest(socket, input, output)
        } catch (_: Exception) {
            // EOF / timeout / reset
        } finally {
            runCatching { socket.close() }
            clientSockets.remove(socket)
            stats.activeConnections.decrementAndGet()
        }
    }

    /**
     * SOCKS5 method negotiation.
     * Client sends: VER | NMETHODS | METHODS
     * Server replies: VER | METHOD (0x00 = no auth)
     */
    private fun handleAuth(input: BufferedInputStream, output: BufferedOutputStream): Boolean {
        val ver = input.read()
        if (ver != 0x05) return false
        val nMethods = input.read()
        if (nMethods < 0) return false
        // Skip the method list (we only support no-auth)
        repeat(nMethods) { input.read() }

        // Reply: version 5, method 0x00 (no auth required)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()
        return true
    }

    /**
     * SOCKS5 request handling.
     * Client sends: VER | CMD | RSV | ATYP | DST.ADDR | DST.PORT
     */
    private fun handleRequest(
        clientSocket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        val ver = input.read()
        if (ver != 0x05) return
        val cmd = input.read()
        val rsv = input.read() // reserved
        val atyp = input.read()

        when (cmd) {
            CMD_CONNECT -> handleConnect(input, output, atyp)
            CMD_UDP_ASSOCIATE -> handleUdpAssociate(clientSocket, input, output)
            else -> {
                sendReply(output, REP_COMMAND_NOT_SUPPORTED)
                return
            }
        }
    }

    // ── CONNECT ──────────────────────────────────────────────────────────

    private fun handleConnect(
        input: BufferedInputStream,
        output: BufferedOutputStream,
        atyp: Int,
    ) {
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
            sendReply(output, REP_SUCCESS, origin.localAddress)

            // Bidirectional pump
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
            sendReply(output, REP_HOST_UNREACHABLE)
        } finally {
            runCatching { origin.close() }
        }
    }

    // ── UDP ASSOCIATE ────────────────────────────────────────────────────

    private fun handleUdpAssociate(
        clientSocket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ) {
        // Create a UDP socket for relay
        val udpSocket = DatagramSocket(0)
        try {
            udpSocket.soTimeout = UDP_IDLE_TIMEOUT_MS

            // Tell the client our UDP relay address
            val relayAddr = InetAddress.getByName(bindHost)
            val relayPort = udpSocket.localPort
            sendReply(output, REP_SUCCESS, relayAddr, relayPort)

            log("socks5 udp associate on $relayAddr:$relayPort")

            // Read incoming UDP packets and forward them
            val buf = ByteArray(65535)
            val clientUdpAddr = AtomicBoolean(false)
            var clientAddress: InetAddress? = null
            var clientPort = 0

            while (running.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket.receive(pkt)

                    // First packet tells us the client's UDP address
                    if (clientAddress == null) {
                        clientAddress = pkt.address
                        clientPort = pkt.port
                    }

                    // SOCKS5 UDP header: RSV(2) + FRAG(1) + ATYP + ADDR + PORT + DATA
                    val data = pkt.data
                    val len = pkt.length
                    if (len < 10) continue

                    // Skip RSV (2 bytes) + FRAG (1 byte)
                    val headerAtyp = data[3].toInt() and 0xFF
                    val (udpHost, udpPort, headerLen) = readUdpHeader(data, headerAtyp) ?: continue

                    // Forward the payload to the destination
                    val payload = data.copyOfRange(headerLen, len)
                    val destAddr = InetAddress.getByName(udpHost)
                    val destPkt = DatagramPacket(payload, payload.size, destAddr, udpPort)
                    udpSocket.send(destPkt)

                    stats.bytesFromClients.addAndGet(payload.size.toLong())
                } catch (_: java.net.SocketTimeoutException) {
                    // Idle timeout — close
                    break
                } catch (_: Exception) {
                    break
                }
            }
        } finally {
            udpSocket.close()
        }
    }

    private fun readUdpHeader(data: ByteArray, atyp: Int): Triple<String, Int, Int>? {
        return when (atyp) {
            ATYP_IPV4 -> {
                if (data.size < 10) return null
                val addr = "${data[4].toInt() and 0xFF}.${data[5].toInt() and 0xFF}.${data[6].toInt() and 0xFF}.${data[7].toInt() and 0xFF}"
                val port = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
                Triple(addr, port, 10)
            }
            ATYP_DOMAIN -> {
                if (data.size < 5) return null
                val domainLen = data[4].toInt() and 0xFF
                if (data.size < 5 + domainLen + 2) return null
                val domain = String(data, 5, domainLen)
                val port = ((data[5 + domainLen].toInt() and 0xFF) shl 8) or (data[6 + domainLen].toInt() and 0xFF)
                Triple(domain, port, 5 + domainLen + 2)
            }
            else -> null
        }
    }

    // ── Address reading ──────────────────────────────────────────────────

    private fun readAddress(input: BufferedInputStream, atyp: Int): Pair<String, Int>? {
        return when (atyp) {
            ATYP_IPV4 -> {
                val addr = ByteArray(4)
                if (input.read(addr) < 4) return null
                val port = readPort(input)
                val host = "${addr[0].toInt() and 0xFF}.${addr[1].toInt() and 0xFF}.${addr[2].toInt() and 0xFF}.${addr[3].toInt() and 0xFF}"
                host to port
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

    // ── Reply helpers ────────────────────────────────────────────────────

    private fun sendReply(output: BufferedOutputStream, rep: Int, bindAddr: InetAddress? = null, bindPort: Int = 0) {
        val reply = ByteArray(10)
        reply[0] = 0x05 // VER
        reply[1] = rep.toByte() // REP
        reply[2] = 0x00 // RSV
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

    private fun pump(from: java.io.InputStream, to: java.io.OutputStream, counter: AtomicLong, done: AtomicBoolean) {
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

    companion object {
        private const val BUFFER = 64 * 1024  // 64 KB — large buffers for throughput
        private const val MAX_CONNECTIONS = 64
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val TUNNEL_JOIN_TIMEOUT_MS = 130_000L
        private const val UDP_IDLE_TIMEOUT_MS = 120_000

        // SOCKS5 commands
        private const val CMD_CONNECT = 0x01
        private const val CMD_UDP_ASSOCIATE = 0x03

        // SOCKS5 reply codes
        private const val REP_SUCCESS = 0x00
        private const val REP_CONNECTION_NOT_ALLOWED = 0x02
        private const val REP_HOST_UNREACHABLE = 0x04
        private const val REP_COMMAND_NOT_SUPPORTED = 0x07
        private const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08

        // SOCKS5 address types
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
    }
}
