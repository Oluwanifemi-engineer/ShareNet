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
import com.sharenet.app.proxy.CaptivePortalDnsServer
import com.sharenet.app.proxy.HttpProxyServer
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
    private var relay: UdpRelayServer? = null
    private var icmpRelay: IcmpRelayServer? = null
    private var dns: CaptivePortalDnsServer? = null
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
        if (BuildConfig.DEBUG) log("session PIN: $sessionPin")

        if (!Permissions.hasAll(this)) {
            fail(getString(R.string.error_permissions))
            return
        }

        lastUpstream = NetworkInfo.describe(this)
        ShareController.dispatch(
            ShareEvent.UpstreamChanged(lastUpstream ?: getString(R.string.upstream_none)),
        )

        val capabilities = DeviceCapabilityDetector(this).report
        log("device capability: ${capabilities.capability} — ${capabilities.clientCompatibility}")
        ShareController.dispatch(ShareEvent.CapabilityDetected(capabilities.capability))

        // ════════════════════════════════════════════════════════════════════
        // STRATEGY:
        //
        // 1. If hotspot is ALREADY active → use it (NAT, all apps work)
        // 2. If hotspot can start programmatically → use it
        // 3. Otherwise → Wi-Fi Direct P2P (client needs ShareNet app for
        //    WhatsApp etc. — proxy-only for browsers)
        // ════════════════════════════════════════════════════════════════════

        // Step 1: Check if hotspot is already active
        val activeHotspotIp = findActiveHotspotInterface()
        if (activeHotspotIp != null) {
            log("hotspot already active at $activeHotspotIp — configuring services")
            startOnHotspot(activeHotspotIp)
            return
        }

        // Step 2: Try to start hotspot programmatically
        val hotspot = NativeHotspotManager(this)
        nativeHotspot = hotspot
        if (hotspot.isAvailable) {
            log("native hotspot available, trying programmatic start")
            hotspot.start(object : NativeHotspotManager.Listener {
                override fun onHotspotStarted(ssid: String, password: String) {
                    log("native hotspot started: $ssid")
                    handler.postDelayed({
                        val ip = findActiveHotspotInterface()
                        if (ip != null) {
                            startOnHotspot(ip)
                        } else {
                            startOnHotspot("0.0.0.0")
                        }
                    }, 1500)
                }

                override fun onHotspotFailed(reason: String) {
                    log("hotspot failed ($reason) — falling back to Wi-Fi Direct")
                    // Device can't do WiFi+Hotspot simultaneously (e.g. Samsung A03s)
                    // Fall back to Wi-Fi Direct P2P immediately
                    startWifiDirect()
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
     * Find the IP address of an active hotspot interface (ap0, wlan1, etc.).
     * Returns null if no hotspot interface is active.
     */
    private fun findActiveHotspotInterface(): String? {
        val hotspotInterfaceNames = listOf("ap0", "wlan1", "swlan0", "softap0", "p2p0")
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name.lowercase()
                if (hotspotInterfaceNames.any { name.contains(it) }) {
                    if (iface.isUp && !iface.isLoopback) {
                        val addrs = iface.inetAddresses
                        while (addrs.hasMoreElements()) {
                            val addr = addrs.nextElement()
                            if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                                log("hotspot interface found: ${iface.name} -> ${addr.hostAddress}")
                                return addr.hostAddress
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Also check for 192.168.43.x subnet (Android default hotspot subnet)
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.43.")) {
                                log("hotspot subnet found: ${iface.name} -> $ip")
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Start all services on a detected hotspot interface.
     * With native hotspot, NAT is handled by Android — no proxy needed for clients.
     * We still run the proxy for the setup page.
     */
    private fun startOnHotspot(host: String) {
        // Get hotspot SSID and password from system
        val hotspotSsid = getHotspotSsid()
        val hotspotPassword = getHotspotPassword()

        log("starting services on hotspot: $host, SSID=$hotspotSsid")
        ShareController.dispatch(
            ShareEvent.GroupCreated(hotspotSsid, hotspotPassword),
        )
        sessionPin?.let { ShareController.dispatch(ShareEvent.PinGenerated(it)) }

        // Start the proxy (for setup page — clients don't need it for internet)
        val candidate = HttpProxyServer(
            bindHost = host,
            port = PROXY_PORT,
            stats = stats,
            captivePortalEnabled = true,
            hotspotMode = true,
            proxyAuthPin = sessionPin,
        ) { msg -> log("proxy: $msg") }
        try {
            candidate.apkContext = this
            candidate.start()
            proxy = candidate
            ShareController.dispatch(ShareEvent.ProxyStarted(host, candidate.boundPort))
            startRelayWithRetry(attemptsLeft = RELAY_BIND_RETRIES, host = host)
            startTicker()
            refreshNotification()
        } catch (e: ProxyBindException) {
            log("proxy bind failed on $host: ${e.message}")
            // Try 0.0.0.0 as fallback
            if (host != "0.0.0.0") {
                try {
                    val fallback = HttpProxyServer(
                        bindHost = "0.0.0.0",
                        port = PROXY_PORT,
                        stats = stats,
                        captivePortalEnabled = true,
                        hotspotMode = true,
                        proxyAuthPin = sessionPin,
                    ) { msg -> log("proxy: $msg") }
                    fallback.apkContext = this
                    fallback.start()
                    proxy = fallback
                    ShareController.dispatch(ShareEvent.ProxyStarted("0.0.0.0", fallback.boundPort))
                    startRelayWithRetry(attemptsLeft = RELAY_BIND_RETRIES, host = "0.0.0.0")
                    startTicker()
                    refreshNotification()
                } catch (e2: ProxyBindException) {
                    fail(getString(R.string.error_proxy_bind))
                }
            } else {
                fail(getString(R.string.error_proxy_bind))
            }
        }
    }

    /**
     * Open hotspot settings and wait for user to enable it.
     * Polls every 3 seconds for an active hotspot interface.
     */
    private fun openHotspotSettingsAndWait() {
        // Notify user to enable hotspot
        ShareController.dispatch(
            ShareEvent.HotspotInstructions(
                "Enable Mobile Hotspot with 'Wi-Fi Sharing' in Settings\n" +
                "Then tap Start Sharing again",
            ),
        )

        // Open hotspot settings
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

        // Start polling for hotspot activation
        handler.postDelayed(pollHotspotRunnable, POLL_HOTSPOT_INTERVAL_MS)
    }

    private val pollHotspotRunnable = object : Runnable {
        override fun run() {
            if (!sessionActive) return
            val ip = findActiveHotspotInterface()
            if (ip != null) {
                log("hotspot detected after manual enable at $ip")
                startOnHotspot(ip)
            } else {
                // Keep polling
                handler.postDelayed(this, POLL_HOTSPOT_INTERVAL_MS)
            }
        }
    }

    private fun getHotspotSsid(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val method = wifiManager.javaClass.getMethod("getWifiApConfiguration")
            val config = method.invoke(wifiManager) as? android.net.wifi.WifiConfiguration
            if (config != null) {
                return config.SSID?.removeSurrounding("\"") ?: "ShareNet-${Build.MODEL.take(8)}"
            }
        } catch (_: Exception) {}
        return "ShareNet-${Build.MODEL.take(8)}"
    }

    private fun getHotspotPassword(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val method = wifiManager.javaClass.getMethod("getWifiApConfiguration")
            val config = method.invoke(wifiManager) as? android.net.wifi.WifiConfiguration
            if (config != null) {
                return config.preSharedKey ?: ""
            }
        } catch (_: Exception) {}
        return ""
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
            proxyAuthPin = sessionPin,
        ) { msg -> log("proxy: $msg") }
        try {
            candidate.apkContext = this
            candidate.start()
            proxy = candidate
            ShareController.dispatch(ShareEvent.ProxyStarted(host, candidate.boundPort))
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
            proxyAuthPin = sessionPin,
        ) { msg ->
            log("proxy: $msg")
        }
        try {
            candidate.apkContext = this
            candidate.start()
            proxy = candidate
            ShareController.dispatch(ShareEvent.ProxyStarted(host, candidate.boundPort))
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
        val answerIp = java.net.InetAddress.getByName(host)
        // Try binding to 0.0.0.0 first (avoids conflict with Android's built-in
        // DNS on specific IPs), fall back to the specific host address.
        for (bindAddr in listOf("0.0.0.0", host)) {
            try {
                val candidate = CaptivePortalDnsServer(bindAddr, DNS_PORT, answerIp) { msg ->
                    log("dns: $msg")
                }
                candidate.start()
                dns = candidate
                log("captive dns up on $bindAddr:$DNS_PORT -> ${answerIp.hostAddress}")
                return
            } catch (_: ProxyBindException) {
                // try next address
            }
        }
        log("captive dns failed to bind on any address (non-fatal)")
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
        handler.removeCallbacks(pollHotspotRunnable)
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
        handler.removeCallbacks(pollHotspotRunnable)
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
        val setupUrl = "http://${state.info.proxyHost}:${state.info.proxyPort}/setup"
        val text = getString(
            R.string.notif_text_sharing,
            clients,
            state.info.proxyAddress,
        ) + "\nOpen: $setupUrl" + relayLine + pinLine
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
        private const val UDP_RELAY_PORT = 5555
        private const val DNS_PORT = 53
        private const val TICK_MS = 3_000L
        private const val PROXY_BIND_RETRIES = 6
        private const val RELAY_BIND_RETRIES = 6
        private const val PROXY_BIND_RETRY_DELAY_MS = 500L
        private const val POLL_HOTSPOT_INTERVAL_MS = 3000L

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
