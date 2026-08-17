package com.sharenet.app.tunnel

import com.sharenet.app.proxy.DestinationPolicy
import com.sharenet.app.proxy.Ipv4Codec
import com.sharenet.app.proxy.TcpFlags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Full-loopback integration test of the Tier-2 TCP tunnel:
 *
 *   [fake app] -> crafted packets -> [TcpTunnelClient] --frames--> [TcpTunnelServer] --real socket--> [echo server]
 *
 * The client's reply packets (the stack's SYN-ACK / data / ACK) are captured
 * in a queue, and the test asserts the echo round-trip end to end.
 */
class TcpTunnelIntegrationTest {

    private val appIp = "26.0.0.2"
    private val appPort = 41000
    private val serverIp = "127.0.0.1"

    private class EchoServer {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val sockets = mutableListOf<Socket>()
        private val acceptThread = thread(name = "echo-accept", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val s = try {
                    serverSocket.accept()
                } catch (e: Exception) {
                    break
                }
                sockets += s
                thread(name = "echo-conn", isDaemon = true) {
                    try {
                        val buf = ByteArray(4096)
                        val input = s.getInputStream()
                        val output = s.getOutputStream()
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            output.flush()
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { s.close() }
                    }
                }
            }
        }

        val port: Int get() = serverSocket.localPort

        fun close() {
            runCatching { serverSocket.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private class AppReplyCollector {
        val packets = LinkedBlockingQueue<ByteArray>()
        fun take(): ByteArray = packets.poll(5, TimeUnit.SECONDS) ?: error("no reply packet")
        fun take(timeoutMs: Long): ByteArray? = packets.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private data class TcpSegment(
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
        return TcpSegment(seq, ack, flags, payload)
    }

    private fun read32(packet: ByteArray, offset: Int): Long =
        ((packet[offset].toLong() and 0xFF) shl 24) or
            ((packet[offset + 1].toLong() and 0xFF) shl 16) or
            ((packet[offset + 2].toLong() and 0xFF) shl 8) or
            (packet[offset + 3].toLong() and 0xFF)

    @Test
    fun `notifies the owner when the host control connection dies`() {
        val host = TcpTunnelServer("127.0.0.1", 0, destinationPolicy = DestinationPolicy.PERMISSIVE)
        host.start()
        val disconnected = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        try {
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
            // Host stops (as when the sharing session ends): the client must
            // learn about it instead of silently keeping a dead tunnel.
            host.stop()
            assertTrue(
                "owner was not notified of the dead control connection",
                latch.await(5, TimeUnit.SECONDS),
            )
            assertTrue(disconnected.get())
            client.stop()
        } finally {
            host.stop()
        }
    }

    @Test
    fun `intentional stop does not trigger the disconnected callback`() {
        val host = TcpTunnelServer("127.0.0.1", 0, destinationPolicy = DestinationPolicy.PERMISSIVE)
        host.start()
        val disconnected = AtomicBoolean(false)
        try {
            val client = TcpTunnelClient(
                host = "127.0.0.1",
                port = host.boundPort,
                writeToTun = {},
                onDisconnected = { disconnected.set(true) },
                log = {},
            )
            assertTrue(client.start())
            client.stop() // user-initiated: must NOT look like a failure
            Thread.sleep(300)
            assertTrue(!disconnected.get())
        } finally {
            host.stop()
        }
    }

    @Test
    fun `heartbeat ping-pong keeps the control connection alive`() {
        val host = TcpTunnelServer("127.0.0.1", 0, destinationPolicy = DestinationPolicy.PERMISSIVE)
        host.start()
        try {
            val client = TcpTunnelClient(
                host = "127.0.0.1",
                port = host.boundPort,
                writeToTun = {},
                heartbeatMs = 200, // fast heartbeat for the test
                log = {},
            )
            assertTrue(client.start())
            // Idle: the only frames the client can receive are PONG replies to
            // its own PINGs — receiving several proves the round-trip works.
            val deadline = System.currentTimeMillis() + 3_000
            while (client.framesReceived.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("expected PONG replies, received ${client.framesReceived.get()}", client.framesReceived.get() >= 3)
            client.stop()
        } finally {
            host.stop()
        }
    }

    @Test
    fun `client with the correct pin can use the tunnel`() {
        val echo = EchoServer()
        val host = TcpTunnelServer(
            "127.0.0.1",
            0,
            authPin = "1234",
            destinationPolicy = DestinationPolicy.PERMISSIVE,
        )
        host.start()
        try {
            val replies = AppReplyCollector()
            val client = TcpTunnelClient(
                host = "127.0.0.1",
                port = host.boundPort,
                writeToTun = { replies.packets.add(it) },
                authPin = "1234",
                log = {},
            )
            assertTrue(client.start())
            try {
                // The handshake succeeding proves the host accepted the AUTH
                // frame and opened the real socket (it refuses otherwise).
                val syn = Ipv4Codec.wrapTcp(
                    srcIp = appIp, srcPort = appPort,
                    dstIp = serverIp, dstPort = echo.port,
                    seq = 1000, ack = 0, flags = TcpFlags.SYN, window = 65535,
                )
                client.onIpPacket(syn)
                val synAck = parseTcp(replies.take())
                assertTrue((synAck.flags and TcpFlags.SYN) != 0)
                assertEquals(1001, synAck.ack)
            } finally {
                client.stop()
            }
        } finally {
            host.stop()
            echo.close()
        }
    }

    @Test
    fun `client with a wrong pin is rejected with AUTH_REJECTED`() {
        val host = TcpTunnelServer(
            "127.0.0.1",
            0,
            authPin = "1234",
            destinationPolicy = DestinationPolicy.PERMISSIVE,
        )
        host.start()
        val latch = CountDownLatch(1)
        val reason = arrayOfNulls<DisconnectReason>(1)
        try {
            val client = TcpTunnelClient(
                host = "127.0.0.1",
                port = host.boundPort,
                writeToTun = {},
                authPin = "0000",
                onDisconnected = { r ->
                    reason[0] = r
                    latch.countDown()
                },
                log = {},
            )
            assertTrue(client.start())
            assertTrue("wrong pin was not rejected", latch.await(5, TimeUnit.SECONDS))
            assertEquals(DisconnectReason.AUTH_REJECTED, reason[0])
            client.stop()
        } finally {
            host.stop()
        }
    }

    @Test
    fun `host refuses connections into private networks`() {
        val host = TcpTunnelServer("127.0.0.1", 0) // strict by default
        host.start()
        try {
            val replies = AppReplyCollector()
            val client = TcpTunnelClient(
                host = "127.0.0.1",
                port = host.boundPort,
                writeToTun = { replies.packets.add(it) },
                log = {},
            )
            assertTrue(client.start())
            try {
                // App tries to reach a private LAN address through the tunnel.
                val syn = Ipv4Codec.wrapTcp(
                    srcIp = appIp, srcPort = appPort,
                    dstIp = "192.168.1.10", dstPort = 80,
                    seq = 1000, ack = 0, flags = TcpFlags.SYN, window = 65535,
                )
                client.onIpPacket(syn)
                // Optimistic SYN-ACK first...
                val synAck = parseTcp(replies.take())
                assertTrue((synAck.flags and TcpFlags.SYN) != 0)
                // ...then the host rejects and the stack resets the app.
                val rst = parseTcp(replies.take())
                assertTrue("expected RST, flags=${rst.flags}", (rst.flags and TcpFlags.RST) != 0)
                assertEquals(0, client.stats.connsActive)
            } finally {
                client.stop()
            }
        } finally {
            host.stop()
        }
    }

    @Test
    fun `echo round-trip through the full tunnel`() {
        val echo = EchoServer()
        val host = TcpTunnelServer("127.0.0.1", 0, destinationPolicy = DestinationPolicy.PERMISSIVE)
        host.start()
        try {
            val replies = AppReplyCollector()
            val client = TcpTunnelClient(
                host = "127.0.0.1",
                port = host.boundPort,
                writeToTun = { replies.packets.add(it) },
                log = {},
            )
            assertTrue(client.start())
            try {
                // 1. App connects to the echo server.
                val syn = Ipv4Codec.wrapTcp(
                    srcIp = appIp, srcPort = appPort,
                    dstIp = serverIp, dstPort = echo.port,
                    seq = 1000, ack = 0, flags = TcpFlags.SYN, window = 65535,
                )
                client.onIpPacket(syn)
                val synAck = parseTcp(replies.take())
                assertTrue((synAck.flags and TcpFlags.SYN) != 0)
                assertTrue((synAck.flags and TcpFlags.ACK) != 0)
                assertEquals(1001, synAck.ack)
                val isn = synAck.seq

                // 2. App completes the handshake.
                client.onIpPacket(
                    Ipv4Codec.wrapTcp(
                        srcIp = appIp, srcPort = appPort,
                        dstIp = serverIp, dstPort = echo.port,
                        seq = 1001, ack = isn + 1, flags = TcpFlags.ACK, window = 65535,
                    ),
                )
                // The stack ACKs nothing extra on the handshake ACK.

                // 3. App sends data; the echo server must bounce it back.
                val message = "ping-through-the-tcp-tunnel".toByteArray()
                client.onIpPacket(
                    Ipv4Codec.wrapTcp(
                        srcIp = appIp, srcPort = appPort,
                        dstIp = serverIp, dstPort = echo.port,
                        seq = 1001, ack = isn + 1, flags = TcpFlags.ACK or TcpFlags.PSH,
                        window = 65535, payload = message, payloadLength = message.size,
                    ),
                )
                // Expect an ACK from the stack...
                val ackSeg = parseTcp(replies.take())
                assertEquals(1001L + message.size.toLong(), ackSeg.ack)

                // ...and the echoed payload delivered as a TCP segment to the app.
                val dataSeg = parseTcp(replies.take())
                assertArrayEquals(message, dataSeg.payload)
                assertEquals(isn + 1, dataSeg.seq)

                // 4. App ACKs the echoed data.
                client.onIpPacket(
                    Ipv4Codec.wrapTcp(
                        srcIp = appIp, srcPort = appPort,
                        dstIp = serverIp, dstPort = echo.port,
                        seq = 1001L + message.size.toLong(),
                        ack = dataSeg.seq + dataSeg.payload.size.toLong(),
                        flags = TcpFlags.ACK, window = 65535,
                    ),
                )

                // 5. Server side closes (echo closes after read EOF? it does not —
                //    our app closes instead). App sends FIN; server echoes EOF.
                client.onIpPacket(
                    Ipv4Codec.wrapTcp(
                        srcIp = appIp, srcPort = appPort,
                        dstIp = serverIp, dstPort = echo.port,
                        seq = 1001L + message.size.toLong(),
                        ack = dataSeg.seq + dataSeg.payload.size.toLong(),
                        flags = TcpFlags.FIN or TcpFlags.ACK, window = 65535,
                    ),
                )
                val finAck = parseTcp(replies.take())
                assertTrue((finAck.flags and TcpFlags.ACK) != 0)

                // The host's real socket sees EOF (server closed after the
                // app's FIN), so REMOTE_CLOSE arrives and the stack sends FIN.
                val fin = replies.take()
                val finSeg = parseTcp(fin)
                assertTrue((finSeg.flags and TcpFlags.FIN) != 0)

                // 6. App acks the FIN; the connection is gone.
                client.onIpPacket(
                    Ipv4Codec.wrapTcp(
                        srcIp = appIp, srcPort = appPort,
                        dstIp = serverIp, dstPort = echo.port,
                        seq = 1001L + message.size.toLong() + 1,
                        ack = finSeg.seq + 1, flags = TcpFlags.ACK, window = 65535,
                    ),
                )
                Thread.sleep(200)
                assertEquals(0, client.stats.connsActive)
                assertTrue(host.connsAccepted.get() >= 1)
                assertEquals(message.size.toLong(), host.bytesClientToServer.get())
                assertEquals(message.size.toLong(), host.bytesServerToClient.get())
            } finally {
                client.stop()
            }
        } finally {
            host.stop()
            echo.close()
        }
    }
}
