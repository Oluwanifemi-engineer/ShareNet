package com.sharenet.app

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny process-wide bridge between [ShareService] and [MainActivity].
 *
 * The service dispatches [ShareEvent]s (always on the main thread); the
 * activity observes the resulting [ShareState]. Keeping the state in one place
 * means the notification, the service, and the UI can never disagree.
 */
object ShareController {

    private val listeners = CopyOnWriteArrayList<(ShareState) -> Unit>()

    @Volatile
    var state: ShareState = ShareState.Idle
        private set

    /** Apply one event through the reducer (call on the main thread). */
    fun dispatch(event: ShareEvent) {
        update(ShareReducer.reduce(state, event))
    }

    /** Replace the state outright (used by the service for direct transitions). */
    fun update(newState: ShareState) {
        state = newState
        for (listener in listeners) {
            runCatching { listener(newState) }
        }
    }

    fun observe(listener: (ShareState) -> Unit) {
        listeners.add(listener)
        listener(state) // immediate replay so late observers never miss the current state
    }

    fun unobserve(listener: (ShareState) -> Unit) {
        listeners.remove(listener)
    }
}
