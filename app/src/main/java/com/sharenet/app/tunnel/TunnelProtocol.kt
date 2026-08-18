package com.sharenet.app.tunnel

/**
 * Framing between the client phone's tunnel core and the host's relay.
 *
 * Both ends speak over a single reliable TCP connection (client -> host on
 * [TCP_PORT]). Each message is:
 *
 *     [connId: 2 bytes][type: 1 byte][len: 2 bytes][payload]
 *
 * where connId identifies one app connection on the client.
 */
object TunnelProtocol {

    const val TCP_PORT = 7777

    // UDP carrier port for ICMP (ping) in tunnel mode: client phones send
    // their captured ICMP echo packets here (host: IcmpRelayServer).
    const val ICMP_RELAY_PORT = 5566

    // Client -> host
    const val TYPE_CONNECT = 1    // payload: dst IPv4 (4) + dst port (2)
    const val TYPE_DATA = 2       // payload: app bytes
    const val TYPE_CLOSE = 3      // app half-closed; host half-closes the real socket
    const val TYPE_RST = 4        // app reset; host aborts the real socket

    // Host -> client
    const val TYPE_CONNECTED = 5  // real connection established
    const val TYPE_REJECTED = 6   // real connection failed (no payload)
    const val TYPE_REMOTE_DATA = 7
    const val TYPE_REMOTE_CLOSE = 8   // server reached EOF
    const val TYPE_REMOTE_RST = 9

    // Heartbeat (both directions, empty payload): lets each side detect a
    // dead peer instead of holding sockets forever. The client PINGs every
    // few seconds; the host answers PONG. A side that hears nothing for a few
    // cycles tears the control connection down.
    const val TYPE_PING = 10  // client -> host
    const val TYPE_PONG = 11  // host -> client

    // Pairing: the client must prove knowledge of the host's on-screen PIN
    // before it may open any real connection (otherwise any device on the
    // hotspot could route traffic through the host without consent).
    const val TYPE_AUTH = 12          // client -> host, payload: PIN as ASCII
    const val TYPE_AUTH_REJECTED = 13 // host -> client, empty payload, then close

    const val HEADER_LEN = 5
    const val MAX_PAYLOAD = 0xFFFF
}
