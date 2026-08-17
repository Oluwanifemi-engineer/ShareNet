package com.sharenet.app.tunnel

import com.sharenet.app.proxy.DestinationPolicy
import com.sharenet.app.proxy.Ipv4Codec
import com.sharenet.app.proxy.TcpFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Protocol-level tests for the pairing PIN (AUTH) handshake, speaking the raw
 * [TunnelProtocol] wire format directly — no [TcpTunnelClient] involved — so
 * the byte-level contract is pinned down:
 *
 *  - the host answers PINGs even before authentication (liveness), but
 *  - refuses every other frame until it sees a valid AUTH, and
 *  - replies with TYPE_AUTH_REJECTED (then closes) on a wrong PIN.
 *
 * Plus one client-level test: a client that has no PIN cannot open any
 * connection against a PIN-protected host.
 */
class TcpTunnelAuthTest {

    private val appIp = "26.0.0.2"
    private val appPort = 41000
    private val serverIp = "127.0.0.1"

    private fun frame(connId: Int, type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(5 + payload.size)
        out[0] = ((connId shr 8) and 0xFF).toByte()
        out[1] = (connId and 0xFF).toByte()
        out[2] = type.toByte()
        out[3] = ((payload.size shr 8) and 0xFF).toByte()
        out[4] = (payload.size and 0xFF).toByte()
        payload.copyInto(out, 5)
        return out
    }

    /** Reads a full frame header + payload, or null on EOF/timeout. */
    private fun readFrame(input: InputStream): Triple<Int, Int, ByteArray>? {
        val header = ByteArray(TunnelProtocol.HEADER_LEN)
        var off = 0
        while (off < header.size) {
            val n = try {
                input.read(header, off, header.size - off)
            } catch (e: Exception) {
                return null
            }
            if (n < 0) return null
            off += n
        }
        val connId = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        val type = header[2].toInt() and 0xFF
        val len = ((header[3].toInt() and 0xFF) shl 8) or (header[4].toInt() and 0xFF)
        if (len > TunnelProtocol.MAX_PAYLOAD) return null
        val payload = ByteArray(len)
        off = 0
        while (off < len) {
            val n = input.read(payload, off, len - off)
            if (n < 0) return null
            off += n
        }
        return Triple(connId, type, payload)
    }

    @Test
    fun `host answers heartbeats before authentication`() {
        val host = TcpTunnelServer("127.0.0.1", 0, authPin = "1234")
        host.start()
        try {
            Socket("127.0.0.1", host.boundPort).use { s ->
                s.soTimeout = 5_000
                s.getOutputStream().write(frame(0, TunnelProtocol.TYPE_PING))
                s.getOutputStream().flush()
                val reply = readFrame(s.getInputStream())
                assertTrue("expected a PONG, got $reply", reply != null)
                assertEquals(TunnelProtocol.TYPE_PONG, reply!!.second)
            }
        } finally {
            host.stop()
        }
    }

    @Test
    fun `wrong pin produces the AUTH_REJECTED frame then closes`() {
        val host = TcpTunnelServer("127.0.0.1", 0, authPin = "1234")
        host.start()
        try {
            Socket("127.0.0.1", host.boundPort).use { s ->
                s.soTimeout = 5_000
                s.getOutputStream().write(frame(0, TunnelProtocol.TYPE_AUTH, "9999".toByteArray()))
                s.getOutputStream().flush()
                val reply = readFrame(s.getInputStream())
                assertTrue("expected AUTH_REJECTED, got $reply", reply != null)
                val (connId, type, payload) = reply!!
                assertEquals(0, connId)
                assertEquals(TunnelProtocol.TYPE_AUTH_REJECTED, type)
                assertEquals(0, payload.size)
                // The host must close the control connection right after.
                assertEquals(-1, s.getInputStream().read())
            }
        } finally {
            host.stop()
        }
    }

    @Test
    fun `host closes a control connection that never authenticates`() {
        val host = TcpTunnelServer("127.0.0.1", 0, authPin = "1234")
        host.start()
        try {
            Socket("127.0.0.1", host.boundPort).use { s ->
                s.soTimeout = 5_000
                // A CONNECT frame with no AUTH first: a protocol violation.
                // payload = 4-byte IP + 2-byte port.
                val connect = byteArrayOf(1, 2, 3, 4, 0, 80)
                s.getOutputStream().write(frame(1, TunnelProtocol.TYPE_CONNECT, connect))
                s.getOutputStream().flush()
                // The host must refuse outright — no CONNECTED, just close.
                assertEquals(-1, s.getInputStream().read())
            }
            assertEquals(0, host.connsAccepted.get())
            assertEquals(0, host.connsRejected.get())
        } finally {
            host.stop()
        }
    }

    @Test
    fun `correct pin over the raw protocol is accepted`() {
        val host = TcpTunnelServer("127.0.0.1", 0, authPin = "1234", destinationPolicy = DestinationPolicy.PERMISSIVE)
        host.start()
        try {
            Socket("127.0.0.1", host.boundPort).use { s ->
                s.soTimeout = 5_000
                val out = s.getOutputStream()
                out.write(frame(0, TunnelProtocol.TYPE_AUTH, "1234".toByteArray()))
                out.flush()
                // Now CONNECT to a port that is almost certainly closed.
                val connect = byteArrayOf(127, 0, 0, 1, 0, 1) // 127.0.0.1:1
                out.write(frame(1, TunnelProtocol.TYPE_CONNECT, connect))
                out.flush()
                val reply = readFrame(s.getInputStream())
                assertTrue("expected a REJECTED (connect refused), got $reply", reply != null)
                assertEquals(TunnelProtocol.TYPE_REJECTED, reply!!.second)
                assertEquals(1, host.connsRejected.get())
            }
        } finally {
            host.stop()
        }
    }

    @Test
    fun `client without a pin cannot open connections on a pin-protected host`() {
        val host = TcpTunnelServer(
            "127.0.0.1",
            0,
            authPin = "1234",
            destinationPolicy = DestinationPolicy.PERMISSIVE,
        )
        host.start()
        val disconnected = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        try {
            // authPin = null: the client never sends AUTH, so its first
            // CONNECT is a protocol violation and the host kills the link.
            val client = TcpTunnelClient(
                host = "127.0.0.1",
                port = host.boundPort,
                writeToTun = {},
                onDisconnected = {
                    disconnected.set(true)
                    latch.countDown()
                },
                log = {},
            )
            assertTrue(client.start())
            val syn = Ipv4Codec.wrapTcp(
                srcIp = appIp, srcPort = appPort,
                dstIp = serverIp, dstPort = 9,
                seq = 1000, ack = 0, flags = TcpFlags.SYN, window = 65535,
            )
            client.onIpPacket(syn)
            assertTrue("unauthenticated client was not cut off", latch.await(5, TimeUnit.SECONDS))
            assertTrue(disconnected.get())
            client.stop()
        } finally {
            host.stop()
        }
    }
}
