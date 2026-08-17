package com.sharenet.app.proxy

/**
 * Minimal IPv4 packet codec for the UDP tunnel. Pure JVM, unit-tested.
 *
 * The tunnel carries the client's raw IP packets over a UDP socket; the relay
 * needs to read the 4-tuple (to track flows) and build reply packets (to
 * return them into the client's tun interface). Nothing here touches Android.
 */
data class Ipv4Packet(
    val srcIp: String,
    val dstIp: String,
    val protocol: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

object Ipv4Codec {

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val HEADER_LEN = 20
    const val UDP_HEADER_LEN = 8

    /** Parses an IPv4 packet; returns null when it is not a valid IPv4 packet. */
    fun parse(packet: ByteArray, length: Int = packet.size): Ipv4Packet? {
        if (length < HEADER_LEN) return null
        val versionIhl = packet[0].toInt() and 0xFF
        if ((versionIhl shr 4) != 4) return null // IPv4 only
        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < HEADER_LEN || length < ihl) return null
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (totalLength < ihl) return null
        val len = minOf(totalLength, length)
        return Ipv4Packet(
            srcIp = inet4(packet, 12),
            dstIp = inet4(packet, 16),
            protocol = packet[9].toInt() and 0xFF,
            payloadOffset = ihl,
            payloadLength = len - ihl,
        )
    }

    fun inet4(packet: ByteArray, offset: Int): String {
        val b0 = packet[offset].toInt() and 0xFF
        val b1 = packet[offset + 1].toInt() and 0xFF
        val b2 = packet[offset + 2].toInt() and 0xFF
        val b3 = packet[offset + 3].toInt() and 0xFF
        return "$b0.$b1.$b2.$b3"
    }

    fun toBytes(ip: String): ByteArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255) return null
            out[i] = v.toByte()
        }
        return out
    }

    fun read16(packet: ByteArray, offset: Int): Int =
        ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)

    /**
     * Builds a UDP IPv4 packet with a computed header checksum. Used by the
     * relay to return payloads to the client's tun interface.
     */
    fun wrapUdp(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ): ByteArray {
        val src = toBytes(srcIp) ?: throw IllegalArgumentException("bad src ip: $srcIp")
        val dst = toBytes(dstIp) ?: throw IllegalArgumentException("bad dst ip: $dstIp")
        val total = HEADER_LEN + UDP_HEADER_LEN + payloadLength
        val pkt = ByteArray(total)
        pkt[0] = 0x45.toByte() // v4, ihl=5
        pkt[2] = (total shr 8).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[8] = 64 // ttl
        pkt[9] = PROTO_UDP.toByte()
        System.arraycopy(src, 0, pkt, 12, 4)
        System.arraycopy(dst, 0, pkt, 16, 4)
        // UDP header (checksum 0 is legal for IPv4).
        pkt[20] = (srcPort shr 8).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort shr 8).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        val udpLen = UDP_HEADER_LEN + payloadLength
        pkt[24] = (udpLen shr 8).toByte()
        pkt[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(payload, payloadOffset, pkt, HEADER_LEN + UDP_HEADER_LEN, payloadLength)
        val checksum = checksum(pkt, 0, HEADER_LEN)
        pkt[10] = (checksum shr 8).toByte()
        pkt[11] = (checksum and 0xFF).toByte()
        return pkt
    }

    /** Internet checksum over [length] bytes starting at [offset]. */
    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    /**
     * Builds a TCP IPv4 packet with correct header and pseudo-header
     * checksums. Used by the tunnel core to reply to the client's tun
     * interface.
     *
     * @param flags OR of TCP flag bits (use [TcpFlags]).
     */
    fun wrapTcp(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray = EMPTY,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ): ByteArray {
        val src = toBytes(srcIp) ?: throw IllegalArgumentException("bad src ip: $srcIp")
        val dst = toBytes(dstIp) ?: throw IllegalArgumentException("bad dst ip: $dstIp")
        val tcpLen = TCP_HEADER_LEN + payloadLength
        val total = HEADER_LEN + tcpLen
        val pkt = ByteArray(total)
        pkt[0] = 0x45.toByte() // v4, ihl=5
        pkt[2] = (total shr 8).toByte()
        pkt[3] = (total and 0xFF).toByte()
        pkt[8] = 64 // ttl
        pkt[9] = PROTO_TCP.toByte()
        System.arraycopy(src, 0, pkt, 12, 4)
        System.arraycopy(dst, 0, pkt, 16, 4)
        pkt[20] = (srcPort shr 8).toByte()
        pkt[21] = (srcPort and 0xFF).toByte()
        pkt[22] = (dstPort shr 8).toByte()
        pkt[23] = (dstPort and 0xFF).toByte()
        put32(pkt, 24, seq)
        put32(pkt, 28, ack)
        pkt[32] = 0x50.toByte() // data offset 5 (no options)
        pkt[33] = flags.toByte()
        put16(pkt, 34, window)
        // checksum fields left zero until computed
        System.arraycopy(payload, payloadOffset, pkt, HEADER_LEN + TCP_HEADER_LEN, payloadLength)

        // TCP checksum over pseudo-header + TCP segment.
        val sum = checksum(pkt, 12, 8) + PROTO_TCP + tcpLen // pseudo: src+dst
        val tcpSum = checksumWithCarry(sum, pkt, HEADER_LEN, tcpLen)
        pkt[36] = (tcpSum shr 8).toByte()
        pkt[37] = (tcpSum and 0xFF).toByte()

        val ipSum = checksum(pkt, 0, HEADER_LEN)
        pkt[10] = (ipSum shr 8).toByte()
        pkt[11] = (ipSum and 0xFF).toByte()
        return pkt
    }

    private fun checksumWithCarry(initial: Int, data: ByteArray, offset: Int, length: Int): Int {
        var sum = initial.toLong() and 0xFFFFFFFFL
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun put16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value shr 8).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun put32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value shr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    const val TCP_HEADER_LEN = 20
    private val EMPTY = ByteArray(0)
}

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}
