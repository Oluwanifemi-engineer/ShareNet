package com.sharenet.app.proxy

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The host side of the Tier-2 UDP tunnel.
 *
 * Client phones running ShareNet's tunnel mode send their raw IP packets here
 * over the Wi-Fi Direct link (UDP). This relay:
 *  - forwards UDP payloads to the real destination through per-flow sockets
 *    (no root needed — normal sockets, exactly like the HTTP proxy),
 *  - wraps each reply back into an IPv4/UDP packet addressed to the client's
 *    tun interface and returns it over the P2P link.
 *
 * TCP/ICMP packets are dropped and counted — relaying those without root
 * requires full TCP sequence translation (tun2socks), which is out of v1
 * scope. TCP continues to work through the HTTP proxy, and the two coexist
 * because the client's P2P-subnet route bypasses its VPN.
 *
 * Pure JVM (java.net only) — integration-tested with real sockets.
 */
class UdpRelayServer(
    private val bindHost: String,
    private val port: Int,
    private val destinationPolicy: DestinationPolicy = DestinationPolicy.STRICT,
    private val log: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var relaySocket: DatagramSocket? = null
    private val flows = ConcurrentHashMap<String, Flow>()
    private var sweeperThread: Thread? = null
    private var receiveThread: Thread? = null

    // Counters exposed for the UI / debugging.
    val datagramsRelayed = AtomicLong(0)
    val repliesSent = AtomicLong(0)
    val nonUdpDropped = AtomicLong(0)
    val malformedDropped = AtomicLong(0)
    val policyDropped = AtomicLong(0)
    val flowsActive = AtomicLong(0)

    @Volatile
    var boundPort: Int = 0
        private set

    val isRunning: Boolean get() = running.get()

    private class Flow(
        val server: InetSocketAddress,
        val clientEndpoint: InetSocketAddress,
        val clientTunIp: String,
        val clientPort: Int,
        val socket: DatagramSocket,
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
            log("udp relay listening on $bindHost:$port")
            receiveThread = Thread { receiveLoop(relay) }
                .apply { name = "sharenet-udp-relay"; isDaemon = true }
            receiveThread?.start()
            sweeperThread = Thread { sweeperLoop() }
                .apply { name = "sharenet-udp-sweeper"; isDaemon = true }
            sweeperThread?.start()
        } catch (e: SocketException) {
            running.set(false)
            throw ProxyBindException("udp relay bind failed on $bindHost:$port", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { relaySocket?.close() }
        relaySocket = null
        for (flow in flows.values) {
            runCatching { flow.socket.close() }
        }
        flows.clear()
        flowsActive.set(0)
        log("udp relay stopped")
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
        if (ip == null) {
            malformedDropped.incrementAndGet()
            return
        }
        if (ip.protocol != Ipv4Codec.PROTO_UDP) {
            nonUdpDropped.incrementAndGet()
            return
        }
        if (ip.payloadLength < Ipv4Codec.UDP_HEADER_LEN) {
            malformedDropped.incrementAndGet()
            return
        }
        val srcPort = Ipv4Codec.read16(packet.data, ip.payloadOffset)
        val dstPort = Ipv4Codec.read16(packet.data, ip.payloadOffset + 2)
        val payloadOffset = ip.payloadOffset + Ipv4Codec.UDP_HEADER_LEN
        val payloadLength = ip.payloadLength - Ipv4Codec.UDP_HEADER_LEN
        val clientEndpoint = packet.socketAddress as InetSocketAddress

        val flowKey = "${ip.srcIp}:$srcPort->${ip.dstIp}:$dstPort"
        var flow = flows[flowKey]
        if (flow == null) {
            flow = createFlow(flowKey, ip, srcPort, dstPort, clientEndpoint)
            if (flow == null) return
        }
        flow.lastActivityMs = System.currentTimeMillis()
        try {
            flow.socket.send(DatagramPacket(packet.data, payloadOffset, payloadLength))
            datagramsRelayed.incrementAndGet()
        } catch (_: Exception) {
            closeFlow(flowKey, flow)
        }
    }

    private fun createFlow(
        key: String,
        ip: Ipv4Packet,
        srcPort: Int,
        dstPort: Int,
        clientEndpoint: InetSocketAddress,
    ): Flow? {
        // Never forward a client's packet into the host's private network.
        if (!destinationPolicy.allow(ip.dstIp)) {
            policyDropped.incrementAndGet()
            return null
        }
        return try {
            val socket = DatagramSocket()
            socket.connect(InetSocketAddress(ip.dstIp, dstPort))
            val flow = Flow(
                server = InetSocketAddress(ip.dstIp, dstPort),
                clientEndpoint = clientEndpoint,
                clientTunIp = ip.srcIp,
                clientPort = srcPort,
                socket = socket,
                lastActivityMs = System.currentTimeMillis(),
            )
            flows[key] = flow
            flowsActive.incrementAndGet()
            Thread {
                replyLoop(flow, key)
            }.apply { name = "sharenet-udp-flow"; isDaemon = true }.start()
            flow
        } catch (_: Exception) {
            null
        }
    }

    private fun replyLoop(flow: Flow, key: String) {
        val buf = ByteArray(2048)
        while (running.get()) {
            val reply = DatagramPacket(buf, buf.size)
            try {
                flow.socket.receive(reply)
            } catch (_: Exception) {
                break // socket closed (flow expired or relay stopped)
            }
            val wrapped = Ipv4Codec.wrapUdp(
                srcIp = flow.server.address.hostAddress ?: continue,
                srcPort = flow.server.port,
                dstIp = flow.clientTunIp,
                dstPort = flow.clientPort,
                payload = reply.data,
                payloadOffset = 0,
                payloadLength = reply.length,
            )
            flow.lastActivityMs = System.currentTimeMillis()
            try {
                relaySocket?.send(DatagramPacket(wrapped, wrapped.size, flow.clientEndpoint))
                repliesSent.incrementAndGet()
            } catch (_: Exception) {
                closeFlow(key, flow)
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
            for ((key, flow) in flows) {
                if (now - flow.lastActivityMs > FLOW_IDLE_MS) {
                    closeFlow(key, flow)
                }
            }
        }
    }

    private fun closeFlow(key: String, flow: Flow) {
        if (flows.remove(key, flow)) {
            flowsActive.decrementAndGet()
        }
        runCatching { flow.socket.close() }
    }

    companion object {
        private const val FLOW_IDLE_MS = 60_000L
        private const val SWEEP_INTERVAL_MS = 30_000L
    }
}
