package com.sharenet.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.InetAddress

/** Describes the phone's current upstream connection for the UI. */
object NetworkInfo {

    /**
     * The DNS resolvers of the active network, falling back to well-known
     * public resolvers so the forwarder always has somewhere to send queries.
     */
    fun dnsServers(context: Context): List<InetAddress> {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val link = network?.let { cm.getLinkProperties(it) }
        val systemDns = link?.dnsServers ?: emptyList()
        val resolvers = systemDns.ifEmpty {
            FALLBACK_DNS.mapNotNull { addr -> runCatching { InetAddress.getByName(addr) }.getOrNull() }
        }
        return resolvers.distinctBy { it.hostAddress }
    }

    private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")

    /**
     * Human-readable description of the active upstream, or null when there is
     * no usable network. Reading the SSID can throw when location/nearby
     * permissions are missing, so it degrades to "Wi-Fi".
     */
    fun describe(context: Context): String? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val ssid = wifiSsid(context)
                if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") "Wi-Fi" else ssid
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular data"
            else -> "Network"
        }
    }

    private fun wifiSsid(context: Context): String? = try {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.connectionInfo?.ssid
    } catch (_: Exception) {
        null
    }
}
