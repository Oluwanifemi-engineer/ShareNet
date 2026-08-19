package com.sharenet.app

/**
 * The sharing lifecycle as a small pure state machine, so the UI and the
 * service stay in sync through one reducer. No Android imports — JVM-testable.
 *
 * Idle ──StartRequested──▶ Starting(pending)
 * Starting ──GroupCreated──▶ Starting(pending+ssid)
 * Starting(pending) ──ProxyStarted──▶ Sharing(info)
 * Sharing ──ClientsChanged/UpstreamChanged──▶ Sharing(updated info)
 * Starting/Sharing ──StopRequested──▶ Stopping ──Stopped──▶ Idle
 * any ──Failed──▶ Failed(message)
 * Failed ──StartRequested──▶ Starting
 */
sealed interface ShareState {
    data object Idle : ShareState
    data class Starting(val pending: PendingInfo?) : ShareState
    data class Sharing(val info: ShareInfo) : ShareState
    data object Stopping : ShareState
    data class Failed(val message: String) : ShareState
}

/** Live byte counters surfaced to the UI (pure data, no Android imports). */
data class TrafficStats(
    val bytesUp: Long,
    val bytesDown: Long,
    val activeConnections: Int,
)

/** What we know about the hotspot before it is fully up. */
data class PendingInfo(
    val ssid: String,
    val passphrase: String,
    val upstream: String,
    val pin: String? = null,
    val capability: DeviceCapabilityDetector.SharingCapability =
        DeviceCapabilityDetector.SharingCapability.P2P_ONLY,
)

data class ShareInfo(
    val ssid: String,
    val passphrase: String,
    val proxyHost: String,
    val proxyPort: Int,
    val upstream: String,
    val clients: Int = 0,
    /** Port of the Tier-2 UDP relay on the same host, or null when not up. */
    val udpRelayPort: Int? = null,
    /** Pairing PIN a ShareNet client must enter to use tunnel mode. */
    val pin: String? = null,
    /** Live traffic counters, refreshed by the service tick. */
    val stats: TrafficStats? = null,
    /** Device capability: what types of clients can connect. */
    val capability: DeviceCapabilityDetector.SharingCapability =
        DeviceCapabilityDetector.SharingCapability.P2P_ONLY,
) {
    val proxyAddress: String get() = "$proxyHost:$proxyPort"

    val clientCompatibilitySummary: String get() = when (capability) {
        DeviceCapabilityDetector.SharingCapability.NATIVE_HOTSPOT ->
            "PC ✓  Phone ✓  Tablet ✓  Smart TV ✓"
        DeviceCapabilityDetector.SharingCapability.P2P_ONLY ->
            "Phone (with app) ✓  Phone (no app) ⚠  PC ✗  Tablet ✗"
        DeviceCapabilityDetector.SharingCapability.NONE ->
            "No devices"
    }
}

sealed interface ShareEvent {
    data object StartRequested : ShareEvent
    data class GroupCreated(val ssid: String, val passphrase: String) : ShareEvent
    data class UpstreamChanged(val upstream: String) : ShareEvent
    data class PinGenerated(val pin: String) : ShareEvent
    data class ProxyStarted(val host: String, val port: Int) : ShareEvent
    data class RelayStarted(val port: Int) : ShareEvent
    data class ClientsChanged(val count: Int) : ShareEvent
    data class StatsUpdated(val stats: TrafficStats) : ShareEvent
    data object StopRequested : ShareEvent
    data object Stopped : ShareEvent
    data class Failed(val message: String) : ShareEvent
    data class CapabilityDetected(
        val capability: DeviceCapabilityDetector.SharingCapability,
    ) : ShareEvent
    /** User needs to enable hotspot in Settings. */
    data class HotspotInstructions(val message: String) : ShareEvent
}

object ShareReducer {

    fun reduce(state: ShareState, event: ShareEvent): ShareState = when (event) {
        ShareEvent.StartRequested -> when (state) {
            is ShareState.Idle, is ShareState.Failed -> ShareState.Starting(pending = null)
            else -> state
        }

        is ShareEvent.GroupCreated -> when (state) {
            is ShareState.Starting -> ShareState.Starting(
                PendingInfo(
                    ssid = event.ssid,
                    passphrase = event.passphrase,
                    upstream = state.pending?.upstream ?: "",
                    capability = state.pending?.capability
                        ?: DeviceCapabilityDetector.SharingCapability.P2P_ONLY,
                ),
            )
            else -> state
        }

        is ShareEvent.UpstreamChanged -> when (state) {
            is ShareState.Starting -> state.copy(
                pending = state.pending?.copy(upstream = event.upstream),
            )
            is ShareState.Sharing -> state.copy(info = state.info.copy(upstream = event.upstream))
            else -> state
        }

        is ShareEvent.PinGenerated -> when (state) {
            is ShareState.Starting -> state.copy(
                pending = state.pending?.copy(pin = event.pin),
            )
            is ShareState.Sharing -> state.copy(info = state.info.copy(pin = event.pin))
            else -> state
        }

        is ShareEvent.ProxyStarted -> when (state) {
            is ShareState.Starting -> {
                val pending = state.pending
                if (pending == null) {
                    ShareState.Failed("Internal error: proxy started before the group")
                } else {
                    ShareState.Sharing(
                        ShareInfo(
                            ssid = pending.ssid,
                            passphrase = pending.passphrase,
                            proxyHost = event.host,
                            proxyPort = event.port,
                            upstream = pending.upstream,
                            pin = pending.pin,
                            capability = pending.capability,
                        ),
                    )
                }
            }
            else -> state
        }

        is ShareEvent.ClientsChanged -> when (state) {
            is ShareState.Sharing -> state.copy(info = state.info.copy(clients = event.count))
            else -> state
        }

        is ShareEvent.StatsUpdated -> when (state) {
            is ShareState.Sharing -> state.copy(info = state.info.copy(stats = event.stats))
            else -> state
        }

        is ShareEvent.RelayStarted -> when (state) {
            is ShareState.Sharing -> state.copy(info = state.info.copy(udpRelayPort = event.port))
            else -> state
        }

        ShareEvent.StopRequested -> when (state) {
            is ShareState.Starting, is ShareState.Sharing -> ShareState.Stopping
            else -> state
        }

        ShareEvent.Stopped -> ShareState.Idle

        is ShareEvent.Failed -> ShareState.Failed(event.message)

        is ShareEvent.CapabilityDetected -> when (state) {
            is ShareState.Starting -> state.copy(
                pending = state.pending?.copy(capability = event.capability),
            )
            else -> state
        }

        is ShareEvent.HotspotInstructions -> when (state) {
            is ShareState.Starting -> state.copy(
                pending = state.pending?.copy(
                    upstream = event.message,
                ),
            )
            else -> state
        }
    }
}
