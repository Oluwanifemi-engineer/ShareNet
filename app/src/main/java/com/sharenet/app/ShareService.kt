package com.sharenet.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.security.SecureRandom
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sharenet.app.proxy.DnsForwarder
import com.sharenet.app.proxy.HttpProxyServer
import com.sharenet.app.proxy.Socks5ProxyServer
import com.sharenet.app.proxy.IcmpRelayServer
import com.sharenet.app.proxy.OsPingSocket
import com.sharenet.app.proxy.P2pAddressResolver
import com.sharenet.app.proxy.ProxyBindException
import com.sharenet.app.proxy.ProxyStats
import com.sharenet.app.proxy.UdpRelayServer
import com.sharenet.app.tunnel.TcpTunnelServer
import com.sharenet.app.tunnel.TunnelProtocol
import com.sharenet.app.util.NetworkInfo
import com.sharenet.app.util.Permissions

/**
 * The foreground service that runs the share session.
 *
 * Flow on START:
 *   1. startForeground (contract for startForegroundService) with a
 *      "starting" notification.
 *   2. Verify permissions; describe the upstream (Wi-Fi SSID or cellular).
 *   3. Become a Wi-Fi Direct Group Owner ([WifiDirectManager]).
 *   4. Once the group exists, resolve the GO IP and start [HttpProxyServer]
 *      on it (retrying briefly — the P2P interface can lag the group callback).
 *   5. Emit [ShareEvent.ProxyStarted] -> UI shows SSID/passphrase/proxy.
 *   6. Poll every few seconds for connected clients and upstream changes,
 *      updating the notification live.
 *
 * Every state change goes through [ShareController] on the main thread.
 */
class ShareService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val stats = ProxyStats()
    private var wifiDirect: WifiDirectManager? = null
    private var proxy: HttpProxyServer? = null
    private var socks5: Socks5ProxyServer? = null
    private var relay: UdpRelayServer? = null
    private var icmpRelay: IcmpRelayServer? = null
    private var dns: DnsForwarder? = null
    private var tcpRelay: TcpTunnelServer? = null
    private var sessionActive = false
    private var lastUpstream: String? = null
    private var sessionPin: String? = null
    private var nativeHotspot: NativeHotspotManager? = null

    // Bind-retry runnables are tracked so a stop/fail during the retry window
    // cancels them — otherwise a retry could fire after teardown, bind a
    // socket the service no longer owns, and break the NEXT session.
    private val pendingRetries = mutableSetOf<Runnable>()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val wifiListener = object : WifiDirectManager.Listener {
        override fun onGroupCreated(ssid: String, passphrase: String) {
            ShareController.dispatch(ShareEvent.GroupCreated(ssid, passphrase))
            sessionPin?.let { ShareController.dispatch(ShareEvent.PinGenerated(it)) }
            startProxyWithRetry(attemptsLeft = PROXY_BIND_RETRIES)
        }

        override fun onGroupLost() {
            fail(getString(R.string.error_group_lost))
        }

        override fun onClientsChanged(count: Int) {
            ShareController.dispatch(ShareEvent.ClientsChanged(count))
            refreshNotification()
        }

        override fun onError(message: String) {
            fail(message)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSharing()
            else -> startSharing()
        }
        // START_STICKY: if the system kills us (memory pressure) while an
        // explicit stopSelf() was never called, restart the share session
        // instead of silently dropping the hotspot.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Start ───────────────────────────────────────────────────────────────

    private fun startSharing() {
        if (sessionActive) return
        sessionActive = true

        // Contract for startForegroundService: startForeground promptly.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notif_title_starting), null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        ShareController.dispatch(ShareEvent.StartRequested)

        // Pairing PIN for client (tunnel) mode: generated now, dispatched once
        // the group exists (the reducer only carries it into the pending info).
        sessionPin = randomPin()
        // Debug builds only: the adb device test reads the PIN from logcat
        // (reading it from the UI is unreliable — animations keep uiautomator
        // from ever seeing an idle screen). Release builds never log it.
        if (BuildConfig.DEBUG) log("session PIN: $sessionPin")

        if (!Permissions.hasAll(this)) {
            fail(getString(R.string.error_permissions))
            return
        }

        lastUpstream = NetworkInfo.describe(this)
        ShareController.dispatch(
            ShareEvent.UpstreamChanged(lastUpstream ?: getString(R.string.upstream_none)),
        )

        // Detect device capabilities to communicate what clients can connect
        val capabilities = DeviceCapabilityDetector(this).report
        log("device capability: ${capabilities.capability} — ${capabilities.clientCompatibility}")
        ShareController.dispatch(ShareEvent.CapabilityDetected(capabilities.capability))

        // Try native hotspot first — works with ALL devices (PC, phone, tablet)
        // Strategy: try native hotspot first (NAT = all apps work automatically).
        // If programmatic start fails, guide user to enable it manually.
        // Fall back to Wi-Fi Direct (proxy-only, limited app support).
        val hotspot = NativeHotspotManager(this)
        nativeHotspot = hotspot
        if (hotspot.isAvailable) {
            log("native hotspot available, trying first")
            hotspot.start(object : NativeHotspotManager.Listener {
                override fun onHotspotStarted(ssid: String, password: String) {
                    log("native hotspot started: $ssid")
                    ShareController.dispatch(
                        ShareEvent.GroupCreated(ssid, password),
                    )
                    sessionPin?.let { ShareController.dispatch(ShareEvent.PinGenerated(it)) }
                    startRelayWithRetry(attemptsLeft = RELAY_BIND_RETRIES, host = "0.0.0.0")
                }

                override fun onHotspotFailed(reason: String) {
                    log("native hotspot programmatic start failed")
                    log("Trying to detect if hotspot is already active...")
                    // Check if the user manually enabled the hotspot
                    detectActiveHotspot()
                }

                override fun onHotspotStopped() {
                    fail("Hotspot stopped")
                }
            })
        } else {
            log("native hotspot not available, using Wi-Fi Direct")
            startWifiDirect()
        }
    }

    /**
     * Check if the user has manually enabled the hotspot.
     * If active, configure services on the hotspot interface.
     * If not, open settings and guide the user.
     */
    private fun detectActiveHotspot() {
        // Check for common hotspot interfaces
        val hotspotInterfaces = listOf("ap0", "wlan1", "swlan0", "softap0")
        var hotspotIp: String? = null

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()
                if (hotspotInterfaces.any { name.contains(it) }) {
                    // Found a hotspot interface
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            hotspotIp = addr.hostAddress
                            log("hotspot interface found: $name -> $hotspotIp")
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (hotspotIp != null) {
            // Hotspot is active — configure services on it
            log("hotspot active at $hotspotIp, starting services")
            ShareController.dispatch(
                ShareEvent.GroupCreated("Hotspot", "Check phone settings"),
            )
            sessionPin?.let { ShareController.dispatch(ShareEvent.PinGenerated(it)) }
            startProxyOnHost(hotspotIp)
        } else {
            // Hotspot not active — guide user to enable it
            log("no active hotspot found, opening settings")
            try {
                val intent = Intent("android.settings.TETHER_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
            ShareController.dispatch(
                ShareEvent.GroupCreated(
                    "Enable Hotspot",
                    "Open Settings > Hotspot > Enable with Wi-Fi Sharing",
                ),
            )
            // Still start proxy on P2P as fallback
            startWifiDirect()
        }
    }

    private fun startProxyOnHost(host: String) {
        val candidate = HttpProxyServer(
            bindHost = host,
            port = PROXY_PORT,
            stats = stats,
            captivePortalEnabled = true,
        ) { msg -> log("proxy: $msg") }
        try {
            candidate.start()
            proxy = candidate
            ShareController.dispatch(ShareEvent.ProxyStarted(host, candidate.boundPort))
            startSocks5(host)
            startTicker()
            refreshNotification()
        } catch (e: ProxyBindException) {
            log("proxy bind failed on $host: ${e.message}")
        }
    }

    /** Bind can race the P2P interface coming up; retry a few times. */
    private fun startProxyWithRetry(attemptsLeft: Int) {
        val host = P2pAddressResolver.resolveGroupOwnerAddress()
        val candidate = HttpProxyServer(
            bindHost = host,
            port = PROXY_PORT,
            stats = stats,
            captivePortalEnabled = true,
        ) { msg ->
            log("proxy: $msg")
        }
        try {
            candidate.start()
            proxy = candidate
            ShareController.dispatch(ShareEvent.ProxyStarted(host, candidate.boundPort))
            startSocks5(host)
            startRelayWithRetry(attemptsLeft = RELAY_BIND_RETRIES, host = host)
            startTicker()
            refreshNotification()
        } catch (e: ProxyBindException) {
            if (attemptsLeft <= 0) {
                fail(getString(R.string.error_proxy_bind))
            } else {
                scheduleRetry(PROXY_BIND_RETRY_DELAY_MS) {
                    startProxyWithRetry(attemptsLeft - 1)
                }
            }
        }
    }

    /** SOCKS5 proxy for all TCP/UDP traffic (chat apps, games, etc). */
    private fun startSocks5(host: String) {
        try {
            val candidate = Socks5ProxyServer(
                bindHost = host,
                port = SOCKS5_PORT,
                stats = stats,
            ) { msg -> log("socks5: $msg") }
            candidate.start()
            socks5 = candidate
            log("socks5 proxy up on $host:${candidate.boundPort}")
        } catch (e: ProxyBindException) {
            log("socks5 proxy failed to bind (non-fatal): ${e.message}")
        }
    }

    /** UDP relay for client-mode phones; bound like the proxy to the P2P IP. */
    private fun startRelayWithRetry(attemptsLeft: Int, host: String) {
        val candidate = UdpRelayServer(host, UDP_RELAY_PORT) { msg -> log("relay: $msg") }
        try {
            candidate.start()
            relay = candidate
            ShareController.dispatch(ShareEvent.RelayStarted(candidate.boundPort))
            startIcmpRelay(host)
            startDns(host)
            startTcpRelay(host)
            refreshNotification()
        } catch (e: ProxyBindException) {
            if (attemptsLeft <= 0) {
                log("udp relay failed to bind: ${e.message}")
            } else {
                scheduleRetry(PROXY_BIND_RETRY_DELAY_MS) {
                    startRelayWithRetry(attemptsLeft - 1, host)
                }
            }
        }
    }

    /**
     * Runs [block] on the main handler later, but only while the session is
     * still active, and tracks it so teardown can cancel it.
     */
    private fun scheduleRetry(delayMs: Long, block: () -> Unit) {
        lateinit var runnable: Runnable
        runnable = Runnable {
            pendingRetries.remove(runnable)
            if (sessionActive) block()
        }
        pendingRetries.add(runnable)
        handler.postDelayed(runnable, delayMs)
    }

    /**
     * DNS for the P2P network, bound to the group owner address (port 53).
     * Best-effort: if it cannot bind we still share — clients can use their
     * own resolvers.
     */
    private fun startDns(host: String) {
        try {
            val candidate = DnsForwarder.forHosts(host, DNS_PORT, NetworkInfo.dnsServers(this)) { msg ->
                log("dns: $msg")
            }
            candidate.start()
            dns = candidate
            log("dns forwarder up on $host:$DNS_PORT")
        } catch (e: ProxyBindException) {
            log("dns forwarder failed to bind (non-fatal): ${e.message}")
        }
    }

    /**
     * ICMP (ping) relay for client phones in tunnel mode — rootless via
     * kernel ping sockets ([OsPingSocket]). Best-effort: if the kernel
     * refuses ping sockets the relay still binds and just drops, which is
     * the pre-ICMP behavior.
     */
    private fun startIcmpRelay(host: String) {
        try {
            val candidate = IcmpRelayServer(
                host,
                TunnelProtocol.ICMP_RELAY_PORT,
                pingSocketFactory = { id -> OsPingSocket.create(id) },
            ) { msg -> log("icmp-relay: $msg") }
            candidate.start()
            icmpRelay = candidate
            log("icmp relay up on $host:${candidate.boundPort}")
        } catch (e: ProxyBindException) {
            log("icmp relay failed to bind (non-fatal): ${e.message}")
        }
    }

    /**
     * The Tier-2 TCP tunnel relay (for client phones in tunnel mode). Like
     * the proxy, it binds to the P2P address; best-effort.
     */
    private fun startTcpRelay(host: String) {
        try {
            val candidate = TcpTunnelServer(
                host,
                TunnelProtocol.TCP_PORT,
                authPin = sessionPin,
            ) { msg ->
                log("tcp-relay: $msg")
            }
            candidate.start()
            tcpRelay = candidate
            log("tcp relay up on $host:${candidate.boundPort}")
        } catch (e: ProxyBindException) {
            log("tcp relay failed to bind (non-fatal): ${e.message}")
        }
    }

    // ── Periodic tick: clients + upstream ───────────────────────────────────

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    private fun tick() {
        wifiDirect?.refreshClients()

        // Live traffic counters for the UI (bytes are cumulative since start).
        val snapshot = stats.snapshot()
        ShareController.dispatch(
            ShareEvent.StatsUpdated(
                TrafficStats(
                    bytesUp = snapshot.bytesFromClients,
                    bytesDown = snapshot.bytesToClients,
                    activeConnections = snapshot.activeConnections,
                ),
            ),
        )

        val now = NetworkInfo.describe(this)
        val display = now ?: getString(R.string.upstream_none)
        if (display != lastUpstream) {
            lastUpstream = display
            ShareController.dispatch(ShareEvent.UpstreamChanged(display))
            refreshNotification()
        }
    }

    // ── Stop / fail ─────────────────────────────────────────────────────────

    private fun startWifiDirect() {
        val manager = WifiDirectManager(this)
        wifiDirect = manager
        manager.start(wifiListener)
    }

    private fun stopSharing() {
        if (!sessionActive) return
        sessionActive = false
        sessionPin = null
        ShareController.dispatch(ShareEvent.StopRequested)
        nativeHotspot?.stop()
        nativeHotspot = null
        teardown()
        ShareController.dispatch(ShareEvent.Stopped)
        stopForegroundCompat()
        stopSelf()
    }

    private fun fail(message: String) {
        if (!sessionActive) return
        sessionActive = false
        sessionPin = null
        ShareController.dispatch(ShareEvent.Failed(message))
        teardown()
        stopForegroundCompat()
        stopSelf()
    }

    private fun teardown() {
        stopTicker()
        // Cancel pending bind retries: they must never fire after teardown.
        for (r in pendingRetries) handler.removeCallbacks(r)
        pendingRetries.clear()
        proxy?.stop()
        proxy = null
        socks5?.stop()
        socks5 = null
        relay?.stop()
        relay = null
        icmpRelay?.stop()
        icmpRelay = null
        dns?.stop()
        dns = null
        tcpRelay?.stop()
        tcpRelay = null
        nativeHotspot?.stop()
        nativeHotspot = null
        wifiDirect?.stop()
        wifiDirect = null
    }

    // ── Notifications ───────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_share_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun refreshNotification() {
        val state = ShareController.state
        if (state !is ShareState.Sharing) return
        val relayLine = state.info.udpRelayPort?.let {
            getString(R.string.notif_relay_line, state.info.proxyHost, it)
        } ?: ""
        val pinLine = state.info.pin?.let {
            getString(R.string.notif_pin_line, it)
        } ?: ""
        val clients = resources.getQuantityString(
            R.plurals.notif_clients,
            state.info.clients,
            state.info.clients,
        )
        val text = getString(
            R.string.notif_text_sharing,
            clients,
            state.info.proxyAddress,
        ) + relayLine + pinLine
        val notification = buildNotification(
            getString(R.string.notif_title_sharing, state.info.ssid),
            text,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(title)
            .setContentText(text ?: getString(R.string.status_starting))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
        return builder.build()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun randomPin(): String =
        (SecureRandom().nextInt(10_000)).toString().padStart(4, '0')

    private fun log(message: String) {
        android.util.Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "ShareNet"

        const val ACTION_START = "com.sharenet.app.action.START"
        const val ACTION_STOP = "com.sharenet.app.action.STOP"

        private const val CHANNEL_ID = "sharenet_status"
        private const val NOTIFICATION_ID = 1
        private const val PROXY_PORT = 8080
        private const val SOCKS5_PORT = 1080
        private const val UDP_RELAY_PORT = 5555
        private const val DNS_PORT = 53
        private const val TICK_MS = 3_000L
        private const val PROXY_BIND_RETRIES = 6
        private const val RELAY_BIND_RETRIES = 6
        private const val PROXY_BIND_RETRY_DELAY_MS = 500L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ShareService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
        }
    }
}
