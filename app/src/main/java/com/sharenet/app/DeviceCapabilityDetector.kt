package com.sharenet.app

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * Detects the device's internet-sharing capabilities and communicates
 * what types of clients can connect.
 *
 * Three capability levels:
 * 1. NATIVE_HOTSPOT: Device supports STA+AP concurrency — any device can connect
 * 2. P2P_ONLY: Device only supports Wi-Fi Direct — Android clients only
 * 3. NONE: Device cannot share internet
 */
class DeviceCapabilityDetector(context: Context) {

    enum class SharingCapability {
        /** Device supports concurrent STA+AP — any client (PC, phone, tablet) can connect */
        NATIVE_HOTSPOT,
        /** Device only supports Wi-Fi Direct P2P — Android clients only */
        P2P_ONLY,
        /** Device cannot share internet at all */
        NONE,
    }

    data class CapabilityReport(
        val capability: SharingCapability,
        val description: String,
        val clientCompatibility: String,
        val recommendedAction: String,
    )

    val report: CapabilityReport by lazy {
        detect(context)
    }

    private fun detect(context: Context): CapabilityReport {
        // Check 1: API-level STA+AP concurrency support (Android 11+)
        val apiSupportsStaAp = checkApiStaApSupport(context)

        // Check 2: Samsung Wi-Fi Sharing feature
        val samsungWifiSharing = checkSamsungWifiSharing(context)

        // Check 3: Wi-Fi Direct P2P support (virtually all Android devices)
        val p2pSupported = checkP2pSupport(context)

        Log.d(TAG, "STA+AP via API: $apiSupportsStaAp, Samsung Wi-Fi Sharing: $samsungWifiSharing, P2P: $p2pSupported")

        return when {
            apiSupportsStaAp || samsungWifiSharing -> CapabilityReport(
                capability = SharingCapability.NATIVE_HOTSPOT,
                description = "Your device supports sharing WiFi while staying connected.\n" +
                    "Any device (PC, phone, tablet) can connect — no configuration needed.",
                clientCompatibility = "PC ✓  Phone ✓  Tablet ✓  Smart TV ✓",
                recommendedAction = "Enable Wi-Fi Sharing:\n" +
                    "1. Go to Settings → Connections → Mobile Hotspot\n" +
                    "2. Tap Advanced → Enable 'Wi-Fi Sharing'\n" +
                    "3. Turn on Mobile Hotspot",
            )
            p2pSupported -> CapabilityReport(
                capability = SharingCapability.P2P_ONLY,
                description = "This device uses Wi-Fi Direct to share.\n" +
                    "Only Android phones with the ShareNet app can fully use the shared connection.",
                clientCompatibility = "PC ✗  Phone (with app) ✓  Phone (no app) ⚠  Tablet ✗",
                recommendedAction = "To share with PCs, use a device with Wi-Fi Sharing support\n" +
                    "(most Samsung S/A series from 2020+, Google Pixel 6+).",
            )
            else -> CapabilityReport(
                capability = SharingCapability.NONE,
                description = "This device cannot share its internet connection.",
                clientCompatibility = "No devices",
                recommendedAction = "This device's hardware does not support internet sharing.",
            )
        }
    }

    private fun checkApiStaApSupport(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isStaApConcurrencySupported == true
        } catch (e: Exception) {
            false
        }
    }

    private fun checkSamsungWifiSharing(context: Context): Boolean {
        // Samsung devices with STA+AP have a Wi-Fi Sharing setting
        // Check via Settings.Global
        return try {
            val value = android.provider.Settings.Global.getString(
                context.contentResolver,
                "hotspot_wifi_sharing",
            )
            value != null // Setting exists = device supports it
        } catch (e: Exception) {
            false
        }
    }

    private fun checkP2pSupport(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_WIFI_DIRECT,
        )
    }

    companion object {
        private const val TAG = "DeviceCapability"
    }
}
