package com.sharenet.app.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationPolicyTest {

    @Test
    fun `public addresses are allowed`() {
        assertTrue(DestinationRules.isAllowedIpv4("8.8.8.8"))
        assertTrue(DestinationRules.isAllowedIpv4("1.1.1.1"))
        assertTrue(DestinationRules.isAllowedIpv4("93.184.216.34"))
        assertTrue(DestinationRules.isAllowedIpv4("172.32.0.1")) // 172.16/12 ends at .31
        assertTrue(DestinationRules.isAllowedIpv4("192.169.0.1")) // 192.168/16 ends at .168
        assertTrue(DestinationRules.isAllowedIpv4("11.0.0.1")) // 10/8 ends at 10
    }

    @Test
    fun `private lan ranges are refused`() {
        assertFalse(DestinationRules.isAllowedIpv4("10.0.0.1"))
        assertFalse(DestinationRules.isAllowedIpv4("10.255.255.255"))
        assertFalse(DestinationRules.isAllowedIpv4("172.16.0.1"))
        assertFalse(DestinationRules.isAllowedIpv4("172.31.255.255"))
        assertFalse(DestinationRules.isAllowedIpv4("192.168.0.1"))
        assertFalse(DestinationRules.isAllowedIpv4("192.168.1.10"))
        assertFalse(DestinationRules.isAllowedIpv4("192.168.255.254"))
    }

    @Test
    fun `loopback link-local and special ranges are refused`() {
        assertFalse(DestinationRules.isAllowedIpv4("127.0.0.1"))
        assertFalse(DestinationRules.isAllowedIpv4("127.255.255.255"))
        assertFalse(DestinationRules.isAllowedIpv4("169.254.1.1"))
        assertFalse(DestinationRules.isAllowedIpv4("0.0.0.0"))
        assertFalse(DestinationRules.isAllowedIpv4("224.0.0.1")) // multicast
        assertFalse(DestinationRules.isAllowedIpv4("239.255.255.255")) // multicast
        assertFalse(DestinationRules.isAllowedIpv4("255.255.255.255")) // broadcast
        assertFalse(DestinationRules.isAllowedIpv4("240.0.0.1")) // reserved
        assertFalse(DestinationRules.isAllowedIpv4("192.0.2.1")) // TEST-NET-1
        assertFalse(DestinationRules.isAllowedIpv4("198.51.100.1")) // TEST-NET-2
        assertFalse(DestinationRules.isAllowedIpv4("203.0.113.1")) // TEST-NET-3
        assertFalse(DestinationRules.isAllowedIpv4("198.18.0.1")) // benchmarking
        assertFalse(DestinationRules.isAllowedIpv4("192.0.0.1")) // IETF protocol assignments
    }

    @Test
    fun `the p2p subnet is allowed`() {
        assertTrue(DestinationRules.isAllowedIpv4("192.168.49.1"))
        assertTrue(DestinationRules.isAllowedIpv4("192.168.49.5"))
        assertTrue(DestinationRules.isAllowedIpv4("192.168.49.255"))
        assertFalse(DestinationRules.isAllowedIpv4("192.168.48.1")) // adjacent subnet is NOT allowed
        assertFalse(DestinationRules.isAllowedIpv4("192.168.50.1"))
    }

    @Test
    fun `malformed addresses are refused`() {
        assertFalse(DestinationRules.isAllowedIpv4(""))
        assertFalse(DestinationRules.isAllowedIpv4("not-an-ip"))
        assertFalse(DestinationRules.isAllowedIpv4("1.2.3"))
        assertFalse(DestinationRules.isAllowedIpv4("1.2.3.4.5"))
        assertFalse(DestinationRules.isAllowedIpv4("1.2.3.999"))
        assertFalse(DestinationRules.isAllowedIpv4("1.2.3.-1"))
    }

    @Test
    fun `hostnames that resolve privately are refused`() {
        assertFalse(DestinationRules.isAllowed("localhost")) // resolves to 127.0.0.1
    }

    @Test
    fun `ipv6 literal hostnames are handled`() {
        assertFalse(DestinationRules.isAllowed("[::1]")) // loopback, bracket form from the proxy
        assertFalse(DestinationRules.isAllowed("::1"))
    }
}
