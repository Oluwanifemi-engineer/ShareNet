# ShareNet — Real-world validation

The product is not distributed until the items below are proven on hardware.
This file tracks what has been verified where, and what still needs a second
phone (or a specific environment) to confirm. An item "ships" only when it has
passed at its hardest layer.

## Proof layers (strongest counts)

| Layer | What it proves | Command |
|---|---|---|
| 1. JVM tests | Deterministic behavior of the proxy, relays, codec, DNS, TCP stack, policies, state machines — no hardware | `./gradlew :app:testDebugUnitTest` |
| 2. Single-device smoke | The host path on a real phone: Wi-Fi Direct GO while on Wi-Fi, proxy, relays, tunnel control channel, PIN gate | `bash scripts/device-test.sh` |
| 3. Two-device E2E | A real client joins `DIRECT-…`, enters the PIN, and tunnels all its traffic through the host | `bash scripts/two-device-test.sh` |
| 4. Manual scenarios | Real apps, real use, soak, edge cases | hands-on |

CI runs layer 1 (+ lint) on every push; layers 2–4 are run by hand.

---

## ✅ Verified on device — host path (Galaxy A03s, Android 13)

Last full run: **2026-08-18 — all green.**

| Check | Result |
|---|---|
| Wi-Fi Direct group created while connected to Wi-Fi (STA + P2P-GO concurrency) | ✅ `DIRECT-…` up, passphrase shown |
| Proxy bound to `192.168.49.1:8080` (never `0.0.0.0`) | ✅ |
| Plain HTTP through the proxy (chunked body) | ✅ `HTTP/1.1 200 OK` |
| CONNECT (HTTPS) through the proxy | ✅ `HTTP/1.1 200 Connection Established` |
| DNS on the P2P interface `:53` (with graceful back-off when the system owns it) | ✅ 2 sockets listening |
| TCP tunnel relay `192.168.49.1:7777`: CONNECT frame → real socket → full HTTP response as frames | ✅ 888 bytes, `200 OK` |
| Pairing-PIN gate | ✅ PIN read per session; unauthenticated client refused with `AUTH_REJECTED` (field-tested earlier) |
| Clean stop (foreground service fully gone) | ✅ 0 live service records |

## ✅ Covered by JVM tests (deterministic, CI-gated)

- `ShareStateTest` — host lifecycle state machine (start/stop/failure paths)
- `HttpProxyServerTest` — keep-alive, CONNECT tunnel, chunked + Content-Length bodies, client-abort crash regression
- `UdpRelayTest` — per-flow sockets, reply wrapping, SSRF/LAN destination policy
- `IcmpRelayServerTest` — rootless ICMP relay (echo request → ping socket → reply wrap), SSRF policy, ping-socket-unsupported fallback
- `DnsForwarderTest` — DNS relay behavior
- `Ipv4CodecTest` — checksum-correct IPv4/TCP/UDP/ICMP codec (including `wrapIcmp`)
- `TcpTunnelCoreTest` — handshake, seq/ack bookkeeping, RTO backoff, **fast retransmit (dup-ACK driven)**, FIN/RST handling, segmentation
- `TcpTunnelIntegrationTest` — client core ⇄ server relay over real sockets
- `DestinationPolicyTest` — private/LAN/loopback destinations refused (SSRF protection)
- `SentryInitTest` (androidTest) — crash reporting only active when a DSN is configured

## ✅ Host-path throughput (Galaxy A03s, Android 13)

Measured by `scripts/throughput-test.sh` on 2026-08-18. The device was
charging (battery reading meaningless).

| Metric | Value |
|---|---|
| Download through proxy to internet (10 MB) | 10,486,160 bytes in 29.4s → **0.36 MB/s (2.85 Mbit/s)** |
| Upstream | Wi-Fi (not cellular) |

This is the host-path number (proxy + upstream radio); per-client P2P
throughput requires a second device and is still open.

**Note:** Android's "Connected, no internet" banner appears on clients
because Android's captive-portal probe runs *without* the HTTP proxy.
This is expected — once the client sets its proxy to
`192.168.49.1:8080`, real traffic flows and the banner is cosmetic
(harmless on most ROMs).

## ⚠️ NOT yet proven — needs a second phone (the distribution gate)

- [ ] **Two-device E2E:** client phone joins `DIRECT-…`, enters the pairing PIN, VPN captures *all* of its traffic; TCP *and* UDP (games/VoIP/WebRTC) flow over the real P2P link — `scripts/two-device-test.sh`
- [ ] **ICMP (ping) relay on-device:** relay is implemented and JVM-tested; on-device instrumented tests confirm the UDP carrier path works (`0.0.0.0` binding) and the relay degrades gracefully when SELinux blocks ICMP socket bind (`pingUnsupported++`); the full ping-through path requires a rooted device or custom SELinux policy
- [ ] Client tunnel verified on a second *different* Android version/ROM (only Android 13 / A03s seen so far)
- [ ] Non-Android clients (Windows/macOS): manual proxy, CONNECT, chunked bodies, join UX
- [ ] Multiple concurrent clients (target 3–5) sharing one link; connection accounting (`devices connected`)
- [ ] Battery: drain rate over a ≥1 h sharing session (foreground service + dual-role radio)
- [ ] Soak: heartbeat liveness + sticky service restart across a long session; recovery after the system tears the group down (Samsung-style)
- [ ] Lossy-link behavior on-device: fast retransmit + RTO backoff under real packet loss (unit-tested; not yet seen on a lossy radio)
- [ ] Android 12 and below (location permission path) and Android 14/15
- [ ] IPv6-only upstream, carrier-tethered-plan behavior (data-plan TOS)

## ✅ Explicitly out of scope / accepted limits (stated in the listing)

- ICMP (ping) in tunnel mode: the relay (`IcmpRelayServer` + `OsPingSocket`)
  is implemented and JVM-tested; SELinux blocks `name_bind` on `icmp_socket`
  for `untrusted_app` on stock Android (audit log confirmed), so the relay
  gracefully degrades to dropping — works only on rooted devices or custom
  ROMs with relaxed SELinux policy
- One radio, one channel: throughput reduced, some devices briefly drop the
  upstream when the group forms
- OS-native "Wi-Fi Sharing" (Android 13+) is the recommended Tier 0 when the
  hardware supports it — ShareNet is the universal fallback

## How to run the layers

```bash
JAVA_HOME=~/jdk21 ./gradlew :app:testDebugUnitTest          # layer 1 (85 tests)
bash scripts/device-test.sh                                  # layer 2 (one phone)
bash scripts/two-device-test.sh                              # layer 3 (two phones)
```

When a gap closes, move its checkbox up into the verified tables with the date
and device it was proven on — and only then is the item "done".
