package com.sharenet.app.proxy

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Decides whether a destination may be reached through ShareNet.
 *
 * ShareNet exists to share the phone's internet connection with devices on
 * the Wi-Fi Direct network, so a joined client must be able to reach public
 * hosts. But a malicious or buggy client must NOT be able to use the phone as
 * a stepping stone into the phone's OWN network — the LAN it is connected to,
 * loopback services on the phone, link-local hosts, multicast, or broadcast.
 * A joined device could otherwise scan the host's home network or probe the
 * phone itself through the proxy, the UDP relay, or the TCP tunnel relay.
 *
 * The policy therefore allows:
 *  - public addresses (anything outside the private/reserved ranges below), and
 *  - the P2P subnet itself (192.168.49.0/24), so hotspot-local traffic
 *    (e.g. another client's game server) still works.
 *
 * Everything else is refused.
 *
 * Pure JVM — unit-tested.
 */
fun interface DestinationPolicy {
    /** Returns true when a connection to this destination may be opened. */
    fun allow(host: String): Boolean

    companion object {
        /** Production policy: public destinations + the P2P subnet only. */
        val STRICT: DestinationPolicy = DestinationPolicy { host -> DestinationRules.isAllowed(host) }

        /** Allow everything — for tests and local debugging only. */
        val PERMISSIVE: DestinationPolicy = DestinationPolicy { true }
    }
}

object DestinationRules {

    private val P2P_A = 192
    private val P2P_B = 168
    private val P2P_C = 49

    /**
     * Checks a destination by name or literal IP. For hostnames every resolved
     * address must be allowed; a name that resolves to any private address is
     * refused outright (the phone's DNS resolves on behalf of clients, so the
     * client cannot poison it — but a dual-stack A/AAAA mix is possible).
     */
    fun isAllowed(host: String): Boolean {
        val name = host.trim().removeSurrounding("[", "]")
        if (name.isEmpty()) return false
        if (isIpv4Literal(name)) return isAllowedIpv4(name)

        val addresses = try {
            InetAddress.getAllByName(name)
        } catch (_: Exception) {
            return false
        }
        if (addresses.isEmpty()) return false
        return addresses.all { allowAddress(it) }
    }

    /** Pure range check for a dotted-quad IPv4 literal. */
    fun isAllowedIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return false
            if (v < 0 || v > 255) return false
            octets[i] = v
        }
        val a = octets[0]
        val b = octets[1]
        val c = octets[2]

        // The P2P subnet: hotspot-local destinations stay reachable.
        if (a == P2P_A && b == P2P_B && c == P2P_C) return true

        return when {
            a == 0 -> false                                   // 0.0.0.0/8 "this network"
            a == 10 -> false                                  // RFC1918 10.0.0.0/8
            a == 127 -> false                                 // loopback
            a == 169 && b == 254 -> false                     // link-local 169.254.0.0/16
            a == 172 && b in 16..31 -> false                  // RFC1918 172.16.0.0/12
            a == 192 && b == 168 -> false                     // RFC1918 192.168.0.0/16 (except P2P above)
            a == 192 && b == 0 && c == 2 -> false             // TEST-NET-1 192.0.2.0/24
            a == 192 && b == 0 && c == 0 -> false             // IETF protocol assignments 192.0.0.0/24
            a == 198 && (b == 18 || b == 19) -> false         // benchmarking 198.18.0.0/15
            a == 198 && b == 51 && c == 100 -> false          // TEST-NET-2 198.51.100.0/24
            a == 203 && b == 0 && c == 113 -> false           // TEST-NET-3 203.0.113.0/24
            a in 224..239 -> false                            // multicast
            a >= 240 -> false                                 // reserved incl. 255.255.255.255 broadcast
            else -> true
        }
    }

    private fun allowAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress) {
            return false
        }
        return when (address) {
            is Inet4Address -> isAllowedIpv4(address.hostAddress ?: return false)
            is Inet6Address -> {
                val bytes = address.address
                if (bytes.size != 16) return false
                val b0 = bytes[0].toInt() and 0xFF
                // fc00::/7 unique-local (private) — never relayed.
                if (b0 and 0xFE == 0xFC) return false
                true
            }
            else -> false
        }
    }

    private fun isIpv4Literal(host: String): Boolean {
        if (host.isEmpty()) return false
        var dots = 0
        for (ch in host) {
            if (ch == '.') dots++
            else if (ch !in '0'..'9') return false
        }
        return dots == 3
    }
}
