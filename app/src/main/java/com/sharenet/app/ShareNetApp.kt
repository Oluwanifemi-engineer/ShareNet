package com.sharenet.app

import android.app.Application
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message

/**
 * Application entry point.
 *
 * Currently only initializes crash reporting (Sentry), and only when a DSN is
 * configured in `sentry.properties` — so the build and the app behave
 * identically without any account, and no crash data ever leaves the device
 * unless the developer opts in (see README "Crash reporting").
 *
 * Crash payloads are scrubbed of the Wi-Fi Direct subnet (192.168.49.x) and
 * the tunnel's virtual range (26.0.0.x) before leaving the device: those
 * addresses identify the sharer's session, not the bug.
 */
class ShareNetApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.SENTRY_DSN.isBlank()) return
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                event.message?.formatted?.let { formatted ->
                    event.message = Message().apply { this.formatted = scrub(formatted) }
                }
                event.exceptions?.forEach { ex ->
                    ex.value = scrub(ex.value ?: "")
                }
                event
            }
        }
    }

    private fun scrub(text: String): String =
        text.replace(P2P_IP, "[p2p-ip]").replace(TUNNEL_IP, "[tunnel-ip]")

    companion object {
        // 192.168.49.0/24 is the Wi-Fi Direct group-owner subnet; 26.0.0.x is
        // the client tunnel's virtual range. Both identify the session.
        private val P2P_IP = Regex("\\b192\\.168\\.49\\.\\d{1,3}\\b")
        private val TUNNEL_IP = Regex("\\b26\\.0\\.0\\.\\d{1,3}\\b")
    }
}
