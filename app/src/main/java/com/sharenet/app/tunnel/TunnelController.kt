package com.sharenet.app.tunnel

import java.util.concurrent.CopyOnWriteArrayList

/** Client-tunnel state shared between [TunnelVpnService] and the UI. */
object TunnelController {

    sealed interface TunnelState {
        data object Idle : TunnelState
        data object Starting : TunnelState
        data class Active(val host: String) : TunnelState
        data class Failed(val message: String) : TunnelState
    }

    private val listeners = CopyOnWriteArrayList<(TunnelState) -> Unit>()

    @Volatile
    var state: TunnelState = TunnelState.Idle
        private set

    fun update(newState: TunnelState) {
        state = newState
        for (listener in listeners) {
            runCatching { listener(newState) }
        }
    }

    fun observe(listener: (TunnelState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun unobserve(listener: (TunnelState) -> Unit) {
        listeners.remove(listener)
    }
}
