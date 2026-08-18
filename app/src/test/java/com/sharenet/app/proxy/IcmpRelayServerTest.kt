package com.sharenet.app.proxy

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * End-to-end relay test on real sockets (pure JVM): the [PingSocket] is a
 * fake (the kernel ping socket is Android-only), but the UDP carrier, the
 * IPv4/ICMP parsing, and the reply wrapping are all real.
 *
 *   fake client --IPv4 ICMP echo--> relay --> fake ping socket
 *   fake client <--IPv4 ICMP reply-- relay <-- fake ping socket
 */
class IcmpRelayServerTest {

    private val relays = mutableListOf<IcmpRelayServer>()

    @After
    fun tearDown() {
        relays.forEach { it.stop() }
    }

    /** A fake kernel ping socket: records what was sent, answers when told. */
    private class FakePingSocket : PingSocket {
        @Volatile var sentDst: String? = null
        @Volatile var sentData: ByteArray? = null
        @Volatile var reply: Pair<ByteArray, String>? = null
        @Volatile var closed = false

        override fun send(dstIp: String, data: ByteArray, offset: Int, length: Int) {
            sentDst = dstIp
            sentData = data.copyOfRange(offset, offset + length)
        }

        override fun receive(): Pair<ByteArray, String>? {
            val r = reply
            if (r == null) {
                Thread.sleep(10) // act like a recv timeout
                return null
            }
            reply = null // one reply per receive, like a real kernel socket
            return r
        }

        override fun close() {
            closed = true
        }
    }

    private fun await(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("condition not met in time")
    }

    @Test
    fun `relays an echo request and wraps the reply back`() {
        val ping = FakePingSocket()
        val relay = IcmpRelayServer(
            "127.0.0.1",
            0,
            DestinationPolicy.PERMISSIVE,
            pingSocketFactory = { ping },
        ) {}
        relay.start()
        relays.add(relay)

        // The client's ICMP echo request: type 8, id 0xBEEF, seq 1, data "hi".
        val echo = byteArrayOf(
            8, 0, 0, 0, // type, code, checksum (not validated by the relay)
            0xBE.toByte(), 0xEF.toByte(), 0, 1, // id, seq
            'h'.code.toByte(), 'i'.code.toByte(),
        )
        val request = Ipv4Codec.wrapIcmp("26.0.0.2", "1.1.1.1", echo)

        val client = DatagramSocket()
        client.send(
            DatagramPacket(
                request,
                request.size,
                InetAddress.getByName("127.0.0.1"),
                relay.boundPort,
            ),
        )

        // The relay handed the payload after the 8-byte ICMP header to the ping socket.
        await { ping.sentData != null }
        assertEquals("1.1.1.1", ping.sentDst)
        assertArrayEquals(byteArrayOf('h'.code.toByte(), 'i'.code.toByte()), ping.sentData)
        assertEquals(1, relay.echoRequestsRelayed.get())
        assertEquals(1, relay.flowsActive.get())

        // The fake ping socket answers with an ICMP echo reply from the target.
        val replyIcmp = byteArrayOf(
            0, 0, 0, 0, // type 0 (echo reply)
            0xBE.toByte(), 0xEF.toByte(), 0, 1, // id 0xBEEF echoed
            'h'.code.toByte(), 'i'.code.toByte(),
        )
        ping.reply = replyIcmp to "1.1.1.1"

        client.soTimeout = 5000
        val replyBuf = ByteArray(2048)
        val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
        client.receive(replyPacket)

        val parsed = Ipv4Codec.parse(replyBuf, replyPacket.length)!!
        assertEquals("1.1.1.1", parsed.srcIp)
        assertEquals("26.0.0.2", parsed.dstIp)
        assertEquals(Ipv4Codec.PROTO_ICMP, parsed.protocol)
        assertEquals(0, replyBuf[parsed.payloadOffset].toInt() and 0xFF) // type 0
        assertEquals(0xBEEF, Ipv4Codec.read16(replyBuf, parsed.payloadOffset + 4))
        assertEquals(1, relay.repliesSent.get())

        client.close()
    }

    @Test
    fun `strict policy drops pings aimed at private destinations`() {
        val ping = FakePingSocket()
        val relay = IcmpRelayServer(
            "127.0.0.1",
            0,
            pingSocketFactory = { ping },
        ) {} // strict by default
        relay.start()
        relays.add(relay)

        val echo = byteArrayOf(8, 0, 0, 0, 0x12, 0x34, 0, 1)
        val request = Ipv4Codec.wrapIcmp("26.0.0.2", "192.168.1.10", echo)

        val client = DatagramSocket()
        client.send(
            DatagramPacket(
                request,
                request.size,
                InetAddress.getByName("127.0.0.1"),
                relay.boundPort,
            ),
        )
        Thread.sleep(200)
        assertEquals(1, relay.policyDropped.get())
        assertEquals(0, relay.echoRequestsRelayed.get())
        assertEquals(0, relay.flowsActive.get())
        assertEquals(null, ping.sentData)
        client.close()
    }

    @Test
    fun `drops non-echo icmp and counts it`() {
        val ping = FakePingSocket()
        val relay = IcmpRelayServer(
            "127.0.0.1",
            0,
            DestinationPolicy.PERMISSIVE,
            pingSocketFactory = { ping },
        ) {}
        relay.start()
        relays.add(relay)

        // Type 3 (destination unreachable) — not an echo request.
        val icmp = byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0)
        val request = Ipv4Codec.wrapIcmp("26.0.0.2", "1.1.1.1", icmp)

        val client = DatagramSocket()
        client.send(
            DatagramPacket(
                request,
                request.size,
                InetAddress.getByName("127.0.0.1"),
                relay.boundPort,
            ),
        )
        Thread.sleep(200)
        assertEquals(1, relay.nonEchoDropped.get())
        assertEquals(0, relay.echoRequestsRelayed.get())
        client.close()
    }

    @Test
    fun `drops packets when the ping socket cannot be created`() {
        val relay = IcmpRelayServer(
            "127.0.0.1",
            0,
            DestinationPolicy.PERMISSIVE,
            pingSocketFactory = { null }, // kernel refused
        ) {}
        relay.start()
        relays.add(relay)

        val echo = byteArrayOf(8, 0, 0, 0, 0x12, 0x34, 0, 1)
        val request = Ipv4Codec.wrapIcmp("26.0.0.2", "1.1.1.1", echo)

        val client = DatagramSocket()
        client.send(
            DatagramPacket(
                request,
                request.size,
                InetAddress.getByName("127.0.0.1"),
                relay.boundPort,
            ),
        )
        Thread.sleep(200)
        assertEquals(1, relay.pingUnsupported.get())
        assertEquals(0, relay.echoRequestsRelayed.get())
        assertEquals(0, relay.flowsActive.get())
        client.close()
    }
}
