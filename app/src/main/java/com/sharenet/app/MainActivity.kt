package com.sharenet.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

        binding.copySsidButton.setOnClickListener {
            copyToClipboard(binding.ssidValue.text.toString())
        }
        binding.copyPassphraseButton.setOnClickListener {
            copyToClipboard(binding.passphraseValue.text.toString())
        }
        binding.copyProxyButton.setOnClickListener {
            copyToClipboard(binding.proxyValue.text.toString())
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
            is ShareState.Idle, is ShareState.Failed -> {
                if (Permissions.hasAll(this)) {
                    ShareService.start(this)
                } else {
                    Permissions.request(this)
                }
            }
            else -> ShareService.stop(this)
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
                binding.udpRelayValue.text = info.udpRelayPort?.let {
                    getString(R.string.share_udp_relay_value, info.proxyHost, it)
                } ?: getString(R.string.share_udp_relay_none)
                binding.pinValue.text = info.pin ?: ""
                toggle.setText(R.string.action_stop)
                toggle.isEnabled = true
            }

            is ShareState.Stopping -> {
                radar.mode = StatusRadarView.Mode.STARTING
                hero.setText(R.string.status_stopping)
                hero.setTextColor(ContextCompat.getColor(this, R.color.md_on_surface_variant))
                clients.text = ""
                details.visibility = View.GONE
                toggle.isEnabled = false
            }

            is ShareState.Failed -> {
                radar.mode = StatusRadarView.Mode.ERROR
                hero.text = state.message
                hero.setTextColor(ContextCompat.getColor(this, R.color.danger))
                upstream.text = describeUpstream(null)
                clients.text = ""
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
    }
}
