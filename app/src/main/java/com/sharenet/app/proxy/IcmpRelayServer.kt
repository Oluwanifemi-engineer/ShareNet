package com.sharenet.app.proxy

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The host side of ICMP (ping) through the Tier-2 tunnel — rootless.
 *
 * Client phones in tunnel mode forward their captured ICMP echo requests
 * here over the Wi-Fi Direct link (UDP, like [UdpRelayServer]). This relay:
 *  - hands each request to a kernel "ping socket" ([PingSocket]) bound to the
 *    client's ICMP id. Stock Android's `ping_group_range` permits every UID
 *    (`0 2147483647`), so no raw socket or root is needed — the same
 *    mechanism the `ping` binary uses,
 *  - wraps each echo reply back into a full IPv4 packet addressed to the
 *    client's tun interface and returns it over the P2P link.
 *
 * Only ICMP echo requests are relayed; anything else is dropped and counted.
 * The [pingSocketFactory] is injected so the relay logic stays pure JVM and
 * integration-testable with real sockets ([OsPingSocket] is the Android
 * implementation).
 */
class IcmpRelayServer(
    private val bindHost: String,
    private val port: Int,
    private val destinationPolicy: DestinationPolicy = DestinationPolicy.STRICT,
    private val pingSocketFactory: (id: Int) -> PingSocket? = { null },
    private val log: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var relaySocket: DatagramSocket? = null
    private val flows = ConcurrentHashMap<Int, Flow>()
    private var receiveThread: Thread? = null
    private var sweeperThread: Thread? = null

    // Counters exposed for the UI / debugging.
    val echoRequestsRelayed = AtomicLong(0)
    val repliesSent = AtomicLong(0)
    val nonEchoDropped = AtomicLong(0)
    val malformedDropped = AtomicLong(0)
    val policyDropped = AtomicLong(0)
    val pingUnsupported = AtomicLong(0)
    val flowsActive = AtomicLong(0)

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    private class Flow(
        val ping: PingSocket,
        @Volatile var clientEndpoint: InetSocketAddress,
        @Volatile var clientTunIp: String,
        @Volatile var lastDst: String,
        @Volatile var lastActivityMs: Long,
    )

    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        try {
            val relay = DatagramSocket(null) // unbound: set options before bind
            relay.reuseAddress = true
            relay.soTimeout = 1000 // allows the receive loop to notice stop()
            relay.bind(InetSocketAddress(bindHost, port))
            boundPort = relay.localPort
            relaySocket = relay
            log("icmp relay listening on $bindHost:$port")
            receiveThread = Thread { receiveLoop(relay) }
                .apply { name = "sharenet-icmp-relay"; isDaemon = true }
            receiveThread?.start()
            sweeperThread = Thread { sweeperLoop() }
                .apply { name = "sharenet-icmp-sweeper"; isDaemon = true }
            sweeperThread?.start()
        } catch (e: SocketException) {
            running.set(false)
            throw ProxyBindException("icmp relay bind failed on $bindHost:$port", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { relaySocket?.close() }
        relaySocket = null
        for (flow in flows.values) {
            runCatching { flow.ping.close() }
        }
        flows.clear()
        flowsActive.set(0)
        log("icmp relay stopped")
    }

    private fun receiveLoop(relay: DatagramSocket) {
        val buf = ByteArray(2048)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                relay.receive(packet)
            } catch (e: SocketException) {
                if (!running.get()) break
                continue // SocketTimeoutException extends SocketException — keep looping
            } catch (_: Exception) {
                if (!running.get()) break
                continue
            }
            handleClientPacket(relay, packet)
        }
    }

    private fun handleClientPacket(relay: DatagramSocket, packet: DatagramPacket) {
        val ip = Ipv4Codec.parse(packet.data, packet.length)
        if (ip == null) { malformedDropped.incrementAndGet(); return }
        if (ip.protocol != Ipv4Codec.PROTO_ICMP) { nonEchoDropped.incrementAndGet(); return }
        if (ip.payloadLength < ICMP_HEADER_LEN) { malformedDropped.incrementAndGet(); return }
        val off = ip.payloadOffset
        val pktType = packet.data[off].toInt() and 0xFF
        if (pktType != TYPE_ECHO_REQUEST) { nonEchoDropped.incrementAndGet(); return }
        if (!destinationPolicy.allow(ip.dstIp)) { policyDropped.incrementAndGet(); return }
        val id = Ipv4Codec.read16(packet.data, off + 4)
        val clientEndpoint = packet.socketAddress as InetSocketAddress
        var flow = flows[id]
        if (flow == null) {
            flow = createFlow(id, ip.srcIp, clientEndpoint) ?: return
        } else {
            flow.clientEndpoint = clientEndpoint
            flow.clientTunIp = ip.srcIp
        }
        flow.lastDst = ip.dstIp
        flow.lastActivityMs = System.currentTimeMillis()
        try {
            flow.ping.send(
                ip.dstIp,
                packet.data,
                off + ICMP_HEADER_LEN,
                ip.payloadLength - ICMP_HEADER_LEN,
            )
            echoRequestsRelayed.incrementAndGet()
        } catch (_: Exception) {
            closeFlow(id, flow)
        }
    }

    private fun createFlow(
        id: Int,
        clientTunIp: String,
        clientEndpoint: InetSocketAddress,
    ): Flow? {
        val ping = try {
            pingSocketFactory(id)
        } catch (_: Exception) {
            null
        }
        if (ping == null) {
            pingUnsupported.incrementAndGet()
            return null
        }
        val flow = Flow(ping, clientEndpoint, clientTunIp, "", System.currentTimeMillis())
        flows[id] = flow
        flowsActive.incrementAndGet()
        Thread {
            replyLoop(id, flow)
        }.apply { name = "sharenet-icmp-flow"; isDaemon = true }.start()
        return flow
    }

    private fun replyLoop(id: Int, flow: Flow) {
        while (running.get()) {
            val reply = try {
                flow.ping.receive()
            } catch (_: Exception) {
                break // socket closed (flow expired or relay stopped)
            }
            if (reply == null) continue // receive timeout — keep polling
            val (icmp, srcIp) = reply
            val replySrc = srcIp.takeIf { it != "0.0.0.0" && it.isNotBlank() } ?: flow.lastDst
            val wrapped = Ipv4Codec.wrapIcmp(
                srcIp = replySrc,
                dstIp = flow.clientTunIp,
                payload = icmp,
            )
            flow.lastActivityMs = System.currentTimeMillis()
            try {
                relaySocket?.send(DatagramPacket(wrapped, wrapped.size, flow.clientEndpoint))
                repliesSent.incrementAndGet()
            } catch (_: Exception) {
                closeFlow(id, flow)
                return
            }
        }
    }

    private fun sweeperLoop() {
        while (running.get()) {
            try {
                Thread.sleep(SWEEP_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            for ((id, flow) in flows) {
                if (now - flow.lastActivityMs > FLOW_IDLE_MS) {
                    closeFlow(id, flow)
                }
            }
        }
    }

    private fun closeFlow(id: Int, flow: Flow) {
        if (flows.remove(id, flow)) {
            flowsActive.decrementAndGet()
        }
        runCatching { flow.ping.close() }
    }

    companion object {
        const val ICMP_HEADER_LEN = 8
        const val TYPE_ECHO_REQUEST = 8
        private const val FLOW_IDLE_MS = 60_000L
        private const val SWEEP_INTERVAL_MS = 30_000L
    }
}

/**
 * A kernel ICMP ping socket: send echo payloads, receive echo replies.
 * Implemented on Android by [OsPingSocket]; faked in the JVM tests.
 */
interface PingSocket {
    /** Sends [data] (the ICMP payload after the 8-byte header) to [dstIp]. */
    fun send(dstIp: String, data: ByteArray, offset: Int, length: Int)

    /** Blocks briefly for the next echo reply; null on receive timeout. */
    fun receive(): Pair<ByteArray, String>?

    fun close()
}
