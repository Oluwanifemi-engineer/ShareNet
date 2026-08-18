package com.sharenet.app.tunnel

import com.sharenet.app.proxy.Ipv4Codec
import com.sharenet.app.proxy.TcpFlags

/**
 * Client-side user-space TCP stack — the "sequence translation" half of the
 * Tier-2 TCP tunnel.
 *
 * The phone's VpnService captures the app's raw IP packets and hands TCP
 * segments here. This core plays the role of the remote server toward the
 * app: it owns the TCP state machine (SYN/SYN-ACK handshake, seq/ack book-
 * keeping, retransmission, FIN and RST), while the payload bytes are bridged
 * to the host over the tunnel ([TunnelProtocol]). The host opens a REAL
 * socket to the destination, so on the wire this is exactly what tun2socks
 * does.
 *
 * Design notes:
 *  - Handshake is optimistic: SYN is answered with SYN-ACK immediately (the
 *    app believes it is connected). If the host later reports the real
 *    connection failed, an RST is sent back — the app sees a reset, which is
 *    what it would get from a refused proxy.
 *  - In-order data only; out-of-order segments are re-ACKed so the app
 *    retransmits (correct, if not maximally efficient — fine for a local
 *    link).
 *  - Retransmission: unacknowledged data is resent after the RTO via [tick]
 *    (exponential backoff), or immediately after three duplicate ACKs (fast
 *    retransmit) — the difference between a hiccup and a stall on a lossy
 *    P2P link. After too many retries the connection is reset.
 *  - Pure state machine: no threads, no sockets, no wall clock. The owner
 *    feeds packets and calls [tick]; replies come out through [output].
 */
class TcpTunnelCore(
    private val output: (ByteArray) -> Unit,
    private val onConnect: (connId: Int, dstIp: String, dstPort: Int) -> Unit,
    private val onPayload: (connId: Int, payload: ByteArray) -> Unit,
    private val onClose: (connId: Int) -> Unit,
    private val onReset: (connId: Int) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val conns = HashMap<Int, Conn>()
    private val byKey = HashMap<String, Int>()
    private var nextId = 1

    // Stats (readable for the UI / logs).
    @Volatile var handshakes = 0L; private set
    @Volatile var appBytesForwarded = 0L; private set
    @Volatile var remoteBytesDelivered = 0L; private set
    @Volatile var retransmits = 0L; private set
    @Volatile var fastRetransmits = 0L; private set
    @Volatile var resetsSent = 0L; private set
    @Volatile var connsActive = 0L; private set
    @Volatile var malformedDropped = 0L; private set

    private enum class State { SYN_RECEIVED, ESTABLISHED, FIN_PENDING, FIN_SENT, CLOSED }

    private class Conn(
        val id: Int,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        var state: State,
        var ourIsn: Long,
        var ourSeq: Long,          // next seq we will send
        var appSeq: Long,          // next app seq we expect
        var sendStartSeq: Long,    // seq of sendBuffer[0]
        val sendBuffer: ByteArray = ByteArray(SEND_BUFFER_MAX),
        var sendLen: Int = 0,
        var ackedSeq: Long,        // highest app ack seen for our data
        var ourFinSeq: Long = -1,  // set when we send FIN
        var lastSendMs: Long = 0,
        var retries: Int = 0,
        var dupAcks: Int = 0,      // consecutive duplicate ACKs from the app
        var fastRetransmitted: Boolean = false, // one fast retransmit per lost segment
        var connected: Boolean = false, // host's real socket established
        var pendingPayload: MutableList<ByteArray>? = null,
        var pendingClose: Boolean = false, // app FIN arrived before CONNECTED
    )

    /** Feeds one captured IP packet (from the tun device). */
    fun onIpPacket(packet: ByteArray, length: Int = packet.size) {
        val ip = Ipv4Codec.parse(packet, length)
        if (ip == null || ip.protocol != Ipv4Codec.PROTO_TCP) {
            if (ip == null) malformedDropped++
            return
        }
        if (ip.payloadLength < Ipv4Codec.TCP_HEADER_LEN) {
            malformedDropped++
            return
        }
        val off = ip.payloadOffset
        val srcPort = Ipv4Codec.read16(packet, off)
        val dstPort = Ipv4Codec.read16(packet, off + 2)
        val seq = read32(packet, off + 4)
        val ack = read32(packet, off + 8)
        val dataOffset = ((packet[off + 12].toInt() and 0xF0) shr 4) * 4
        val flags = packet[off + 13].toInt() and 0x3F
        if (dataOffset < Ipv4Codec.TCP_HEADER_LEN || dataOffset > ip.payloadLength) {
            malformedDropped++
            return
        }
        val payloadOffset = off + dataOffset
        val payloadLength = ip.payloadLength - dataOffset

        val key = keyOf(ip.srcIp, srcPort, ip.dstIp, dstPort)
        val existing = byKey[key]
        val conn = if (existing != null) conns[existing] else null

        when {
            conn == null -> {
                if (flags and TcpFlags.SYN != 0) {
                    acceptNew(ip, srcPort, dstPort, seq, key)
                } // else: stray segment for a connection we never saw — ignore
            }
            flags and TcpFlags.RST != 0 -> handleAppReset(conn)
            flags and TcpFlags.SYN != 0 && conn.state == State.SYN_RECEIVED -> {
                // Duplicate SYN (our SYN-ACK was lost): resend it.
                sendSynAck(conn)
                conn.retries = 0
            }
            flags and TcpFlags.FIN != 0 ->
                handleAppFin(conn, seq, packet, payloadOffset, payloadLength)
            else -> handleAppSegment(conn, seq, ack, flags, packet, payloadOffset, payloadLength)
        }
    }

    // ── App -> core ─────────────────────────────────────────────────────────

    private fun acceptNew(ip: com.sharenet.app.proxy.Ipv4Packet, srcPort: Int, dstPort: Int, synSeq: Long, key: String) {
        if (conns.size >= MAX_CONNECTIONS) return
        val id = nextId++
        val conn = Conn(
            id = id,
            srcIp = ip.srcIp,
            srcPort = srcPort,
            dstIp = ip.dstIp,
            dstPort = dstPort,
            state = State.SYN_RECEIVED,
            ourIsn = randomIsn(),
            ourSeq = 0,
            appSeq = synSeq + 1,
            sendStartSeq = 0,
            ackedSeq = 0,
            lastSendMs = now(),
        )
        conn.ourSeq = conn.ourIsn + 1
        conns[id] = conn
        byKey[key] = id
        connsActive++
        handshakes++
        sendSynAck(conn)
        onConnect(id, conn.dstIp, conn.dstPort)
    }

    private fun handleAppSegment(
        conn: Conn,
        seq: Long,
        ack: Long,
        flags: Int,
        packet: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ) {
        // Our outgoing data acknowledged?
        if (flags and TcpFlags.ACK != 0) {
            applyAppAck(conn, ack)
        }
        if (conn.state == State.CLOSED) return

        // Data from the app, in order?
        if (payloadLength > 0) {
            if (seq == conn.appSeq) {
                if (conn.state == State.SYN_RECEIVED) conn.state = State.ESTABLISHED
                val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                forwardAppPayload(conn, payload)
                conn.appSeq += payloadLength
                appBytesForwarded += payloadLength
                sendAck(conn)
            } else {
                // Out of order (gap) or retransmission of acked data: send a
                // duplicate ACK for the expected sequence — the app's sender
                // retransmits the missing bytes either way.
                sendAck(conn)
            }
        }
    }

    /**
     * App bytes to the host. If the real connection is not established yet
     * (the host opens it asynchronously), buffer them — the host would drop
     * frames for an unknown connId.
     */
    private fun forwardAppPayload(conn: Conn, payload: ByteArray) {
        if (conn.connected) {
            onPayload(conn.id, payload)
        } else {
            val queue = conn.pendingPayload ?: ArrayList<ByteArray>().also {
                conn.pendingPayload = it
            }
            if (queue.size >= MAX_PENDING_SEGMENTS) {
                // The host is too slow to connect; reset rather than buffer
                // unboundedly.
                sendReset(conn)
                removeConn(conn)
                return
            }
            queue.add(payload)
        }
    }

    private fun handleAppFin(conn: Conn, finSeq: Long, packet: ByteArray, payloadOffset: Int, payloadLength: Int) {
        if (conn.state != State.SYN_RECEIVED && conn.state != State.ESTABLISHED &&
            conn.state != State.FIN_PENDING
        ) {
            return
        }
        // Consume any data riding on the FIN, then the FIN itself — but only
        // when it is in order. An out-of-order FIN is ignored for data but
        // still closes the app side.
        if (payloadLength > 0 && finSeq == conn.appSeq) {
            val payload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength)
            forwardAppPayload(conn, payload)
            conn.appSeq += payloadLength
            appBytesForwarded += payloadLength
        }
        if (finSeq + payloadLength == conn.appSeq) {
            conn.appSeq++ // the FIN consumes one sequence number
        }
        sendAck(conn)
        if (conn.connected) {
            onClose(conn.id) // host half-closes the real socket
        } else {
            conn.pendingClose = true
        }
        maybeSendFin(conn)
    }

    // ── Host -> core ────────────────────────────────────────────────────────

    /** The host established the real connection: flush any buffered data. */
    fun onRemoteConnected(connId: Int) {
        val conn = conns[connId] ?: return
        conn.connected = true
        conn.pendingPayload?.forEach { onPayload(conn.id, it) }
        conn.pendingPayload = null
        if (conn.pendingClose) {
            conn.pendingClose = false
            onClose(conn.id)
        }
        maybeSendFin(conn)
    }

    /** The host could not reach the destination: reset the app connection. */
    fun onRemoteRejected(connId: Int) {
        val conn = conns[connId] ?: return
        sendReset(conn)
        removeConn(conn)
    }

    /** Payload from the real server, to be delivered to the app. */
    fun onRemoteData(connId: Int, data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val conn = conns[connId] ?: return
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val chunk = minOf(MAX_SEGMENT, end - pos)
            appendSend(conn, data, pos, chunk)
            sendSegment(conn, chunk, flags = TcpFlags.ACK or TcpFlags.PSH)
            pos += chunk
        }
        remoteBytesDelivered += length
        maybeSendFin(conn)
    }

    /** The server reached EOF: FIN to the app once our data is flushed. */
    fun onRemoteClose(connId: Int) {
        val conn = conns[connId] ?: return
        conn.state = State.FIN_PENDING
        maybeSendFin(conn)
    }

    /** The real connection was aborted: RST the app. */
    fun onRemoteReset(connId: Int) {
        val conn = conns[connId] ?: return
        sendReset(conn)
        removeConn(conn)
    }

    // ── Retransmission timer ────────────────────────────────────────────────

    /** Called periodically by the owner; resends unacknowledged data. */
    fun tick(nowMs: Long) {
        for (conn in conns.values.toList()) {
            if (conn.state == State.CLOSED) continue
            val unacked = conn.state == State.SYN_RECEIVED ||
                conn.sendLen > 0 ||
                (conn.state == State.FIN_SENT && conn.ourFinSeq >= 0 &&
                    conn.ackedSeq < conn.ourFinSeq + 1)
            if (!unacked) continue
            // Exponential backoff: each retransmission doubles the wait (up to
            // a cap), so a genuinely dead link does not hammer the radio and
            // a briefly congested one is given room to recover.
            val rto = minOf(RTO_MS shl minOf(conn.retries, MAX_RTO_SHIFT), MAX_RTO_MS)
            if (nowMs - conn.lastSendMs < rto) continue
            conn.retries++
            if (conn.retries > MAX_RETRIES) {
                sendReset(conn)
                removeConn(conn)
                continue
            }
            retransmits++
            when {
                conn.state == State.SYN_RECEIVED -> sendSynAck(conn)
                else -> {
                    resendUnacked(conn)
                    if (conn.state == State.FIN_SENT) sendFinSegment(conn)
                }
            }
            conn.lastSendMs = nowMs
        }
    }

    /** Drops everything (tunnel going down). */
    fun closeAll() {
        for (conn in conns.values.toList()) {
            runCatching { onReset(conn.id) }
        }
        conns.clear()
        byKey.clear()
        connsActive = 0
    }

    // ── Sending to the app ──────────────────────────────────────────────────

    private fun sendSynAck(conn: Conn) {
        output(
            Ipv4Codec.wrapTcp(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = conn.ourIsn, ack = conn.appSeq,
                flags = TcpFlags.SYN or TcpFlags.ACK,
                window = WINDOW,
            ),
        )
        conn.lastSendMs = now()
    }

    private fun sendAck(conn: Conn) {
        output(
            Ipv4Codec.wrapTcp(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = conn.ourSeq, ack = conn.appSeq,
                flags = TcpFlags.ACK,
                window = WINDOW,
            ),
        )
    }

    private fun appendSend(conn: Conn, data: ByteArray, offset: Int, length: Int) {
        if (conn.sendLen + length > SEND_BUFFER_MAX) {
            // Buffer full: the app is not ACKing; reset rather than corrupt.
            sendReset(conn)
            removeConn(conn)
            return
        }
        System.arraycopy(data, offset, conn.sendBuffer, conn.sendLen, length)
        if (conn.sendLen == 0) conn.sendStartSeq = conn.ourSeq
        conn.sendLen += length
        conn.ourSeq += length
    }

    private fun sendSegment(conn: Conn, length: Int, flags: Int) {
        val segSeq = conn.sendStartSeq + conn.sendLen - length
        output(
            Ipv4Codec.wrapTcp(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = segSeq, ack = conn.appSeq,
                flags = flags,
                window = WINDOW,
                payload = conn.sendBuffer,
                payloadOffset = (segSeq - conn.sendStartSeq).toInt(),
                payloadLength = length,
            ),
        )
        conn.lastSendMs = now()
    }

    /**
     * Fast retransmit: resend the single oldest unacked segment (RFC 5681).
     * Duplicate ACKs mean the app has everything before [Conn.sendStartSeq],
     * so that is exactly the byte it is missing. Also restarts the RTO timer.
     */
    private fun fastRetransmit(conn: Conn) {
        if (conn.sendLen == 0) return
        val chunk = minOf(MAX_SEGMENT, conn.sendLen)
        output(
            Ipv4Codec.wrapTcp(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = conn.sendStartSeq, ack = conn.appSeq,
                flags = TcpFlags.ACK or TcpFlags.PSH,
                window = WINDOW,
                payload = conn.sendBuffer, payloadOffset = 0, payloadLength = chunk,
            ),
        )
        conn.lastSendMs = now()
    }

    private fun resendUnacked(conn: Conn) {
        if (conn.sendLen == 0) return
        var pos = 0
        while (pos < conn.sendLen) {
            val chunk = minOf(MAX_SEGMENT, conn.sendLen - pos)
            output(
                Ipv4Codec.wrapTcp(
                    srcIp = conn.dstIp, srcPort = conn.dstPort,
                    dstIp = conn.srcIp, dstPort = conn.srcPort,
                    seq = conn.sendStartSeq + pos, ack = conn.appSeq,
                    flags = TcpFlags.ACK or TcpFlags.PSH,
                    window = WINDOW,
                    payload = conn.sendBuffer, payloadOffset = pos, payloadLength = chunk,
                ),
            )
            pos += chunk
        }
        conn.lastSendMs = now()
    }

    private fun sendFinSegment(conn: Conn) {
        if (conn.ourFinSeq >= 0) return
        conn.ourFinSeq = conn.ourSeq
        conn.ourSeq++
        conn.state = State.FIN_SENT
        output(
            Ipv4Codec.wrapTcp(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = conn.ourFinSeq, ack = conn.appSeq,
                flags = TcpFlags.FIN or TcpFlags.ACK,
                window = WINDOW,
            ),
        )
        conn.lastSendMs = now()
    }

    private fun sendReset(conn: Conn) {
        resetsSent++
        output(
            Ipv4Codec.wrapTcp(
                srcIp = conn.dstIp, srcPort = conn.dstPort,
                dstIp = conn.srcIp, dstPort = conn.srcPort,
                seq = conn.ourSeq, ack = conn.appSeq,
                flags = TcpFlags.RST or TcpFlags.ACK,
                window = 0,
            ),
        )
    }

    private fun maybeSendFin(conn: Conn) {
        if (conn.state != State.FIN_PENDING) return
        if (conn.sendLen > 0) return // wait for the app to drain our data
        sendFinSegment(conn)
    }

    private fun applyAppAck(conn: Conn, ack: Long) {
        if (conn.state == State.SYN_RECEIVED) {
            if (ack == conn.ourIsn + 1) {
                conn.state = State.ESTABLISHED
            }
            return
        }
        if (ack > conn.ackedSeq) {
            conn.ackedSeq = ack
            conn.dupAcks = 0
            conn.fastRetransmitted = false
        } else if (ack == conn.ackedSeq && conn.ackedSeq > 0 && conn.sendLen > 0) {
            // Duplicate ACK: the app received everything up to ackedSeq, so
            // the first unacked byte is missing. Three of these in a row mean
            // "fast retransmit" — resend that one segment now instead of
            // waiting out the RTO. One shot per lost segment: after the
            // retransmit, dup ACKs accumulate again before the next one.
            if (!conn.fastRetransmitted) {
                conn.dupAcks++
                if (conn.dupAcks >= FAST_RETRANSMIT_THRESHOLD) {
                    conn.dupAcks = 0
                    conn.fastRetransmitted = true
                    fastRetransmits++
                    fastRetransmit(conn)
                }
            }
        }
        if (conn.sendLen > 0 && ack > conn.sendStartSeq) {
            val consumed = (ack - conn.sendStartSeq).toInt()
            if (consumed >= conn.sendLen) {
                conn.sendLen = 0
            } else {
                System.arraycopy(
                    conn.sendBuffer, consumed,
                    conn.sendBuffer, 0,
                    conn.sendLen - consumed,
                )
                conn.sendLen -= consumed
                conn.sendStartSeq = ack
            }
            conn.retries = 0
        }
        when {
            conn.state == State.FIN_SENT && conn.ourFinSeq >= 0 &&
                conn.ackedSeq >= conn.ourFinSeq + 1 -> removeConn(conn)
            conn.state == State.FIN_PENDING -> maybeSendFin(conn)
        }
    }

    private fun removeConn(conn: Conn) {
        conn.state = State.CLOSED
        conns.remove(conn.id)
        byKey.remove(keyOf(conn.srcIp, conn.srcPort, conn.dstIp, conn.dstPort))
        connsActive--
    }

    private fun handleAppReset(conn: Conn) {
        // Always tell the host to abort; if its socket is still connecting it
        // will close it when CONNECTED would have been sent (the host tracks
        // aborted connIds). Ordering is safe: frames travel in order on the
        // control connection, so the host sees CONNECT before RST.
        onReset(conn.id)
        removeConn(conn)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun keyOf(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int): String =
        "$srcIp:$srcPort->$dstIp:$dstPort"

    private fun read32(packet: ByteArray, offset: Int): Long =
        ((packet[offset].toLong() and 0xFF) shl 24) or
            ((packet[offset + 1].toLong() and 0xFF) shl 16) or
            ((packet[offset + 2].toLong() and 0xFF) shl 8) or
            (packet[offset + 3].toLong() and 0xFF)

    private fun randomIsn(): Long =
        (Math.random() * 0xFFFFFFFFL).toLong() and 0xFFFFFFFFL

    companion object {
        private const val WINDOW = 65535
        private const val MAX_SEGMENT = 1360 // MTU 1400 - 20 ip - 20 tcp
        private const val SEND_BUFFER_MAX = 256 * 1024
        private const val MAX_CONNECTIONS = 64
        private const val MAX_PENDING_SEGMENTS = 64
        private const val FAST_RETRANSMIT_THRESHOLD = 3 // dup ACKs -> resend
        private const val RTO_MS = 400L
        private const val MAX_RTO_MS = 10_000L
        private const val MAX_RTO_SHIFT = 5 // 400ms * 2^5 = 12.8s, capped above
        private const val MAX_RETRIES = 6
    }
}
