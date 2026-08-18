package com.sharenet.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.lang.reflect.Method

/**
 * Manages the system's native hotspot (softAP) while keeping Wi-Fi connected.
 *
 * On devices that support STA+AP concurrency (Android 9+, most modern phones),
 * the OS can run a regular access point alongside the Wi-Fi station connection.
 * This is superior to Wi-Fi Direct because:
 *   - ANY WiFi device can connect (PCs, phones, tablets, IoT)
 *   - No proxy configuration needed — the OS handles NAT/routing
 *   - Standard WPA2/WPA3 security
 *
 * Strategy:
 *   1. Try hidden `WifiManager.setWifiApEnabled()` API (Samsung, some OEMs)
 *   2. Try `ConnectivityManager.startTethering()` (Android 10+)
 *   3. Fall back to deep-linking to system hotspot settings
 *
 * The caller should check [isAvailable] before attempting to start.
 */
class NativeHotspotManager(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {

    interface Listener {
        fun onHotspotStarted(ssid: String, password: String)
        fun onHotspotFailed(reason: String)
        fun onHotspotStopped()
    }

    private var listener: Listener? = null
    private var started = false
    private var hotspotReceiver: BroadcastReceiver? = null

    /**
     * Whether this device likely supports STA+AP concurrency.
     * Checks the OS version and known device capabilities.
     */
    val isAvailable: Boolean by lazy {
        // Android 9+ introduced STA+AP concurrency in AOSP
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@lazy false

        // Check if the device reports STA+AP support via WifiManager
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                val method: Method = wifiManager.javaClass.getMethod("isStaApConcurrencySupported")
                val result = method.invoke(wifiManager) as? Boolean ?: false
                if (result) return@lazy true
            }
        } catch (_: Exception) {
            // Method not available on this device
        }

        // Samsung devices with Android 13+ typically support Wi-Fi Sharing
        val manufacturer = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        if (manufacturer == "samsung" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return@lazy true
        }

        // Pixel 6+ and other recent devices
        if (manufacturer == "google" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return@lazy true
        }

        // Default: assume not supported unless proven otherwise
        false
    }

    /**
     * The SSID of the hotspot after it starts. Only valid after [Listener.onHotspotStarted].
     */
    var hotspotSsid: String? = null
        private set

    /**
     * The password of the hotspot after it starts. Only valid after [Listener.onHotspotStarted].
     */
    var hotspotPassword: String? = null
        private set

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (started) return
        this.listener = listener

        if (!isAvailable) {
            listener.onHotspotFailed("Device does not support concurrent STA+AP")
            return
        }

        // Try approach 1: Hidden WifiManager API (Samsung, some OEMs)
        if (trySetWifiApEnabled()) {
            return
        }

        // Try approach 2: Start tethering via reflection
        if (tryStartTethering()) {
            return
        }

        // Fall back: guide user to Settings
        listener.onHotspotFailed(
            "Please enable the hotspot manually:\n" +
            "Settings → Connections → Mobile Hotspot and Tethering → Mobile Hotspot\n" +
            "Then enable 'Wi-Fi Sharing' if available."
        )
    }

    fun stop() {
        if (!started) return
        started = false

        // Unregister receiver
        hotspotReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        hotspotReceiver = null

        // Try to disable hotspot
        tryDisableAp()

        listener?.onHotspotStopped()
        listener = null
    }

    /**
     * Try to enable the hotspot using the hidden WifiManager.setWifiApEnabled() API.
     * This works on Samsung and some other OEM devices.
     */
    private fun trySetWifiApEnabled(): Boolean {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method: Method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            )
            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = "ShareNet-${Build.MODEL.take(8)}"
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                preSharedKey = generatePassword()
            }
            val result = method.invoke(wifiManager, config, true) as? Boolean ?: false
            if (result) {
                started = true
                hotspotSsid = config.SSID?.removeSurrounding("\"")
                hotspotPassword = config.preSharedKey
                registerReceiver()
                Log.d(TAG, "Hotspot enabled via setWifiApEnabled: ${config.SSID}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "setWifiApEnabled not available: ${e.message}")
            false
        }
    }

    /**
     * Try to start tethering using the hidden ConnectivityManager.startTethering() API.
     * This works on Android 10+ devices.
     */
    @SuppressLint("DiscouragedPrivateApi")
    private fun tryStartTethering(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val method: Method = cm.javaClass.getMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback"),
            )
            // This is complex and device-specific; just return false for now
            // The actual tethering callback would need to be implemented
            false
        } catch (e: Exception) {
            Log.d(TAG, "startTethering not available: ${e.message}")
            false
        }
    }

    private fun tryDisableAp() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method: Method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            )
            method.invoke(wifiManager, null, false)
        } catch (_: Exception) {
            // Best effort
        }
    }

    private fun registerReceiver() {
        hotspotReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                        val state = intent.getIntExtra("wifi_state", -1)
                        if (state == 11) { // WIFI_AP_STATE_DISABLED
                            started = false
                            listener?.onHotspotStopped()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED")
        ContextCompat.registerReceiver(context, hotspotReceiver!!, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun generatePassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    companion object {
        private const val TAG = "NativeHotspot"
    }
}
