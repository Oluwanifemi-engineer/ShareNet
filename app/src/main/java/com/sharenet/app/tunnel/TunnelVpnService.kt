package com.sharenet.app.tunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.sharenet.app.MainActivity
import com.sharenet.app.R
import com.sharenet.app.proxy.Ipv4Codec
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Client-mode tunnel (Tier-2).
 *
 * The phone running this has joined the host's DIRECT-… network. It captures
 * ALL of its traffic into a tun interface and forwards:
 *
 *  - UDP datagrams over the Wi-Fi Direct link to the host's relay
 *    ([com.sharenet.app.proxy.UdpRelayServer]), and
 *  - TCP through [TcpTunnelCore] (a user-space stack that translates
 *    sequence numbers tun2socks-style) over a reliable control connection to
 *    the host's [TcpTunnelServer], which opens a real socket per app
 *    connection.
 *
 * The P2P subnet route (192.168.49.0/24) is more specific than the VPN's
 * default route, so proxy connections to 192.168.49.1:8080 bypass the tunnel
 * entirely and keep working.
 *
 * What still does not work: ICMP (ping) — relaying it needs a raw socket.
 */
class TunnelVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private val udpForwarded = AtomicLong(0)
    private val tcpForwarded = AtomicLong(0)
    private val otherDropped = AtomicLong(0)
    private val repliesReceived = AtomicLong(0)

    // TcpTunnelClient's reader thread calls back when the control socket
    // dies; we must surface that on the main thread or the user would keep
    // seeing "Connected" with a dead TCP path.
    private val mainHandler = Handler(Looper.getMainLooper())

    private var fd: ParcelFileDescriptor? = null
    private var tunnelSocket: DatagramSocket? = null
    private var tcpTunnel: TcpTunnelClient? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private var activeHost: String? = null
    private var tunOut: FileOutputStream? = null
    private val tunWriteLock = Any()

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> {
                // START_STICKY restarts us with a null intent after a kill;
                // fall back to the host the user last entered.
                val host = intent?.getStringExtra(EXTRA_HOST)
                    ?: prefsHost()
                    ?: DEFAULT_HOST
                startTunnel(host)
            }
        }
        // START_STICKY: if the system kills us (not an explicit stop), resume
        // the tunnel with the last host instead of silently dying.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTunnel(host: String) {
        if (running.get()) return

        // Android 16 (API 36) removed the public VPN FGS type; specialUse is
        // the correct type for the client tunnel on all modern versions.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification("ShareNet tunnel", "Connecting to $host…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        TunnelController.update(TunnelController.TunnelState.Starting)

        val builder = Builder()
            .addAddress(TUN_IP, 24)
            .addRoute("0.0.0.0", 0)
            .setMtu(MTU)
            .setSession(getString(R.string.tunnel_session, host))

        val pfd = builder.establish()
        if (pfd == null) {
            // User denied the VPN consent dialog.
            fail(getString(R.string.tunnel_error_denied))
            return
        }

        val socket = try {
            DatagramSocket().also {
                // protect() keeps the tunnel socket out of the VPN we create,
                // avoiding a loop (the P2P subnet route would also bypass it).
                protect(it)
                it.connect(InetSocketAddress(host, RELAY_PORT))
            }
        } catch (e: Exception) {
            runCatching { pfd.close() }
            fail(getString(R.string.tunnel_error_socket))
            return
        }

        fd = pfd
        tunnelSocket = socket
        activeHost = host

        // The TCP tunnel rides its own reliable control connection (bypassing
        // the VPN we just created, like the UDP relay socket).
        tunOut = FileOutputStream(pfd.fileDescriptor)

        val tcp = TcpTunnelClient(
            host = host,
            port = TunnelProtocol.TCP_PORT,
            writeToTun = { packet -> writeToTun(packet) },
            onDisconnected = { reason -> mainHandler.post { onTunnelControlLost(reason) } },
            authPin = prefsPin(),
            log = { msg -> log(msg) },
        )
        if (!tcp.start()) {
            runCatching { pfd.close() }
            runCatching { socket.close() }
            fail(getString(R.string.tunnel_error_socket))
            return
        }
        tcpTunnel = tcp

        running.set(true)
        TunnelController.update(TunnelController.TunnelState.Active(host))
        refreshNotification(getString(R.string.tunnel_notif_active, host))

        readThread = Thread { readLoop() }.apply {
            name = "sharenet-tun-read"
            isDaemon = true
        }.also { it.start() }
        writeThread = Thread { writeLoop() }.apply {
            name = "sharenet-tun-write"
            isDaemon = true
        }.also { it.start() }
    }

    private fun writeToTun(packet: ByteArray) {
        val out = tunOut ?: return
        synchronized(tunWriteLock) {
            try {
                out.write(packet)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    /** tun -> host: UDP via the relay, TCP via the tunnel core. */
    private fun readLoop() {
        val pfd = fd ?: return
        val tunIn = FileInputStream(pfd.fileDescriptor)
        val socket = tunnelSocket ?: return
        val buf = ByteArray(MTU + 64)
        while (running.get()) {
            val n = try {
                tunIn.read(buf)
            } catch (e: Exception) {
                break
            }
            if (n <= 0) break
            when (Ipv4Codec.parse(buf, n)?.protocol) {
                Ipv4Codec.PROTO_UDP -> {
                    try {
                        socket.send(DatagramPacket(buf, n))
                        udpForwarded.incrementAndGet()
                    } catch (_: Exception) {
                        break
                    }
                }
                Ipv4Codec.PROTO_TCP -> {
                    tcpTunnel?.onIpPacket(buf, n)
                    tcpForwarded.incrementAndGet()
                }
                else -> otherDropped.incrementAndGet()
            }
        }
    }

    /** host -> tun: inject wrapped reply packets into the tun interface. */
    private fun writeLoop() {
        val out = tunOut ?: return
        val socket = tunnelSocket ?: return
        val buf = ByteArray(MTU + 64)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (e: SocketException) {
                break
            } catch (_: Exception) {
                if (!running.get()) break
                continue
            }
            if (packet.length <= 0) continue
            synchronized(tunWriteLock) {
                try {
                    out.write(packet.data, 0, packet.length)
                    out.flush()
                    repliesReceived.incrementAndGet()
                } catch (_: Exception) {
                    return@writeLoop
                }
            }
        }
    }

    private fun stopTunnel() {
        if (!running.getAndSet(false)) return
        runCatching { tunnelSocket?.close() }
        tunnelSocket = null
        tcpTunnel?.stop()
        tcpTunnel = null
        runCatching { fd?.close() }
        fd = null
        activeHost = null
        TunnelController.update(TunnelController.TunnelState.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The host's TCP control connection ended (host gone, radio dropped, or a bad PIN). */
    private fun onTunnelControlLost(reason: DisconnectReason) {
        if (!running.get()) return
        // Re-check under the lock: a concurrent stopTunnel() may have already
        // torn everything down.
        synchronized(this) {
            if (running.getAndSet(false)) {
                runCatching { tunnelSocket?.close() }
                tunnelSocket = null
                tcpTunnel?.stop()
                tcpTunnel = null
                runCatching { fd?.close() }
                fd = null
                activeHost = null
            }
        }
        val message = when (reason) {
            DisconnectReason.AUTH_REJECTED -> {
                // The stored PIN is wrong; drop it so the user re-enters it.
                prefs(this).edit { remove(KEY_PIN) }
                getString(R.string.tunnel_error_auth)
            }
            DisconnectReason.CONTROL_LOST -> getString(R.string.tunnel_error_lost)
        }
        TunnelController.update(TunnelController.TunnelState.Failed(message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun prefsHost(): String? =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_HOST, null)

    private fun prefsPin(): String? =
        prefs(this).getString(KEY_PIN, null)?.takeIf { it.isNotBlank() }

    private fun fail(message: String) {
        if (running.getAndSet(false)) {
            runCatching { tunnelSocket?.close() }
            tunnelSocket = null
            tcpTunnel?.stop()
            tcpTunnel = null
            runCatching { fd?.close() }
            fd = null
        }
        TunnelController.update(TunnelController.TunnelState.Failed(message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notifications ───────────────────────────────────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_tunnel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun log(message: String) {
        android.util.Log.d(TAG, message)
    }

    private fun refreshNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(getString(R.string.app_name), text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, TunnelVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "com.sharenet.app.tunnel.START"
        const val ACTION_STOP = "com.sharenet.app.tunnel.STOP"
        const val EXTRA_HOST = "host"

        const val PREFS_NAME = "sharenet"
        const val KEY_HOST = "client_host"
        const val KEY_PIN = "client_pin"

        const val DEFAULT_HOST = "192.168.49.1"
        const val RELAY_PORT = 5555

        private const val TAG = "ShareNetTunnel"

        private const val TUN_IP = "26.0.0.2"
        private const val MTU = 1400
        private const val CHANNEL_ID = "sharenet_tunnel"
        private const val NOTIFICATION_ID = 2

        fun start(context: Context, host: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TunnelVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_HOST, host),
            )
        }

        fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun stop(context: Context) {
            context.startService(Intent(context, TunnelVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
