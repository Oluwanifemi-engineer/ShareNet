package com.sharenet.app.tunnel

import com.sharenet.app.proxy.Ipv4Codec
import com.sharenet.app.proxy.TcpFlags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scripted wire-level tests for [TcpTunnelCore]: the test plays the role of
 * the app by crafting real IP/TCP packets (via [Ipv4Codec.wrapTcp]) and
 * asserts the stack's replies byte-for-byte.
 */
class TcpTunnelCoreTest {

    private val appIp = "26.0.0.2"
    private val appPort = 40000
    private val serverIp = "10.0.0.5"
    private val serverPort = 80

    private var fakeNow = 0L
    private val outgoing = ArrayList<ByteArray>()
    private val connects = ArrayList<Triple<Int, String, Int>>()
    private val payloads = ArrayList<Pair<Int, ByteArray>>()
    private val closes = ArrayList<Int>()
    private val resets = ArrayList<Int>()

    private fun newCore(): TcpTunnelCore = TcpTunnelCore(
        output = { outgoing.add(it) },
        onConnect = { id, dstIp, dstPort -> connects.add(Triple(id, dstIp, dstPort)) },
        onPayload = { id, payload -> payloads.add(id to payload) },
        onClose = { closes.add(it) },
        onReset = { resets.add(it) },
        now = { fakeNow },
    )

    private fun appSyn(seq: Long = 1000): ByteArray = Ipv4Codec.wrapTcp(
        srcIp = appIp, srcPort = appPort, dstIp = serverIp, dstPort = serverPort,
        seq = seq, ack = 0, flags = TcpFlags.SYN, window = 65535,
    )

    private fun appAck(seq: Long, ack: Long, payload: ByteArray = ByteArray(0)): ByteArray =
        Ipv4Codec.wrapTcp(
            srcIp = appIp, srcPort = appPort, dstIp = serverIp, dstPort = serverPort,
            seq = seq, ack = ack, flags = TcpFlags.ACK, window = 65535,
            payload = payload, payloadLength = payload.size,
        )

    private fun appFin(seq: Long, ack: Long): ByteArray = Ipv4Codec.wrapTcp(
        srcIp = appIp, srcPort = appPort, dstIp = serverIp, dstPort = serverPort,
        seq = seq, ack = ack, flags = TcpFlags.FIN or TcpFlags.ACK, window = 65535,
    )

    private fun appRst(seq: Long, ack: Long): ByteArray = Ipv4Codec.wrapTcp(
        srcIp = appIp, srcPort = appPort, dstIp = serverIp, dstPort = serverPort,
        seq = seq, ack = ack, flags = TcpFlags.RST or TcpFlags.ACK, window = 0,
    )

    private data class TcpSegment(
        val srcIp: String, val srcPort: Int, val dstIp: String, val dstPort: Int,
        val seq: Long, val ack: Long, val flags: Int, val payload: ByteArray,
    )

    private fun parseTcp(packet: ByteArray): TcpSegment {
        val ip = Ipv4Codec.parse(packet)!!
        val off = ip.payloadOffset
        val dataOffset = ((packet[off + 12].toInt() and 0xF0) shr 4) * 4
        val seq = read32(packet, off + 4)
        val ack = read32(packet, off + 8)
        val flags = packet[off + 13].toInt() and 0x3F
        val payload = packet.copyOfRange(off + dataOffset, off + ip.payloadLength)
        return TcpSegment(
            srcIp = ip.srcIp, srcPort = Ipv4Codec.read16(packet, off),
            dstIp = ip.dstIp, dstPort = Ipv4Codec.read16(packet, off + 2),
            seq = seq, ack = ack, flags = flags, payload = payload,
        )
    }

    private fun read32(packet: ByteArray, offset: Int): Long =
        ((packet[offset].toLong() and 0xFF) shl 24) or
            ((packet[offset + 1].toLong() and 0xFF) shl 16) or
            ((packet[offset + 2].toLong() and 0xFF) shl 8) or
            (packet[offset + 3].toLong() and 0xFF)

    /** Runs a full three-way handshake, returning the connId and our ISN. */
    private fun handshake(core: TcpTunnelCore, connect: Boolean = true): Pair<Int, Long> {
        outgoing.clear()
        core.onIpPacket(appSyn())
        assertEquals(1, connects.size)
        val connId = connects[0].first
        assertEquals(serverIp, connects[0].second)
        assertEquals(serverPort, connects[0].third)
        assertEquals(1, outgoing.size)
        val synAck = parseTcp(outgoing[0])
        assertEquals(serverIp, synAck.srcIp)
        assertEquals(serverPort, synAck.srcPort)
        assertEquals(appIp, synAck.dstIp)
        assertEquals(appPort, synAck.dstPort)
        assertTrue((synAck.flags and TcpFlags.SYN) != 0)
        assertTrue((synAck.flags and TcpFlags.ACK) != 0)
        assertEquals(1001, synAck.ack) // app's SYN seq + 1
        val isn = synAck.seq

        outgoing.clear()
        core.onIpPacket(appAck(seq = 1001, ack = isn + 1))
        // The host opens the real connection asynchronously; the tests
        // simulate its CONNECTED (which also flushes buffered app data).
        if (connect) core.onRemoteConnected(connId)
        return connId to isn
    }

    // ── Handshake ───────────────────────────────────────────────────────────

    @Test
    fun `completes three-way handshake`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        assertEquals(1, core.connsActive)
        assertEquals(1, core.handshakes)
        assertEquals(connId, connects[0].first)
    }

    @Test
    fun `resends SYN-ACK on duplicate SYN`() {
        val core = newCore()
        core.onIpPacket(appSyn())
        val first = outgoing[0]
        outgoing.clear()
        fakeNow += 500
        core.onIpPacket(appSyn())
        assertEquals(1, outgoing.size)
        val resend = parseTcp(outgoing[0])
        assertEquals(parseTcp(first).seq, resend.seq)
        assertEquals(parseTcp(first).ack, resend.ack)
        assertEquals(1, connects.size) // no duplicate connect
    }

    @Test
    fun `ignores stray segments for unknown connections`() {
        val core = newCore()
        core.onIpPacket(appAck(seq = 2000, ack = 5000))
        assertEquals(0, outgoing.size)
        assertEquals(0, core.connsActive)
    }

    // ── App -> tunnel data ──────────────────────────────────────────────────

    @Test
    fun `forwards app data and acks it`() {
        val core = newCore()
        handshake(core)
        outgoing.clear()
        payloads.clear()

        val body = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        core.onIpPacket(appAck(seq = 1001, ack = 0, payload = body))

        assertEquals(1, payloads.size)
        assertArrayEquals(body, payloads[0].second)
        assertEquals(1, outgoing.size)
        val ack = parseTcp(outgoing[0])
        assertTrue((ack.flags and TcpFlags.ACK) != 0)
        assertTrue((ack.flags and TcpFlags.SYN) == 0)
        assertEquals(1001L + body.size, ack.ack)
        assertEquals(core.appBytesForwarded, body.size.toLong())
    }

    @Test
    fun `re-acks out-of-order data without forwarding it`() {
        val core = newCore()
        handshake(core)
        outgoing.clear()
        payloads.clear()

        // Seq jumps ahead: gap -> re-ACK current expected only.
        core.onIpPacket(appAck(seq = 1100, ack = 0, payload = "hi".toByteArray()))
        assertEquals(0, payloads.size)
        assertEquals(1, outgoing.size)
        assertEquals(1001, parseTcp(outgoing[0]).ack)

        // Now the missing byte arrives.
        core.onIpPacket(appAck(seq = 1001, ack = 0, payload = "a".toByteArray()))
        assertEquals(1, payloads.size)
    }

    @Test
    fun `buffers app data until the host connects, then flushes it`() {
        val core = newCore()
        val (connId, _) = handshake(core, connect = false)
        outgoing.clear()
        payloads.clear()

        val body = "early-data".toByteArray()
        core.onIpPacket(appAck(seq = 1001, ack = 0, payload = body))
        // Nothing forwarded yet (host still connecting), but it IS acked.
        assertEquals(0, payloads.size)
        assertEquals(1, outgoing.size)
        assertEquals(1001L + body.size, parseTcp(outgoing[0]).ack)

        core.onRemoteConnected(connId)
        assertEquals(1, payloads.size)
        assertArrayEquals(body, payloads[0].second)
    }

    // ── Host -> app data ────────────────────────────────────────────────────

    @Test
    fun `delivers remote data with correct seq and honors app acks`() {
        val core = newCore()
        val (connId, isn) = handshake(core)
        outgoing.clear()

        val ourSeq = isn + 1
        core.onRemoteData(connId, "HTTP/1.1 200 OK\r\n".toByteArray())
        assertEquals(1, outgoing.size)
        val seg = parseTcp(outgoing[0])
        assertEquals(ourSeq, seg.seq)
        assertEquals("HTTP/1.1 200 OK\r\n".length.toLong(), core.remoteBytesDelivered)
        assertArrayEquals("HTTP/1.1 200 OK\r\n".toByteArray(), seg.payload)

        // App acks the whole segment: nothing left to retransmit.
        outgoing.clear()
        core.onIpPacket(appAck(seq = 1001, ack = ourSeq + seg.payload.size))
        assertEquals(0, outgoing.size)
    }

    @Test
    fun `retransmits unacked remote data after RTO`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        core.onRemoteData(connId, "retransmit-me".toByteArray())
        assertEquals(1, outgoing.size)
        val first = parseTcp(outgoing[0])

        outgoing.clear()
        fakeNow += 400 // >= RTO
        core.tick(fakeNow)
        assertEquals(1, outgoing.size)
        val resent = parseTcp(outgoing[0])
        assertEquals(first.seq, resent.seq)
        assertArrayEquals(first.payload, resent.payload)
        assertTrue(core.retransmits >= 1)

        // Now the app acks: retransmission stops.
        outgoing.clear()
        core.onIpPacket(appAck(seq = 1001, ack = first.seq + first.payload.size))
        fakeNow += 400
        core.tick(fakeNow)
        assertEquals(0, outgoing.size)
    }

    @Test
    fun `retransmissions back off exponentially`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        core.onRemoteData(connId, "stuck".toByteArray())
        assertEquals(1, outgoing.size)

        // t=400: first retransmission (RTO 400).
        fakeNow += 400
        core.tick(fakeNow)
        assertEquals(2, outgoing.size)

        // t=800: RTO has doubled to 800 — only 400ms passed, nothing yet.
        fakeNow += 400
        core.tick(fakeNow)
        assertEquals(2, outgoing.size)

        // t=1600: 1200ms >= 800ms RTO — second retransmission.
        fakeNow += 800
        core.tick(fakeNow)
        assertEquals(3, outgoing.size)

        // t=2400: RTO now 1600 — only 800ms passed, nothing yet.
        fakeNow += 800
        core.tick(fakeNow)
        assertEquals(3, outgoing.size)

        // t=4000: 2400ms >= 1600ms — third retransmission.
        fakeNow += 1600
        core.tick(fakeNow)
        assertEquals(4, outgoing.size)
        assertTrue(core.retransmits >= 3)
    }

    @Test
    fun `resets after too many retries`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()
        core.onRemoteData(connId, "stuck".toByteArray())
        var resetSeen = false
        // With backoff the retries spread out; tick long enough to exhaust them.
        for (i in 0..200) {
            fakeNow += 400
            core.tick(fakeNow)
            if (core.connsActive == 0L) {
                resetSeen = true
                break
            }
        }
        assertTrue("connection was never reset", resetSeen)
        val last = parseTcp(outgoing.last())
        assertTrue((last.flags and TcpFlags.RST) != 0)
        assertEquals(0, core.connsActive)
    }

    @Test
    fun `fast-retransmits the missing segment on three duplicate acks`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        val body = "abcdefghijklmnopqrstuvwxyz0123456789".toByteArray() // 36 bytes
        core.onRemoteData(connId, body)
        assertEquals(1, outgoing.size)
        val ourStart = parseTcp(outgoing[0]).seq

        // The app acks only the first 12 bytes; the rest stay unacked.
        outgoing.clear()
        core.onIpPacket(appAck(seq = 1001, ack = ourStart + 12))

        // Two duplicate ACKs are not enough — and no tick/RTO has fired.
        core.onIpPacket(appAck(seq = 1001, ack = ourStart + 12))
        core.onIpPacket(appAck(seq = 1001, ack = ourStart + 12))
        assertEquals(0, outgoing.size)

        // The third duplicate ACK triggers fast retransmit immediately,
        // resending only the single missing segment.
        core.onIpPacket(appAck(seq = 1001, ack = ourStart + 12))
        assertEquals(1, outgoing.size)
        val resent = parseTcp(outgoing[0])
        assertEquals(ourStart + 12, resent.seq)
        assertArrayEquals(body.copyOfRange(12, body.size), resent.payload)
        assertEquals(1, core.fastRetransmits)
        assertEquals(0, core.retransmits) // RTO path never fired

        // One shot per lost segment: further dup ACKs are ignored until a
        // real ACK arrives (the RTO timer restarted from the resend instead).
        outgoing.clear()
        core.onIpPacket(appAck(seq = 1001, ack = ourStart + 12))
        assertEquals(0, outgoing.size)

        // A real ACK advances the stream and drains the connection normally.
        core.onIpPacket(appAck(seq = 1001, ack = ourStart + body.size))
        fakeNow += 400
        core.tick(fakeNow)
        assertEquals(0, outgoing.size)
    }

    // ── Close ───────────────────────────────────────────────────────────────

    @Test
    fun `sends FIN to app when server closes and app acks it`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        core.onRemoteClose(connId)
        assertEquals(1, outgoing.size)
        val fin = parseTcp(outgoing[0])
        assertTrue((fin.flags and TcpFlags.FIN) != 0)

        // App acks the FIN: connection is removed.
        core.onIpPacket(appAck(seq = 1001, ack = fin.seq + 1))
        assertEquals(0, core.connsActive)
    }

    @Test
    fun `flushes pending data before FIN on server close`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        core.onRemoteData(connId, "final words".toByteArray())
        core.onRemoteClose(connId)
        assertEquals(1, outgoing.size) // data only — FIN waits
        val seg = parseTcp(outgoing[0])
        assertTrue((seg.flags and TcpFlags.FIN) == 0)

        // App acks the data; FIN goes out now.
        outgoing.clear()
        core.onIpPacket(appAck(seq = 1001, ack = seg.seq + seg.payload.size))
        assertEquals(1, outgoing.size)
        assertTrue((parseTcp(outgoing[0]).flags and TcpFlags.FIN) != 0)
    }

    @Test
    fun `propagates app FIN to host and acks it`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()
        closes.clear()

        core.onIpPacket(appFin(seq = 1001, ack = 0))
        assertEquals(listOf(connId), closes)
        assertEquals(1, outgoing.size)
        val ack = parseTcp(outgoing[0])
        assertTrue((ack.flags and TcpFlags.ACK) != 0)
        assertEquals(1002, ack.ack) // FIN consumes a sequence number
    }

    @Test
    fun `resets connection when app sends RST`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        resets.clear()

        core.onIpPacket(appRst(seq = 1001, ack = 0))
        assertEquals(listOf(connId), resets)
        assertEquals(0, core.connsActive)
    }

    @Test
    fun `resets app connection when host rejects`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        core.onRemoteRejected(connId)
        assertEquals(1, outgoing.size)
        val rst = parseTcp(outgoing[0])
        assertTrue((rst.flags and TcpFlags.RST) != 0)
        assertEquals(0, core.connsActive)
    }

    @Test
    fun `resets app connection on remote reset`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        core.onRemoteReset(connId)
        val rst = parseTcp(outgoing[0])
        assertTrue((rst.flags and TcpFlags.RST) != 0)
        assertEquals(0, core.connsActive)
    }

    // ── Segmentation ────────────────────────────────────────────────────────

    @Test
    fun `segments large remote data into MTU-sized pieces`() {
        val core = newCore()
        val (connId, _) = handshake(core)
        outgoing.clear()

        val big = ByteArray(5000) { (it % 251).toByte() }
        core.onRemoteData(connId, big)
        assertTrue(outgoing.size >= 4) // 5000 / 1360 -> 4 segments
        var total = 0L
        for (p in outgoing) {
            val seg = parseTcp(p)
            assertTrue(seg.payload.size <= 1360)
            total += seg.payload.size
        }
        assertEquals(5000L, total)
        assertEquals(big.toList(), outgoing.flatMap { parseTcp(it).payload.toList() })
    }

    @Test
    fun `closeAll resets every connection`() {
        val core = newCore()
        handshake(core)
        resets.clear()
        core.closeAll()
        assertEquals(1, resets.size)
        assertEquals(0, core.connsActive)
    }
}
