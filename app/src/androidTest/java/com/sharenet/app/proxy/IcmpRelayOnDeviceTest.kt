package com.sharenet.app.proxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * On-device integration test for [IcmpRelayServer] with the real
 * [OsPingSocket] — runs a kernel ping socket on the actual hardware.
 *
 * Proves the full chain: crafted ICMP echo → UDP carrier → relay → kernel
 * ping socket → real internet destination → echo reply → wrapped IPv4 → back
 * to the client.
 *
 * Requires the device to have internet access (Wi-Fi or cellular).
 */
@RunWith(AndroidJUnit4::class)
class IcmpRelayOnDeviceTest {

    private val relays = mutableListOf<IcmpRelayServer>()

    @After
    fun tearDown() {
        relays.forEach { runCatching { it.stop() } }
    }

    /** ICMP checksum (used to build a valid echo request). */
    private fun icmpChecksum(data: ByteArray): Int {
        var sum = 0L
        var i = 0
        while (i < data.size - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < data.size) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    /** Builds a minimal IPv4 packet wrapping an ICMP echo request. */
    private fun buildEchoRequest(
        srcIp: String = "26.0.0.2",
        dstIp: String,
        icmpId: Int,
        seq: Int,
        data: ByteArray = "ping-test".toByteArray(),
    ): ByteArray {
        // ICMP echo request: type=8, code=0, checksum, id, seq, data
        val icmpBody = byteArrayOf(
            8, 0, // type, code
            0, 0, // checksum (placeholder)
            ((icmpId shr 8) and 0xFF).toByte(), (icmpId and 0xFF).toByte(),
            ((seq shr 8) and 0xFF).toByte(), (seq and 0xFF).toByte(),
        ) + data
        val cs = icmpChecksum(icmpBody)
        icmpBody[2] = ((cs shr 8) and 0xFF).toByte()
        icmpBody[3] = (cs and 0xFF).toByte()

        // IPv4 wrapping
        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address
        val total = 20 + icmpBody.size
        val pkt = ByteArray(total)
        pkt[0] = 0x45.toByte() // v4, ihl=5
        pkt[2] = ((total shr 8) and 0xFF).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[8] = 64 // ttl
        pkt[9] = 1  // ICMP
        System.arraycopy(src, 0, pkt, 12, 4)
        System.arraycopy(dst, 0, pkt, 16, 4)
        val ipCs = Ipv4Codec.checksum(pkt, 0, 20)
        pkt[10] = ((ipCs shr 8) and 0xFF).toByte()
        pkt[11] = (ipCs and 0xFF).toByte()
        System.arraycopy(icmpBody, 0, pkt, 20, icmpBody.size)
        return pkt
    }

    @Test
    fun relay_receives_udp_packet_on_bound_port() {
        // Bind to 0.0.0.0 (all interfaces) — on some Android builds,
        // binding to a specific loopback address drops inbound traffic
        // from a DatagramSocket in the same process.
        val relay = IcmpRelayServer("0.0.0.0", 0, pingSocketFactory = { null }) {}
        relays.add(relay)
        relay.start()
        val port = relay.boundPort
        assertTrue("relay should have a valid bound port", port > 0)

        val client = DatagramSocket()
        val data = "hello-relay".toByteArray()
        client.send(
            DatagramPacket(data, data.size, InetAddress.getLoopbackAddress(), port),
        )
        Thread.sleep(500)

        // The relay should have received the packet and dropped it
        // (it's not a valid IPv4+ICMP packet, so malformedDropped++).
        assertTrue("relay should have dropped the malformed packet",
            relay.malformedDropped.get() >= 1)
        client.close()
    }

    @Test
    fun relay_pingsthrough_to_internet_and_returns_reply() {
        val relay = IcmpRelayServer(
            "0.0.0.0",
            0,
            DestinationPolicy.PERMISSIVE,
            pingSocketFactory = { id -> OsPingSocket.create(id) },
        ) {}
        relays.add(relay)
        relay.start()

        val client = DatagramSocket()
        val icmpId = 0xABCD

        // Build and send an ICMP echo request to a public host.
        val request = buildEchoRequest(
            dstIp = "1.1.1.1",
            icmpId = icmpId,
            seq = 1,
        )
        client.send(
            DatagramPacket(
                request, request.size,
                InetAddress.getLoopbackAddress(), relay.boundPort,
            ),
        )

        // Wait for the reply (ICMP round-trip should be < 500ms).
        client.soTimeout = 5000
        val replyBuf = ByteArray(2048)
        val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
        client.receive(replyPacket)

        val parsed = Ipv4Codec.parse(replyBuf, replyPacket.length)!!
        assertEquals(Ipv4Codec.PROTO_ICMP, parsed.protocol)

        // The ICMP payload starts at the IP header length.
        val icmpType = replyBuf[parsed.payloadOffset].toInt() and 0xFF
        assertEquals("reply should be echo reply (type 0)", 0, icmpType)

        val replyId = ((replyBuf[parsed.payloadOffset + 4].toInt() and 0xFF) shl 8) or
            (replyBuf[parsed.payloadOffset + 5].toInt() and 0xFF)
        assertEquals("id should match our request", icmpId, replyId)

        assertTrue("relay should have relayed at least one echo request",
            relay.echoRequestsRelayed.get() >= 1)
        assertTrue("relay should have sent at least one reply",
            relay.repliesSent.get() >= 1)

        client.close()
    }

    @Test
    fun relay_replies_from_different_destination() {
        val relay = IcmpRelayServer(
            "0.0.0.0",
            0,
            DestinationPolicy.PERMISSIVE,
            pingSocketFactory = { id -> OsPingSocket.create(id) },
        ) {}
        relays.add(relay)
        relay.start()

        val client = DatagramSocket()
        val icmpId = 0x1234

        // Try 8.8.8.8 (Google DNS).
        val request = buildEchoRequest(dstIp = "8.8.8.8", icmpId = icmpId, seq = 7)
        client.send(
            DatagramPacket(request, request.size, InetAddress.getLoopbackAddress(), relay.boundPort),
        )

        client.soTimeout = 5000
        val replyBuf = ByteArray(2048)
        val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
        client.receive(replyPacket)

        val parsed = Ipv4Codec.parse(replyBuf, replyPacket.length)!!
        assertEquals(0, replyBuf[parsed.payloadOffset].toInt() and 0xFF) // type 0
        val replyId = ((replyBuf[parsed.payloadOffset + 4].toInt() and 0xFF) shl 8) or
            (replyBuf[parsed.payloadOffset + 5].toInt() and 0xFF)
        assertEquals(icmpId, replyId)

        client.close()
    }

    @Test
    fun relay_drops_private_destination() {
        val relay = IcmpRelayServer(
            "0.0.0.0",
            0,
            pingSocketFactory = { id -> OsPingSocket.create(id) },
        ) {} // strict by default
        relays.add(relay)
        relay.start()

        val client = DatagramSocket()
        val request = buildEchoRequest(dstIp = "192.168.1.1", icmpId = 0x9999, seq = 1)
        client.send(
            DatagramPacket(request, request.size, InetAddress.getLoopbackAddress(), relay.boundPort),
        )
        Thread.sleep(300)
        assertEquals(1, relay.policyDropped.get())
        assertEquals(0, relay.echoRequestsRelayed.get())

        client.close()
    }

    @Test
    fun relay_gracefully_handles_unsupported_kernel() {
        val relay = IcmpRelayServer(
            "0.0.0.0",
            0,
            DestinationPolicy.PERMISSIVE,
            pingSocketFactory = { null }, // kernel refuses
        ) {}
        relays.add(relay)
        relay.start()

        val client = DatagramSocket()
        val request = buildEchoRequest(dstIp = "1.1.1.1", icmpId = 0xBEEF, seq = 1)
        client.send(
            DatagramPacket(request, request.size, InetAddress.getLoopbackAddress(), relay.boundPort),
        )
        Thread.sleep(300)
        assertEquals(1, relay.pingUnsupported.get())
        assertEquals(0, relay.echoRequestsRelayed.get())

        client.close()
    }
}
