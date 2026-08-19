package com.sharenet.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.core.net.toUri
import java.util.Locale
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sharenet.app.databinding.ActivityMainBinding
import com.sharenet.app.tunnel.TunnelController
import com.sharenet.app.tunnel.TunnelVpnService
import com.sharenet.app.ui.StatusRadarView
import com.sharenet.app.util.NetworkInfo
import com.sharenet.app.util.Permissions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val stateListener: (ShareState) -> Unit = { state -> render(state) }
    private val tunnelListener: (TunnelController.TunnelState) -> Unit = { state -> renderTunnel(state) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener { onToggle() }
        binding.clientToggleButton.setOnClickListener { onClientToggle() }
        binding.osSettingsButton.setOnClickListener { openHotspotSettings() }
        binding.privacyButton.setOnClickListener { showPrivacyDialog() }
        binding.aboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        binding.copySsidButton.setOnClickListener {
            copyToClipboard(binding.ssidValue.text.toString())
        }
        binding.copyPassphraseButton.setOnClickListener {
            copyToClipboard(binding.passphraseValue.text.toString())
        }
        binding.copyProxyButton.setOnClickListener {
            copyToClipboard(binding.proxyValue.text.toString())
        }
        binding.copySetupUrlButton.setOnClickListener {
            copyToClipboard(binding.setupUrlValue.text.toString())
        }
        binding.copyPinButton.setOnClickListener {
            copyToClipboard(binding.pinValue.text.toString())
        }

        binding.clientHostInput.setText(
            TunnelVpnService.prefs(this).getString(TunnelVpnService.KEY_HOST, TunnelVpnService.DEFAULT_HOST),
        )
        binding.clientPinInput.setText(
            TunnelVpnService.prefs(this).getString(TunnelVpnService.KEY_PIN, null),
        )

        ShareController.observe(stateListener)
        TunnelController.observe(tunnelListener)

        // Auto-discover sharing phone when on the Wi-Fi Direct network
        autoDiscoverShareHost()

        // Test hook for the adb scripts (scripts/device-test.sh): launching
        // with this action starts sharing without any UI taps, which is the
        // only automation that is reliable on real devices.
        if (intent?.action == ACTION_AUTO_START) {
            startSharingIfPossible()
        }
    }

    /**
     * Try to discover the sharing phone by hitting the discovery JSON endpoint.
     * If found, auto-fill the host IP in the client input field.
     */
    private fun autoDiscoverShareHost() {
        Thread {
            try {
                val url = java.net.URL("http://192.168.49.1:8080/sharenet.json")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 2000
                conn.readTimeout = 2000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    // Parse simple JSON: {"host":"...","port":...}
                    // Simple JSON parse: find "host":"value"
                    val hostStart = body.indexOf("\"host\":\"")
                    if (hostStart >= 0) {
                        val valueStart = hostStart + 9 // length of "host":"
                        val valueEnd = body.indexOf("\"", valueStart)
                        if (valueEnd > valueStart) {
                            val discoveredHost = body.substring(valueStart, valueEnd)
                            runOnUiThread {
                                binding.clientHostInput.setText(discoveredHost)
                                Toast.makeText(this@MainActivity, "ShareNet found: $discoveredHost", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Not on the ShareNet network or host not available
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_AUTO_START) {
            startSharingIfPossible()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect the current upstream even while idle.
        val state = ShareController.state
        if (state is ShareState.Idle || state is ShareState.Failed) {
            binding.upstreamText.text = describeUpstream(null)
        }
    }

    override fun onDestroy() {
        ShareController.unobserve(stateListener)
        TunnelController.unobserve(tunnelListener)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != Permissions.REQUEST_CODE) return
        if (Permissions.hasAll(this)) {
            ShareService.start(this)
        } else {
            Toast.makeText(this, R.string.error_permissions, Toast.LENGTH_LONG).show()
        }
    }

    // ── Host mode ───────────────────────────────────────────────────────────

    private fun onToggle() {
        when (ShareController.state) {
            is ShareState.Idle, is ShareState.Failed -> startSharingIfPossible()
            else -> ShareService.stop(this)
        }
    }

    private fun startSharingIfPossible() {
        if (Permissions.hasAll(this)) {
            ShareService.start(this)
        } else {
            Permissions.request(this)
        }
    }

    // ── Client (tunnel) mode ────────────────────────────────────────────────

    private fun onClientToggle() {
        when (TunnelController.state) {
            is TunnelController.TunnelState.Idle,
            is TunnelController.TunnelState.Failed,
            -> {
                val host = binding.clientHostInput.text.toString()
                    .trim()
                    .ifEmpty { TunnelVpnService.DEFAULT_HOST }
                val pin = binding.clientPinInput.text.toString().trim()
                val prefs = TunnelVpnService.prefs(this).edit()
                    .putString(TunnelVpnService.KEY_HOST, host)
                if (pin.isEmpty()) {
                    prefs.remove(TunnelVpnService.KEY_PIN)
                } else {
                    prefs.putString(TunnelVpnService.KEY_PIN, pin)
                }
                prefs.apply()
                TunnelVpnService.start(this, host)
            }
            else -> TunnelVpnService.stop(this)
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render(state: ShareState) {
        val hero = binding.heroStatusText
        val upstream = binding.upstreamText
        val clients = binding.clientsText
        val statsText = binding.statsText
        val details = binding.shareDetailsCard
        val toggle = binding.toggleButton
        val radar = binding.statusRadar

        when (state) {
            is ShareState.Idle -> {
                radar.mode = StatusRadarView.Mode.IDLE
                hero.setText(R.string.status_idle)
                hero.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface))
                upstream.text = describeUpstream(null)
                clients.text = ""
                statsText.visibility = View.GONE
                details.visibility = View.GONE
                toggle.setText(R.string.action_start)
                toggle.isEnabled = true
            }

            is ShareState.Starting -> {
                radar.mode = StatusRadarView.Mode.STARTING
                hero.setText(R.string.status_starting)
                hero.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
                upstream.text = state.pending?.upstream?.let(::describeUpstream)
                    ?: describeUpstream(null)
                clients.text = ""
                statsText.visibility = View.GONE
                details.visibility = View.GONE
                toggle.setText(R.string.action_stop)
                toggle.isEnabled = true // allow cancelling while starting
            }

            is ShareState.Sharing -> {
                val info = state.info
                radar.mode = StatusRadarView.Mode.ACTIVE
                hero.setText(R.string.status_sharing)
                hero.setTextColor(ContextCompat.getColor(this, R.color.live))
                upstream.text = describeUpstream(info.upstream)
                clients.text = describeClients(info.clients)
                details.visibility = View.VISIBLE
                binding.ssidValue.text = info.ssid
                binding.passphraseValue.text = info.passphrase
                binding.proxyValue.text = info.proxyAddress
                val setupUrl = "http://${info.proxyHost}:${info.proxyPort}/setup"
                binding.setupUrlValue.text = setupUrl
                binding.udpRelayValue.text = info.udpRelayPort?.let {
                    getString(R.string.share_udp_relay_value, info.proxyHost, it)
                } ?: getString(R.string.share_udp_relay_none)
                binding.pinValue.text = info.pin ?: ""
                val stats = info.stats
                if (stats != null) {
                    statsText.text = getString(
                        R.string.stats_value,
                        formatBytes(stats.bytesUp),
                        formatBytes(stats.bytesDown),
                        stats.activeConnections,
                    )
                    statsText.visibility = View.VISIBLE
                }
                updateQr(info)
                toggle.setText(R.string.action_stop)
                toggle.isEnabled = true
            }

            is ShareState.Stopping -> {
                radar.mode = StatusRadarView.Mode.STARTING
                hero.setText(R.string.status_stopping)
                hero.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
                clients.text = ""
                statsText.visibility = View.GONE
                details.visibility = View.GONE
                toggle.isEnabled = false
            }

            is ShareState.Failed -> {
                radar.mode = StatusRadarView.Mode.ERROR
                hero.text = state.message
                hero.setTextColor(ContextCompat.getColor(this, R.color.danger))
                upstream.text = describeUpstream(null)
                clients.text = ""
                statsText.visibility = View.GONE
                details.visibility = View.GONE
                toggle.setText(R.string.action_start)
                toggle.isEnabled = true
            }
        }
    }

    private fun renderTunnel(state: TunnelController.TunnelState) {
        val status = binding.clientStatus
        val button = binding.clientToggleButton
        when (state) {
            is TunnelController.TunnelState.Idle -> {
                status.setText(R.string.client_status_idle)
                status.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
                button.setText(R.string.client_connect)
                button.isEnabled = true
            }
            is TunnelController.TunnelState.Starting -> {
                status.setText(R.string.client_status_starting)
                status.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
                button.setText(R.string.client_disconnect)
                button.isEnabled = true
            }
            is TunnelController.TunnelState.Active -> {
                status.text = getString(R.string.client_status_active, state.host)
                status.setTextColor(ContextCompat.getColor(this, R.color.live))
                button.setText(R.string.client_disconnect)
                button.isEnabled = true
            }
            is TunnelController.TunnelState.Failed -> {
                status.text = getString(R.string.client_status_failed, state.message)
                status.setTextColor(ContextCompat.getColor(this, R.color.danger))
                button.setText(R.string.client_connect)
                button.isEnabled = true
            }
        }
    }

    private fun describeUpstream(upstream: String?): String {
        if (upstream.isNullOrBlank()) {
            return NetworkInfo.describe(this) ?: getString(R.string.upstream_unknown)
        }
        return when {
            upstream.startsWith("Wi-Fi", ignoreCase = true) ->
                getString(R.string.upstream_wifi, upstream)
            upstream.startsWith("Cellular", ignoreCase = true) ->
                getString(R.string.upstream_cellular)
            else -> upstream
        }
    }

    private fun describeClients(count: Int): String = when (count) {
        0 -> getString(R.string.clients_zero)
        1 -> getString(R.string.clients_one)
        else -> getString(R.string.clients_many, count)
    }

    /** QR of the join details, regenerated only when the session changes. */
    private var lastQrSsid: String? = null

    private fun updateQr(info: ShareInfo) {
        if (info.ssid == lastQrSsid && binding.qrCode.drawable != null) return
        lastQrSsid = info.ssid
        val setupUrl = "http://${info.proxyHost}:${info.proxyPort}/setup"
        val payload = buildString {
            appendLine("ShareNet")
            appendLine("Network: ${info.ssid}")
            appendLine("Password: ${info.passphrase}")
            appendLine("Proxy: ${info.proxyAddress}")
            appendLine("Setup: $setupUrl")
            info.udpRelayPort?.let { appendLine("Games/calls: ${info.proxyHost}:$it") }
            info.pin?.let { appendLine("PIN: $it") }
        }
        binding.qrCode.setImageBitmap(renderQr(payload))
    }

    private fun renderQr(payload: String): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val size = 384
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.set(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

    private fun showPrivacyDialog() {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_body)
            .setPositiveButton(R.string.privacy_ok, null)
        // When the build embeds a hosted policy URL (-PprivacyPolicyUrl=…),
        // offer to open it in the browser as well.
        if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
            builder.setNeutralButton(R.string.privacy_online) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.PRIVACY_POLICY_URL.toUri()))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.error_generic, Toast.LENGTH_LONG).show()
                }
            }
        }
        builder.show()
    }

    private fun copyToClipboard(value: String) {
        if (value.isBlank()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("ShareNet", value))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun openHotspotSettings() {
        // No public constant for the tethering settings action, so use the
        // well-known system action, falling back to wireless settings.
        val intents = listOf(
            Intent("android.settings.TETHERING_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
        Toast.makeText(this, R.string.error_generic, Toast.LENGTH_LONG).show()
    }

    companion object {
        /** Test hook action for the adb scripts: start sharing without taps. */
        const val ACTION_AUTO_START = "com.sharenet.app.action.AUTO_START"
    }
}
