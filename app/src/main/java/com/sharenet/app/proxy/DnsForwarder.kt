package com.sharenet.app.proxy

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The host side of DNS for the Wi-Fi Direct network.
 *
 * Devices that join the P2P group receive the group owner's address
 * (e.g. 192.168.49.1) as their DNS server from the group's DHCP. This
 * forwarder listens on that address and relays each query to the phone's real
 * upstream resolvers, then sends the answer back. Without it, clients would
 * get an IP but every name lookup would time out.
 *
 * The transaction ID is preserved end-to-end (we pass the query through
 * verbatim), so nothing needs rewriting. Each query gets a fresh outbound
 * socket with a short timeout, and upstream servers are tried in order.
 *
 * Pure JVM (java.net only) — integration-tested with real sockets.
 */
class DnsForwarder(
    private val bindHost: String,
    private val port: Int,
    private val upstreamServers: List<InetSocketAddress>,
    private val log: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var receiveThread: Thread? = null

    // A handful of workers is plenty — DNS is tiny and quick.
    private val executor = ThreadPoolExecutor(
        1,
        WORKERS,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(QUEUE_CAPACITY),
    ) { r -> Thread(r, "sharenet-dns").apply { isDaemon = true } }

    val queriesAnswered = AtomicLong(0)
    val queriesFailed = AtomicLong(0)

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        try {
            val s = DatagramSocket(null) // unbound: set options before bind
            s.reuseAddress = true
            s.soTimeout = 1000 // allows the receive loop to notice stop()
            s.bind(InetSocketAddress(bindHost, port))
            boundPort = s.localPort
            socket = s
            log("dns forwarder listening on $bindHost:$boundPort " +
                "-> ${upstreamServers.joinToString { "${it.address.hostAddress}:${it.port}" }}")
            receiveThread = Thread { receiveLoop(s) }
                .apply { name = "sharenet-dns-recv"; isDaemon = true }
            receiveThread?.start()
        } catch (e: SocketException) {
            running.set(false)
            throw ProxyBindException("dns bind failed on $bindHost:$port", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { socket?.close() }
        socket = null
        executor.shutdownNow()
        log("dns forwarder stopped")
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(RECV_BUFFER)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (e: SocketException) {
                break
            } catch (_: Exception) {
                if (!running.get()) break
                continue
            }
            val query = packet.data.copyOf(packet.length)
            val client = packet.socketAddress as InetSocketAddress
            try {
                executor.execute { resolve(query, client) }
            } catch (_: Exception) {
                queriesFailed.incrementAndGet()
            }
        }
    }

    private fun resolve(query: ByteArray, client: InetSocketAddress) {
        if (upstreamServers.isEmpty()) {
            queriesFailed.incrementAndGet()
            return
        }
        DatagramSocket().use { out ->
            out.soTimeout = QUERY_TIMEOUT_MS
            for (server in upstreamServers) {
                try {
                    out.send(DatagramPacket(query, query.size, server))
                    val buf = ByteArray(RECV_BUFFER)
                    val reply = DatagramPacket(buf, buf.size)
                    out.receive(reply)
                    val answer = reply.data.copyOf(reply.length)
                    // The upstream echoes the transaction ID; refuse mismatches
                    // so we never hand a client a response meant for someone else.
                    if (answer.size < 2 || answer[0] != query[0] || answer[1] != query[1]) {
                        continue
                    }
                    socket?.send(DatagramPacket(answer, answer.size, client))
                    queriesAnswered.incrementAndGet()
                    return
                } catch (_: Exception) {
                    // try the next upstream
                }
            }
            queriesFailed.incrementAndGet()
        }
    }

    companion object {
        /** Builds a forwarder for host addresses on the standard DNS port. */
        fun forHosts(
            bindHost: String,
            port: Int,
            upstreamHosts: List<InetAddress>,
            log: (String) -> Unit = {},
        ): DnsForwarder = DnsForwarder(
            bindHost,
            port,
            upstreamHosts.map { InetSocketAddress(it, DNS_PORT) },
            log,
        )

        private const val DNS_PORT = 53
        private const val WORKERS = 4
        private const val QUEUE_CAPACITY = 256
        private const val RECV_BUFFER = 4096
        private const val QUERY_TIMEOUT_MS = 3_000
    }
}
