package com.sharenet.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Owns the Wi-Fi Direct (P2P) group on the sharing phone.
 *
 * The phone stays connected to its upstream Wi-Fi (STA mode) and ALSO becomes
 * a P2P Group Owner (GO). STA + P2P-GO concurrency is supported by virtually
 * all chipsets — it is the same mechanism Android used for Cast/Miracast while
 * connected to Wi-Fi — which is what makes this whole app possible without
 * root and without turning off the phone's own Wi-Fi.
 *
 * The P2P group shows up to other devices as a normal Wi-Fi network whose
 * name starts with "DIRECT-"; clients join it with the passphrase reported via
 * [Listener.onGroupCreated].
 */
class WifiDirectManager(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {

    interface Listener {
        fun onGroupCreated(ssid: String, passphrase: String)
        fun onGroupLost()
        fun onClientsChanged(count: Int)
        fun onError(message: String)
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null
    private var listener: Listener? = null
    private var groupActive = false
    private var createPending = false
    private var started = false
    private var clientPollRunning = false

    private val watchdog = Runnable {
        if (createPending) {
            createPending = false
            listener?.onError("Timed out creating the Wi-Fi Direct group. Turn Wi-Fi on and retry.")
        }
    }

    private val clientPoll = object : Runnable {
        override fun run() {
            refreshClients()
            handler.postDelayed(this, CLIENT_POLL_MS)
        }
    }

    /** Group teardown and radio-state changes are system broadcasts. */
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshClients()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled && groupActive) {
                        groupActive = false
                        listener?.onGroupLost()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (started) return
        this.listener = listener

        val m = manager
        if (m == null) {
            listener.onError("Wi-Fi Direct is unavailable on this device.")
            return
        }
        val ch = m.initialize(context, handler.looper) { /* framework channel dropped */ }
        if (ch == null) {
            listener.onError("Wi-Fi Direct is unavailable on this device.")
            return
        }
        channel = ch

        // P2P system broadcasts are protected and, on Android 13+, MUST be
        // received with RECEIVER_EXPORTED (NOT_EXPORTED receivers get an
        // "Exported Denial" from the system and never see the events).
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        started = true
        // Clear any stale group left by a previous session (e.g. an app
        // force-stop that skipped removeGroup); creating on top of one fails
        // with BUSY on many devices.
        clearStaleGroup { createGroup(busyRetriesRemaining = BUSY_RETRIES) }
    }

    @SuppressLint("MissingPermission")
    private fun clearStaleGroup(then: () -> Unit) {
        val ch = channel ?: return then()
        val m = manager ?: return then()
        m.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handler.postDelayed(then, CLEAR_GROUP_DELAY_MS)
            }

            override fun onFailure(reason: Int) {
                then()
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(busyRetriesRemaining: Int) {
        val ch = channel ?: return
        val m = manager ?: return
        createPending = true
        handler.postDelayed(watchdog, GROUP_CREATE_TIMEOUT_MS)

        m.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                handler.removeCallbacks(watchdog)
                createPending = false
                // The group object can lag the callback by a moment; retry briefly.
                fetchGroupInfo(retriesRemaining = 6)
            }

            override fun onFailure(reason: Int) {
                handler.removeCallbacks(watchdog)
                createPending = false
                if (reason == WifiP2pManager.BUSY && busyRetriesRemaining > 0) {
                    // Transient framework-busy (observed on Samsung right after
                    // initialize / group teardown): back off and try again.
                    val attempt = BUSY_RETRIES - busyRetriesRemaining + 1
                    val delay = minOf(BUSY_RETRY_BASE_MS * attempt, BUSY_RETRY_MAX_MS)
                    handler.postDelayed(
                        { createGroup(busyRetriesRemaining - 1) },
                        delay,
                    )
                } else {
                    listener?.onError(
                        "Could not create the Wi-Fi Direct group (reason $reason). " +
                            "Some devices need Wi-Fi to be on.",
                    )
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun fetchGroupInfo(retriesRemaining: Int) {
        val ch = channel ?: return
        val m = manager ?: return
        m.requestGroupInfo(ch) { group ->
            if (group == null) {
                if (retriesRemaining > 0) {
                    handler.postDelayed({ fetchGroupInfo(retriesRemaining - 1) }, RETRY_DELAY_MS)
                } else {
                    listener?.onError("Timed out creating the Wi-Fi Direct group.")
                }
            } else {
                groupActive = true
                listener?.onGroupCreated(groupName(group), group.passphrase)
                startClientPoll()
                reportClients(group)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshClients() {
        val ch = channel ?: return
        val m = manager ?: return
        m.requestGroupInfo(ch) { group ->
            if (group == null) {
                if (groupActive) {
                    groupActive = false
                    listener?.onGroupLost()
                }
            } else {
                groupActive = true
                reportClients(group)
            }
        }
    }

    private fun reportClients(group: WifiP2pGroup) {
        listener?.onClientsChanged(group.clientList.size)
    }

    private fun startClientPoll() {
        if (clientPollRunning) return
        clientPollRunning = true
        handler.postDelayed(clientPoll, CLIENT_POLL_MS)
    }

    private fun stopClientPoll() {
        clientPollRunning = false
        handler.removeCallbacks(clientPoll)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!started) return
        stopClientPoll()
        handler.removeCallbacks(watchdog)
        runCatching { context.unregisterReceiver(receiver) }
        val ch = channel
        val m = manager
        channel = null
        groupActive = false
        started = false
        if (ch != null && m != null) {
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) {}
            })
        }
        listener = null
    }

    private fun groupName(group: WifiP2pGroup): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            group.networkName
        } else {
            // WifiP2pGroup.getSsid() was removed from the compile-time API surface
            // in newer SDKs, but still exists on Android 7.x/8.0 devices.
            legacySsid(group)
        }

    private fun legacySsid(group: WifiP2pGroup): String = try {
        group.javaClass.getMethod("getSsid").invoke(group) as? String ?: ""
    } catch (_: Exception) {
        ""
    }

    companion object {
        private const val GROUP_CREATE_TIMEOUT_MS = 15_000L
        private const val RETRY_DELAY_MS = 500L
        private const val CLIENT_POLL_MS = 3_000L
        private const val BUSY_RETRIES = 8
        private const val BUSY_RETRY_BASE_MS = 1_000L
        private const val BUSY_RETRY_MAX_MS = 8_000L
        private const val CLEAR_GROUP_DELAY_MS = 1_000L
    }
}
