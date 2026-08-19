package com.sharenet.app.proxy

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A DNS server that resolves ALL queries to [answerIp].
 *
 * When a device joins the ShareNet network, its OS probes known URLs
 * (connectivitycheck.gstatic.com, captive.apple.com, etc.) to detect
 * captive portals. By resolving every query to the phone's P2P IP,
 * these probes land on our HTTP proxy, which redirects to the setup page.
 * The OS then shows the captive portal popup automatically — no manual
 * URL entry needed.
 *
 * Pure JVM — no Android imports.
 */
class CaptivePortalDnsServer(
    private val bindHost: String,
    private val port: Int,
    private val answerIp: InetAddress,
    private val log: (String) -> Unit = {},
) {

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null

    val queriesAnswered = AtomicLong(0)

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start() {
        if (running.getAndSet(true)) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.soTimeout = 1000
            s.bind(InetSocketAddress(bindHost, port))
            socket = s
            log("captive dns: answering ALL queries -> ${answerIp.hostAddress} on $bindHost:$port")
            Thread({ receiveLoop(s) }, "sharenet-captive-dns").apply { isDaemon = true }.start()
        } catch (e: SocketException) {
            running.set(false)
            throw ProxyBindException("captive dns bind failed on $bindHost:$port", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { socket?.close() }
        socket = null
        log("captive dns stopped")
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(512)
        while (running.get()) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (_: SocketException) { break
            } catch (_: Exception) { if (!running.get()) break; continue }

            val query = pkt.data.copyOf(pkt.length)
            val client = pkt.socketAddress as InetSocketAddress
            val reply = buildReply(query, answerIp)
            if (reply != null) {
                try {
                    s.send(DatagramPacket(reply, reply.size, client))
                    queriesAnswered.incrementAndGet()
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        /**
         * Build a DNS A-record reply pointing [queryName] to [ip].
         * Returns null if the query is malformed.
         */
        fun buildReply(query: ByteArray, ip: InetAddress): ByteArray? {
            if (query.size < 12) return null

            val txId = byteArrayOf(query[0], query[1])
            val flags = byteArrayOf(0x81.toByte(), 0x80.toByte()) // standard response, no error
            val counts = byteArrayOf(
                0x00, 0x01, // QDCOUNT = 1
                0x00, 0x01, // ANCOUNT = 1
                0x00, 0x00, // NSCOUNT = 0
                0x00, 0x00, // ARCOUNT = 0
            )

            // Parse the question section to copy it back
            var pos = 12
            val qname = mutableListOf<Byte>()
            while (pos < query.size) {
                val labelLen = query[pos].toInt() and 0xFF
                if (labelLen == 0) {
                    qname.add(0x00)
                    pos++
                    break
                }
                // Add the length byte + label bytes
                for (i in 0..labelLen) {
                    if (pos + i < query.size) qname.add(query[pos + i])
                }
                pos += labelLen + 1
            }
            // QTYPE (2 bytes) + QCLASS (2 bytes)
            if (pos + 4 > query.size) return null
            val qtype = byteArrayOf(query[pos], query[pos + 1])
            val qclass = byteArrayOf(query[pos + 2], query[pos + 3])

            // Build the answer: same name (compressed pointer to offset 12), type A, class IN, TTL 60, 4-byte IP
            val answerName = byteArrayOf(0xC0.toByte(), 0x0C) // pointer to offset 12
            val answerType = qtype  // A record
            val answerClass = qclass  // IN
            val ttl = byteArrayOf(0x00, 0x00, 0x00, 0x3C) // 60 seconds
            val rdlength = byteArrayOf(0x00, 0x04) // 4 bytes
            val rdata = ip.address

            val totalLen = 12 + qname.size + 4 + answerName.size + 4 + 4 + 2 + rdata.size
            val reply = ByteArray(totalLen)
            var w = 0

            // Header
            System.arraycopy(txId, 0, reply, w, 2); w += 2
            System.arraycopy(flags, 0, reply, w, 2); w += 2
            System.arraycopy(counts, 0, reply, w, 8); w += 8

            // Question
            for (b in qname) reply[w++] = b
            System.arraycopy(answerType, 0, reply, w, 2); w += 2
            System.arraycopy(answerClass, 0, reply, w, 2); w += 2

            // Answer
            System.arraycopy(answerName, 0, reply, w, 2); w += 2
            System.arraycopy(answerType, 0, reply, w, 2); w += 2
            System.arraycopy(answerClass, 0, reply, w, 2); w += 2
            System.arraycopy(ttl, 0, reply, w, 4); w += 4
            System.arraycopy(rdlength, 0, reply, w, 2); w += 2
            System.arraycopy(rdata, 0, reply, w, 4); w += 4

            return reply
        }
    }
}
