package com.sharenet.app.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

class DnsForwarderTest {

    /** A fake upstream resolver: echoes the query with the QR bit set. */
    private class FakeResolver {
        val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        private val thread = Thread {
            val buf = ByteArray(4096)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(p)
                } catch (e: Exception) {
                    break
                }
                val answer = p.data.copyOf(p.length)
                if (answer.size >= 2) {
                    answer[2] = (answer[2].toInt() or 0x80).toByte() // set QR (response)
                }
                socket.send(DatagramPacket(answer, answer.size, p.socketAddress))
            }
        }.apply { isDaemon = true }

        init {
            thread.start()
        }

        val port: Int get() = socket.localPort

        fun close() = socket.close()
    }

    private fun queryBytes(txId: Int): ByteArray {
        val q = ByteArray(12)
        q[0] = (txId shr 8).toByte()
        q[1] = txId.toByte()
        return q
    }

    private fun txIdOf(packet: ByteArray): Int =
        ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)

    @Test
    fun `forwards a query and returns the upstream answer`() {
        val resolver = FakeResolver()
        try {
            val forwarder = DnsForwarder(
                bindHost = "127.0.0.1",
                port = 0,
                upstreamServers = listOf(InetSocketAddress("127.0.0.1", resolver.port)),
            )
            forwarder.start()
            try {
                val client = DatagramSocket()
                client.soTimeout = 5000
                val q = queryBytes(0x1234)
                client.send(DatagramPacket(q, q.size, InetSocketAddress("127.0.0.1", forwarder.boundPort)))

                val buf = ByteArray(4096)
                val reply = DatagramPacket(buf, buf.size)
                client.receive(reply)

                assertEquals(12, reply.length)
                assertEquals(0x1234, txIdOf(reply.data))
                // QR bit set: it is a response, not a query.
                assertTrue((reply.data[2].toInt() and 0x80) != 0)
                // The rest of the query is forwarded verbatim (byte 2 was the
                // only byte the fake resolver touched).
                for (i in q.indices) {
                    if (i == 2) continue
                    assertEquals("byte $i", q[i], reply.data[i])
                }
                assertTrue(forwarder.queriesAnswered.get() >= 1)
                client.close()
            } finally {
                forwarder.stop()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun `tries the next upstream when the first is silent`() {
        val silent = DatagramSocket(0, InetAddress.getByName("127.0.0.1")) // never replies
        val resolver = FakeResolver()
        try {
            val forwarder = DnsForwarder(
                bindHost = "127.0.0.1",
                port = 0,
                upstreamServers = listOf(
                    InetSocketAddress("127.0.0.1", silent.localPort),
                    InetSocketAddress("127.0.0.1", resolver.port),
                ),
            )
            forwarder.start()
            try {
                val client = DatagramSocket()
                client.soTimeout = 8000
                val q = queryBytes(0x00AB)
                client.send(DatagramPacket(q, q.size, InetSocketAddress("127.0.0.1", forwarder.boundPort)))
                val buf = ByteArray(4096)
                val reply = DatagramPacket(buf, buf.size)
                client.receive(reply)
                assertEquals(0x00AB, txIdOf(reply.data))
                assertTrue((reply.data[2].toInt() and 0x80) != 0)
                // The counter is bumped on the executor thread right after
                // the reply is sent; poll briefly for it.
                val deadline = System.currentTimeMillis() + 2000
                while (forwarder.queriesAnswered.get() < 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
                assertTrue(forwarder.queriesAnswered.get() >= 1)
                client.close()
            } finally {
                forwarder.stop()
            }
        } finally {
            silent.close()
            resolver.close()
        }
    }

    @Test
    fun `drops the query when no upstream answers`() {
        val silent = DatagramSocket(0, InetAddress.getByName("127.0.0.1")) // never replies
        try {
            val forwarder = DnsForwarder(
                bindHost = "127.0.0.1",
                port = 0,
                upstreamServers = listOf(InetSocketAddress("127.0.0.1", silent.localPort)),
            )
            forwarder.start()
            try {
                val client = DatagramSocket()
                client.soTimeout = 500
                val q = queryBytes(0x7777)
                client.send(DatagramPacket(q, q.size, InetSocketAddress("127.0.0.1", forwarder.boundPort)))
                val buf = ByteArray(4096)
                val reply = DatagramPacket(buf, buf.size)
                try {
                    client.receive(reply)
                    // Upstream never answers, so nothing should come back.
                    assertEquals("expected no reply", 0, reply.length)
                } catch (_: SocketTimeoutException) {
                    // expected — this is the success case
                }
                // The forwarder counts it as failed after its own timeout.
                Thread.sleep(3200)
                assertTrue(forwarder.queriesFailed.get() >= 1)
                client.close()
            } finally {
                forwarder.stop()
            }
        } finally {
            silent.close()
        }
    }
}
