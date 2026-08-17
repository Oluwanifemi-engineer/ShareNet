package com.sharenet.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareStateTest {

    private fun reduce(state: ShareState, vararg events: ShareEvent): ShareState =
        events.fold(state) { acc, event -> ShareReducer.reduce(acc, event) }

    @Test
    fun `idle plus start request goes to starting`() {
        val next = ShareReducer.reduce(ShareState.Idle, ShareEvent.StartRequested)
        assertEquals(ShareState.Starting(pending = null), next)
    }

    @Test
    fun `start request while already starting is a no-op`() {
        val next = ShareReducer.reduce(
            ShareState.Starting(pending = null),
            ShareEvent.StartRequested,
        )
        assertEquals(ShareState.Starting(pending = null), next)
    }

    @Test
    fun `full happy path builds sharing info`() {
        val state = reduce(
            ShareState.Idle,
            ShareEvent.StartRequested,
            ShareEvent.GroupCreated("DIRECT-zz-Test", "secret123"),
            ShareEvent.UpstreamChanged("HomeWiFi (Wi-Fi)"),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        )
        val expected = ShareState.Sharing(
            ShareInfo(
                ssid = "DIRECT-zz-Test",
                passphrase = "secret123",
                proxyHost = "192.168.49.1",
                proxyPort = 8080,
                upstream = "HomeWiFi (Wi-Fi)",
            ),
        )
        assertEquals(expected, state)
    }

    @Test
    fun `clients changed updates sharing info in place`() {
        val sharing = reduce(
            ShareState.Idle,
            ShareEvent.StartRequested,
            ShareEvent.GroupCreated("DIRECT-x", "pw"),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        ) as ShareState.Sharing

        val updated = ShareReducer.reduce(sharing, ShareEvent.ClientsChanged(3))
        val info = (updated as ShareState.Sharing).info
        assertEquals(3, info.clients)
        assertEquals("DIRECT-x", info.ssid) // other fields untouched
    }

    @Test
    fun `relay started while sharing adds the relay port`() {
        val sharing = reduce(
            ShareState.Idle,
            ShareEvent.StartRequested,
            ShareEvent.GroupCreated("DIRECT-x", "pw"),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        ) as ShareState.Sharing
        assertEquals(null, sharing.info.udpRelayPort)

        val updated = ShareReducer.reduce(sharing, ShareEvent.RelayStarted(5555))
        assertEquals(5555, (updated as ShareState.Sharing).info.udpRelayPort)
        assertEquals("DIRECT-x", updated.info.ssid) // other fields untouched
    }

    @Test
    fun `clients changed while starting is ignored`() {
        val starting = ShareState.Starting(
            PendingInfo("DIRECT-x", "pw", "Wi-Fi"),
        )
        val next = ShareReducer.reduce(starting, ShareEvent.ClientsChanged(2))
        assertEquals(starting, next)
    }

    @Test
    fun `upstream changed while sharing updates in place`() {
        val sharing = reduce(
            ShareState.Idle,
            ShareEvent.StartRequested,
            ShareEvent.GroupCreated("DIRECT-x", "pw"),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        ) as ShareState.Sharing

        val updated = ShareReducer.reduce(
            sharing,
            ShareEvent.UpstreamChanged("Cellular data"),
        )
        assertEquals("Cellular data", (updated as ShareState.Sharing).info.upstream)
    }

    @Test
    fun `stop request during starting or sharing goes to stopping`() {
        assertEquals(
            ShareState.Stopping,
            ShareReducer.reduce(ShareState.Starting(pending = null), ShareEvent.StopRequested),
        )
        assertEquals(
            ShareState.Stopping,
            ShareReducer.reduce(ShareState.Sharing(shareInfo()), ShareEvent.StopRequested),
        )
    }

    @Test
    fun `stopped returns to idle`() {
        assertEquals(ShareState.Idle, ShareReducer.reduce(ShareState.Stopping, ShareEvent.Stopped))
    }

    @Test
    fun `failure is terminal until a new start`() {
        val failed = ShareReducer.reduce(ShareState.Sharing(shareInfo()), ShareEvent.Failed("boom"))
        assertTrue(failed is ShareState.Failed)
        assertEquals("boom", (failed as ShareState.Failed).message)

        // From Failed, StartRequested restarts.
        val restarted = ShareReducer.reduce(failed, ShareEvent.StartRequested)
        assertEquals(ShareState.Starting(pending = null), restarted)
    }

    @Test
    fun `pin generated while starting is carried into sharing info`() {
        val state = reduce(
            ShareState.Idle,
            ShareEvent.StartRequested,
            ShareEvent.GroupCreated("DIRECT-x", "pw"),
            ShareEvent.PinGenerated("4821"),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        )
        assertEquals("4821", (state as ShareState.Sharing).info.pin)
    }

    @Test
    fun `pin generated while sharing updates in place`() {
        val sharing = reduce(
            ShareState.Idle,
            ShareEvent.StartRequested,
            ShareEvent.GroupCreated("DIRECT-x", "pw"),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        ) as ShareState.Sharing
        val updated = ShareReducer.reduce(sharing, ShareEvent.PinGenerated("9012"))
        assertEquals("9012", (updated as ShareState.Sharing).info.pin)
    }

    @Test
    fun `stats updated while sharing updates in place`() {
        val sharing = reduce(
            ShareState.Idle,
            ShareEvent.StartRequested,
            ShareEvent.GroupCreated("DIRECT-x", "pw"),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        ) as ShareState.Sharing
        assertEquals(null, sharing.info.stats)

        val updated = ShareReducer.reduce(
            sharing,
            ShareEvent.StatsUpdated(TrafficStats(bytesUp = 100, bytesDown = 200, activeConnections = 2)),
        )
        val info = (updated as ShareState.Sharing).info
        assertEquals(100L, info.stats?.bytesUp)
        assertEquals(200L, info.stats?.bytesDown)
        assertEquals(2, info.stats?.activeConnections)
        assertEquals("DIRECT-x", info.ssid) // other fields untouched
    }

    @Test
    fun `stats updated while idle is ignored`() {
        val next = ShareReducer.reduce(
            ShareState.Idle,
            ShareEvent.StatsUpdated(TrafficStats(1, 2, 0)),
        )
        assertEquals(ShareState.Idle, next)
    }

    @Test
    fun `proxy started before group is an internal error`() {
        val next = ShareReducer.reduce(
            ShareState.Starting(pending = null),
            ShareEvent.ProxyStarted("192.168.49.1", 8080),
        )
        assertTrue(next is ShareState.Failed)
    }

    private fun shareInfo() = ShareInfo(
        ssid = "DIRECT-x",
        passphrase = "pw",
        proxyHost = "192.168.49.1",
        proxyPort = 8080,
        upstream = "Wi-Fi",
    )
}
