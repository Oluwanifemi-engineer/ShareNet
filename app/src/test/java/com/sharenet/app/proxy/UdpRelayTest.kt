package com.sharenet.app.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * End-to-end relay test on real sockets (pure JVM):
 *
 *   fake client --wrapped packet--> relay --> echo server
 *   fake client <--wrapped reply--- relay <-- echo server
 *
 * The relay must unwrap the client's IP packet, forward the UDP payload to the
 * destination, wrap the reply back with swapped addresses, and return it.
 */
class UdpRelayTest {

    private val relays = mutableListOf<UdpRelayServer>()

    @After
    fun tearDown() {
        relays.forEach { it.stop() }
    }

    @Test
    fun `relays udp through to the destination and back`() {
        // "Internet": an echo server.
        val echo = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        Thread {
            val buf = ByteArray(2048)
            while (!echo.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    echo.receive(p)
                    echo.send(DatagramPacket(p.data, p.length, p.socketAddress))
                } catch (_: Exception) {
                    break
                }
            }
        }.apply { isDaemon = true }.start()

        // The relay (bind port 0 = ephemeral). PERMISSIVE: the test echoes
        // over loopback, which the production policy refuses by design.
        val relay = UdpRelayServer("127.0.0.1", 0, DestinationPolicy.PERMISSIVE) {}
        relay.start()
        relays.add(relay)
        val relayPort = relay.boundPort

        // Fake client: the client's tun IP with a random source port.
        val clientTunIp = "26.0.0.2"
        val clientPort = 4321
        val client = DatagramSocket()

        // Client sends one UDP packet addressed to the echo server.
        val payload = "ping-from-client".toByteArray(Charsets.UTF_8)
        val outgoing = Ipv4Codec.wrapUdp(
            srcIp = clientTunIp,
            srcPort = clientPort,
            dstIp = "127.0.0.1",
            dstPort = echo.localPort,
            payload = payload,
        )
        client.send(
            DatagramPacket(
                outgoing,
                outgoing.size,
                InetAddress.getByName("127.0.0.1"),
                relayPort,
            ),
        )

        // Expect the wrapped reply: src = echo server, dst = client tun.
        client.soTimeout = 5000
        val replyBuf = ByteArray(2048)
        val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
        client.receive(replyPacket)

        val parsed = Ipv4Codec.parse(replyBuf, replyPacket.length)!!
        assertEquals("127.0.0.1", parsed.srcIp)
        assertEquals(clientTunIp, parsed.dstIp)
        assertEquals(Ipv4Codec.PROTO_UDP, parsed.protocol)

        val srcPort = Ipv4Codec.read16(replyBuf, parsed.payloadOffset)
        val dstPort = Ipv4Codec.read16(replyBuf, parsed.payloadOffset + 2)
        assertEquals(echo.localPort, srcPort)
        assertEquals(clientPort, dstPort)

        val replyPayload = String(
            replyBuf,
            parsed.payloadOffset + Ipv4Codec.UDP_HEADER_LEN,
            parsed.payloadLength - Ipv4Codec.UDP_HEADER_LEN,
        )
        assertEquals("ping-from-client", replyPayload)
        assertEquals(1, relay.datagramsRelayed.get())
        assertEquals(1, relay.repliesSent.get())

        echo.close()
    }

    @Test
    fun `drops non-udp packets and counts them`() {
        val relay = UdpRelayServer("127.0.0.1", 0, DestinationPolicy.PERMISSIVE) {}
        relay.start()
        relays.add(relay)

        // A TCP packet (protocol 6) with a valid total length.
        val tcpPacket = ByteArray(40)
        tcpPacket[0] = 0x45.toByte()
        tcpPacket[2] = 0
        tcpPacket[3] = 40
        tcpPacket[9] = Ipv4Codec.PROTO_TCP.toByte()
        tcpPacket[12] = 26; tcpPacket[13] = 0; tcpPacket[14] = 0; tcpPacket[15] = 2
        tcpPacket[16] = 8; tcpPacket[17] = 8; tcpPacket[18] = 8; tcpPacket[19] = 8

        val client = DatagramSocket()
        client.send(
            DatagramPacket(
                tcpPacket,
                tcpPacket.size,
                InetAddress.getByName("127.0.0.1"),
                relay.boundPort,
            ),
        )
        // Give the relay a moment to process.
        Thread.sleep(200)
        assertEquals(1, relay.nonUdpDropped.get())
        assertEquals(0, relay.datagramsRelayed.get())
        client.close()
    }

    @Test
    fun `strict policy drops packets aimed at private destinations`() {
        val relay = UdpRelayServer("127.0.0.1", 0) {} // strict by default
        relay.start()
        relays.add(relay)

        // A UDP packet addressed to a private LAN host (the host's own LAN).
        val payload = "probe".toByteArray(Charsets.UTF_8)
        val packet = Ipv4Codec.wrapUdp(
            srcIp = "26.0.0.2",
            srcPort = 4321,
            dstIp = "192.168.1.10",
            dstPort = 80,
            payload = payload,
        )
        val client = DatagramSocket()
        client.send(
            DatagramPacket(
                packet,
                packet.size,
                InetAddress.getByName("127.0.0.1"),
                relay.boundPort,
            ),
        )
        Thread.sleep(200)
        assertEquals(1, relay.policyDropped.get())
        assertEquals(0, relay.datagramsRelayed.get())
        assertEquals(0, relay.flowsActive.get())
        client.close()
    }

    @Test
    fun `strict policy allows the p2p subnet`() {
        val relay = UdpRelayServer("127.0.0.1", 0) {} // strict by default
        relay.start()
        relays.add(relay)

        // A UDP packet addressed to another host on the hotspot itself.
        val payload = "local".toByteArray(Charsets.UTF_8)
        val packet = Ipv4Codec.wrapUdp(
            srcIp = "26.0.0.2",
            srcPort = 4321,
            dstIp = "192.168.49.5",
            dstPort = 9000,
            payload = payload,
        )
        val client = DatagramSocket()
        client.send(
            DatagramPacket(
                packet,
                packet.size,
                InetAddress.getByName("127.0.0.1"),
                relay.boundPort,
            ),
        )
        Thread.sleep(200)
        assertEquals(0, relay.policyDropped.get())
        assertEquals(1, relay.datagramsRelayed.get())
        client.close()
    }
}
