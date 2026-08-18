package com.sharenet.app.proxy

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Android implementation of [PingSocket] over a kernel "ping socket"
 * (`socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)`) via libcore's [Os] — no raw
 * socket, no root.
 *
 * Rootless because stock Android sets `net.ipv4.ping_group_range` to
 * `0 2147483647` (verified on device), which lets any UID open ping sockets —
 * the same mechanism the `ping` binary uses. The socket is bound to the
 * client's ICMP id, so echo replies carry that id and the client's ping tool
 * accepts them.
 *
 * Fallback behavior: if the kernel or an OEM build refuses the socket
 * ([ErrnoException] on create/bind), [create] returns null and the relay
 * drops ICMP as before — the share session keeps working.
 *
 * There is no receive timeout: [receive] blocks until a reply arrives or the
 * socket is closed (the relay's sweeper closes idle flows, waking it).
 */
class OsPingSocket private constructor(private val fd: FileDescriptor) : PingSocket {

    override fun send(dstIp: String, data: ByteArray, offset: Int, length: Int) {
        val dst = InetAddress.getByName(dstIp)
        Os.connect(fd, dst, 0)
        Os.write(fd, data, offset, length)
    }

    override fun receive(): Pair<ByteArray, String>? {
        val buf = ByteArray(512)
        val src = InetSocketAddress(0) // recvfrom fills this (wildcard if not)
        val n = try {
            Os.recvfrom(fd, buf, 0, buf.size, 0, src)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN) return null // recv timeout
            throw e
        }
        if (n <= 0) return null
        // "0.0.0.0" means the source could not be read; the relay falls back
        // to the destination the client asked for.
        val srcIp = src.getAddress()?.hostAddress ?: "0.0.0.0"
        return buf.copyOf(n) to srcIp
    }

    override fun close() {
        runCatching { Os.close(fd) }
    }

    companion object {
        /**
         * Opens and binds a ping socket for [id]; null when the kernel
         * refuses (e.g. an OEM that restricts ping sockets).
         */
        fun create(id: Int): OsPingSocket? = try {
            val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
            // Binding the "port" sets the ICMP id: replies echo it back to us.
            Os.bind(fd, InetAddress.getByName("0.0.0.0"), id)
            OsPingSocket(fd)
        } catch (_: ErrnoException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}
