package com.sharenet.app.proxy

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe byte counters for the proxy, exposed to the UI.
 * Pure JVM — no Android dependencies, so it runs in plain unit tests.
 */
class ProxyStats {
    val connectionsAccepted = AtomicLong(0)
    val connectionsRejected = AtomicLong(0)
    val authRejections = AtomicLong(0)
    val bytesFromClients = AtomicLong(0) // client -> internet
    val bytesToClients = AtomicLong(0)   // internet -> client

    val activeConnections = AtomicInteger(0)

    fun snapshot(): StatsSnapshot = StatsSnapshot(
        connectionsAccepted = connectionsAccepted.get(),
        connectionsRejected = connectionsRejected.get(),
        bytesFromClients = bytesFromClients.get(),
        bytesToClients = bytesToClients.get(),
        activeConnections = activeConnections.get(),
        authRejections = authRejections.get(),
    )
}

data class StatsSnapshot(
    val connectionsAccepted: Long,
    val connectionsRejected: Long,
    val bytesFromClients: Long,
    val bytesToClients: Long,
    val activeConnections: Int,
    val authRejections: Long = 0,
)
