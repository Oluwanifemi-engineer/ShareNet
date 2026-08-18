package com.sharenet.app.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4CodecTest {

    @Test
    fun `wraps a udp packet that parses back with matching fields`() {
        val payload = "hello-udp".toByteArray(Charsets.UTF_8)
        val packet = Ipv4Codec.wrapUdp(
            srcIp = "192.168.49.1",
            srcPort = 5555,
            dstIp = "26.0.0.2",
            dstPort = 4242,
            payload = payload,
        )

        val parsed = Ipv4Codec.parse(packet)!!
        assertEquals("192.168.49.1", parsed.srcIp)
        assertEquals("26.0.0.2", parsed.dstIp)
        assertEquals(Ipv4Codec.PROTO_UDP, parsed.protocol)
        assertEquals(Ipv4Codec.HEADER_LEN, parsed.payloadOffset)

        // UDP header inside the payload region.
        val srcPort = Ipv4Codec.read16(packet, parsed.payloadOffset)
        val dstPort = Ipv4Codec.read16(packet, parsed.payloadOffset + 2)
        assertEquals(5555, srcPort)
        assertEquals(4242, dstPort)

        val udpPayloadLength = parsed.payloadLength - Ipv4Codec.UDP_HEADER_LEN
        assertEquals(payload.size, udpPayloadLength)
        assertEquals(
            String(payload),
            String(packet, parsed.payloadOffset + Ipv4Codec.UDP_HEADER_LEN, udpPayloadLength),
        )
    }

    @Test
    fun `wraps an icmp packet with a valid header checksum`() {
        val icmp = byteArrayOf(0, 0, 0, 0, 0xBE.toByte(), 0xEF.toByte(), 0, 1, 1, 2, 3)
        val packet = Ipv4Codec.wrapIcmp(
            srcIp = "1.1.1.1",
            dstIp = "26.0.0.2",
            payload = icmp,
        )

        val parsed = Ipv4Codec.parse(packet)!!
        assertEquals("1.1.1.1", parsed.srcIp)
        assertEquals("26.0.0.2", parsed.dstIp)
        assertEquals(Ipv4Codec.PROTO_ICMP, parsed.protocol)
        assertEquals(Ipv4Codec.HEADER_LEN, parsed.payloadOffset)
        assertEquals(icmp.size, parsed.payloadLength)

        // Header checksum is valid: zero it and recompute.
        val stored = Ipv4Codec.read16(packet, 10)
        packet[10] = 0
        packet[11] = 0
        assertEquals(stored, Ipv4Codec.checksum(packet, 0, Ipv4Codec.HEADER_LEN))

        // ICMP payload rides verbatim after the IP header.
        assertEquals(
            icmp.toList(),
            packet.copyOfRange(Ipv4Codec.HEADER_LEN, packet.size).toList(),
        )
    }

    @Test
    fun `icmp checksum is verifiable and returns zero over the whole message`() {
        // Echo request with a computed checksum; the full-message sum must fold to 0.
        val icmp = byteArrayOf(8, 0, 0, 0, 0x12, 0x34, 0, 1, 9, 9, 9)
        val sum = Ipv4Codec.icmpChecksum(icmp)
        icmp[2] = (sum shr 8).toByte()
        icmp[3] = (sum and 0xFF).toByte()
        assertEquals(0, Ipv4Codec.icmpChecksum(icmp))
    }

    @Test
    fun `header checksum verifies`() {
        val packet = Ipv4Codec.wrapUdp("10.0.0.1", 1000, "10.0.0.2", 2000, byteArrayOf(1, 2, 3))
        val stored = Ipv4Codec.read16(packet, 10)
        // Zero the checksum field and recompute — must equal the stored value.
        packet[10] = 0
        packet[11] = 0
        assertEquals(stored, Ipv4Codec.checksum(packet, 0, Ipv4Codec.HEADER_LEN))
    }

    @Test
    fun `rejects non-ipv4 and truncated packets`() {
        // IPv6 version nibble = 6.
        val ipv6 = ByteArray(20) { 0 }
        ipv6[0] = 0x60
        assertNull(Ipv4Codec.parse(ipv6))

        assertNull(Ipv4Codec.parse(ByteArray(10)))
        assertNull(Ipv4Codec.parse(ByteArray(0)))
    }

    @Test
    fun `toBytes round trip`() {
        assertEquals("192.168.49.1", Ipv4Codec.inet4(Ipv4Codec.toBytes("192.168.49.1")!!, 0))
        assertNull(Ipv4Codec.toBytes("not-an-ip"))
        assertNull(Ipv4Codec.toBytes("1.2.3"))
        assertNull(Ipv4Codec.toBytes("1.2.3.999"))
    }

    @Test
    fun `parses a real udp header with ports`() {
        val packet = Ipv4Codec.wrapUdp("1.1.1.1", 53, "26.0.0.2", 54321, byteArrayOf(0, 1, 2, 3))
        val parsed = Ipv4Codec.parse(packet)!!
        assertEquals(4 + 8, parsed.payloadLength)
        val srcPort = Ipv4Codec.read16(packet, parsed.payloadOffset)
        val dstPort = Ipv4Codec.read16(packet, parsed.payloadOffset + 2)
        assertTrue(srcPort == 53 && dstPort == 54321)
    }
}
