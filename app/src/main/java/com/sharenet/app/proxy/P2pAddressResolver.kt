package com.sharenet.app.proxy

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Resolves the IPv4 address of the Wi-Fi Direct group owner (GO) interface,
 * which is where the proxy and UDP relay must listen so that ONLY hotspot
 * clients can reach them — never the phone's upstream Wi-Fi network.
 *
 * Android's P2P group convention is 192.168.49.0/24 with the GO at
 * 192.168.49.1 (set by WifiP2pService), so that is both the preferred match
 * and the fallback. The upstream interface (wlan0) is deliberately NEVER a
 * candidate: binding there would expose the proxy on the phone's own Wi-Fi
 * network and would be unreachable from P2P clients anyway.
 *
 * Pure JVM (java.net only) — on the test JVM this simply returns the default.
 */
object P2pAddressResolver {

    const val DEFAULT_GO_IP = "192.168.49.1"

    private const val DEFAULT_SUBNET_PREFIX = "192.168.49."

    fun resolveGroupOwnerAddress(): String {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        // 1. Any interface already holding the default P2P subnet — the GO.
        for (iface in interfaces) {
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    val host = address.hostAddress ?: continue
                    if (host.startsWith(DEFAULT_SUBNET_PREFIX)) return host
                }
            }
        }

        // 2. An interface whose name looks like the P2P/AP interface.
        for (iface in interfaces) {
            val name = iface.name.lowercase()
            if (name.contains("p2p") || name.contains("ap")) {
                firstIpv4(iface)?.let { return it }
            }
        }

        // 3. Android's fixed P2P GO address (set as soon as the group forms).
        return DEFAULT_GO_IP
    }

    private fun firstIpv4(iface: NetworkInterface): String? {
        for (address in iface.inetAddresses) {
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress
            }
        }
        return null
    }
}
