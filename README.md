# ShareNet

**Share your phone's internet connection while staying connected to Wi-Fi yourself — no root, no tethering plan.**

ShareNet turns your Android phone into a Wi-Fi hotspot that re-shares the
phone's *own* Wi-Fi connection (or cellular data) to nearby devices. It works
where a normal hotspot can't: on a phone whose single Wi-Fi radio can't be a
client and an access point at the same time.

```
  [Laptop] ──┐                    ┌── upstream Wi-Fi (STA)
  [Tablet] ──┼── Wi-Fi Direct ────┼── ShareNet phone  ──── Internet
  [Phone]  ──┘   (DIRECT-xxxx)    └── or cellular
```

---

## 1. Why this is hard

A phone normally has **one Wi-Fi radio** that can run in one mode at a time:

- **STA mode** (station / client) — the phone *joins* a Wi-Fi network.
- **AP mode** (softAP / hotspot) — the phone *broadcasts* a Wi-Fi network.

Turning on the classic hotspot forces the phone to drop its Wi-Fi connection —
the radio can't do both. To re-share Wi-Fi you need the phone to run **both
roles at once** ("STA + AP concurrency"), which most phones' software refuses
to do with the normal hotspot.

## 2. The research: four ways to get STA+AP concurrency

| # | Approach | How it works | Root? | What traffic works | Status (2026) |
|---|---|---|---|---|---|
| **0** | **OS-native "Wi-Fi Sharing"** (Android 13+, AOSP) | The OS itself supports concurrent STA+AP on capable chipsets (DBS/SBS/MCC/SCC). In Settings → Hotspot & tethering, turning on the hotspot while on Wi-Fi re-shares that Wi-Fi via NAT. | No | Everything | Zero effort, but hardware-dependent (notably *not* on Pixel 7a/8a/Fold 1), OEMs word the toggle differently, and **there is no public API for an app to drive it**. iOS cannot do this at all. |
| **1** | **Wi-Fi Direct (P2P) group + local proxy** — *what ShareNet does* | The phone stays on Wi-Fi (STA) *and* creates a **Wi-Fi Direct group** as Group Owner. STA + P2P-GO concurrency is supported by virtually every chipset — it is the same mechanism Android used for Cast/Miracast while on Wi-Fi. The group broadcasts as a normal "DIRECT-…" network; clients join it; the host runs an embedded **HTTP proxy** (with CONNECT for HTTPS) that forwards their traffic out over the phone's upstream. | No | HTTP(S) and proxy-aware apps: browsers, most apps, chat, email, streaming | ✅ Most practical universal app. Proven at scale: **NetShare** (4.3★, 50k+ ratings on Play) and **TetherFuseNet** (Apache-2.0 open source) both use it. |
| **2** | **Wi-Fi Direct + full IP tunnel** (NetShare "advanced" mode) | Both phones run the app. The host uses `VpnService` (a tun interface) to inject client packets into the network stack — no root; the client uses `VpnService` to capture *all* of its traffic and tunnel it over the P2P link. | No | Everything, incl. UDP (games, VoIP) | Much more complex, more battery, more fragile; the natural v2 of this project. |
| **3** | **Root: iptables NAT** | Kernel-level packet forwarding between the P2P interface and the upstream. | Yes | Everything | Legacy; modern Android restricts it, and Google keeps deprecating the P2P APIs. |

**Other paths considered and rejected:**
- *Bluetooth tethering* — always coexists with Wi-Fi, but caps out at ~2–3 Mbps.
- *USB/Ethernet tethering* — works while on Wi-Fi on many phones, but wired.
- *iOS* — no public API for third-party Wi-Fi Direct GO or VPN+tethering; iPhones can't re-share Wi-Fi at all. **Android-only is a hard platform constraint, not a choice.**

**Recommendation (what this repo implements):** use the OS's Wi-Fi Sharing
(Tier 0) when the phone supports it — zero battery cost — and ShareNet (Tier 1)
as the universal fallback that also shares cellular data. Tier 2 — the full
tunnel (UDP *and* TCP) — is shipped in this repo as **client mode**.

## 3. How ShareNet works

```
MainActivity ──▶ ShareController / TunnelController (pure state machines, tested)
                      │
ShareService (foreground, type connectedDevice)        TunnelVpnService (client)
   ├── WifiDirectManager → Group Owner "DIRECT-xx"      ├── VpnService (tun 26.0.0.2)
   ├── HttpProxyServer → 192.168.49.1:8080             │    captures all client traffic
   │      ├─ plain HTTP (keep-alive)                   ├── UDP packets over the P2P link
   │      ├─ CONNECT (HTTPS/WSS tunnel)                │    → host UdpRelayServer (per-flow)
   │      └─ chunked & Content-Length bodies           ├── TCP packets → TcpTunnelCore
   ├── UdpRelayServer → 192.168.49.1:5555             │    (user-space TCP stack, seq/ack
   │      └─ UDP payloads → real destination            │     translation tun2socks-style)
   ├── DnsForwarder → 192.168.49.1:53                 └── → TcpTunnelServer → real sockets
   │      └─ DNS queries → upstream resolvers
   └── TcpTunnelServer → 192.168.49.1:7777
          └─ per-app-connection real sockets to the internet
```

**Host mode (Tier 1)** — only the host phone needs the app. Clients join the
`DIRECT-…` network with the shown password and set their HTTP proxy to
`192.168.49.1:8080`. Browsing, email, chat, and most apps work.

**Pairing PIN** — every share session generates a random 4-digit PIN (shown
on screen, in the notification, and in the QR code). A phone running
ShareNet in client mode must enter this PIN before the host will route its
traffic, so a stranger on the hotspot cannot use the host's connection
without consent.

**SSRF/LAN protection** — the proxy, UDP relay, and TCP relay refuse to open
connections into private/LAN/loopback addresses: a joined device can reach
public hosts and the hotspot subnet, but can never use the phone to probe its
own home network or localhost services.

**Client mode (Tier 2, full tunnel)** — a second phone running the app joins
the host's `DIRECT-…` network, then taps **Connect as client**. Its
`VpnService` captures *all* of the client's traffic and tunnels it over the
Wi-Fi Direct link — **no root**:

- **UDP** (games, VoIP, WebRTC, DNS) goes to the host's `UdpRelayServer`,
  which forwards it with per-flow sockets and wraps replies back into the
  client's tun interface.
- **TCP** goes through `TcpTunnelCore`, a user-space TCP stack that performs
  the tun2socks-style sequence-number translation on the client, then rides a
  reliable control connection to the host's `TcpTunnelServer`, which opens a
  *real* socket to each destination and pumps bytes. So non-proxy apps work
  through the tunnel too.
- TCP to the proxy itself (`192.168.49.1:8080`) bypasses the tunnel entirely:
  the P2P subnet route (`192.168.49.0/24`) is more specific than the VPN's
  default route.
- Only ICMP (ping) is still dropped — relaying it needs a raw socket.

**DNS for P2P clients** — the host runs a `DnsForwarder` on
`192.168.49.1:53` so devices that join the group (which receive the GO's
address as their DNS server) can resolve names even without the proxy. It is
best-effort: on devices where the system already owns port 53 on the P2P
interface (common on Samsung), it backs off gracefully.

**OS Wi-Fi Sharing tip** — the app shows a card on Android 13+ pointing to
Settings → Hotspot & tethering (deep-linked button), so users whose hardware
supports the native feature can skip the app entirely.

Design invariants:
- The proxy, relays, and DNS bind **only to the P2P interface address**
  (never `0.0.0.0`), so the phone's own Wi-Fi network can never reach them.
- All state changes flow through pure, dependency-free reducers covered by JVM
  tests (same convention as the Magneetar project).
- The proxy, IPv4 codec, UDP relay, DNS forwarder, TCP stack, and both
  tunnel transports are pure JVM (zero Android imports) and integration-tested
  with real sockets.

## 4. Project layout

```
app/src/main/java/com/sharenet/app/
├── MainActivity.kt          # UI: host mode, client mode, OS-tip card
├── ShareService.kt          # host foreground service (proxy + relay)
├── ShareController.kt       # host state bridge
├── ShareState.kt            # pure host lifecycle state machine (reducer)
├── WifiDirectManager.kt     # P2P GO: create (with BUSY retry), teardown, clients
├── proxy/
│   ├── HttpProxyServer.kt   # pure-JVM HTTP proxy (CONNECT + keep-alive)
│   ├── UdpRelayServer.kt    # pure-JVM UDP relay (per-flow sockets + reply wrap)
│   ├── DnsForwarder.kt      # pure-JVM DNS relay for P2P clients (:53)
│   ├── Ipv4Codec.kt         # minimal IPv4/TCP/UDP codec (checksum-correct)
│   ├── ProxyStats.kt        # byte counters
│   └── P2pAddressResolver.kt# finds the GO IPv4 (never the upstream wlan0)
├── tunnel/
│   ├── TcpTunnelCore.kt     # user-space TCP stack (seq/ack translation)
│   ├── TcpTunnelClient.kt   # client transport: frames <-> control socket
│   ├── TcpTunnelServer.kt   # host relay: one real socket per app connection
│   ├── TunnelProtocol.kt    # client<->host framing
│   ├── TunnelVpnService.kt  # client VpnService (UDP relay + TCP core)
│   └── TunnelController.kt  # client state bridge
└── util/
    ├── Permissions.kt       # NEARBY_WIFI_DEVICES (13+) / location (≤12)
    └── NetworkInfo.kt       # "HomeWiFi (Wi-Fi)" / "Cellular data" + DNS servers
app/src/test/…               # 78 JVM tests: proxy, relay, DNS, TCP stack, codec, policy, auth, state machines
app/src/androidTest/…        # device smoke test (./gradlew connectedDebugAndroidTest)
scripts/
    ├── device-test.sh       # single-device host smoke test (adb, incl. PIN auth)
    └── two-device-test.sh   # real client-joins-host end-to-end test
```

Stack: Kotlin 2.0.21 · AGP 8.10.1 · Gradle 8.12 · minSdk 24 · target/compileSdk 36 ·
Material 3 + AppCompat + core-ktx + ZXing (QR) + Sentry (crash reporting,
opt-in). Versions live in a Gradle version catalog (`gradle/libs.versions.toml`);
CI runs tests + lint on every push (`.github/workflows/ci.yml`).

## 5. Build & install

```bash
cd ~/Projects/ShareNet
JAVA_HOME=~/jdk21 ./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
JAVA_HOME=~/jdk21 ./gradlew :app:testDebugUnitTest    # 78 JVM tests
JAVA_HOME=~/jdk21 ./gradlew :app:lintDebug            # lint gate
JAVA_HOME=~/jdk21 ./gradlew :app:assembleRelease      # signed release (see docs/PLAY-STORE.md)
JAVA_HOME=~/jdk21 ./gradlew :app:connectedDebugAndroidTest  # device smoke test
```

Install `app-debug.apk` on the sharing phone. Android Studio: open the folder
and run — the SDK/AGP/Gradle versions match the local toolchain.

## 6. Using it

**On the host phone:**
1. Connect to Wi-Fi (or stay on cellular — both are shareable).
2. Open ShareNet → **Start sharing**. Grant the location/nearby permission
   (Android needs it for Wi-Fi Direct discovery; the app never reads location).
3. Note the **network name** (`DIRECT-…`), **password**, **proxy address**
   (`192.168.49.1:8080`), **UDP relay** (`192.168.49.1:5555`), and **pairing
   PIN** shown on screen, in the notification, and in a scannable QR code.

**On each client device (plain Tier-1):**
1. Join the `DIRECT-…` network with the shown password.
2. Set the HTTP proxy to `192.168.49.1:8080`:
   - *Android*: Wi-Fi → long-press the network → Modify → Advanced → Proxy
     → Manual.
   - *Windows/macOS*: the network's Proxy settings (Manual, host
     `192.168.49.1`, port `8080`).
   - *Linux*: see `docs/PC-CLIENT.md` for detailed instructions (GNOME,
     Firefox, command-line).

**On an Android client (adds UDP via tunnel mode):**
1. Join the host's `DIRECT-…` network in Wi-Fi settings.
2. Open ShareNet on this phone → in **Client mode**, keep the default host
   (`192.168.49.1`), enter the host's **pairing PIN**, and tap
   **Connect as client**; accept the VPN dialog.
3. Set the HTTP proxy as above for TCP. UDP (games, calls) now works too.

Browsing, email, chat, and most apps work immediately. With the client
phone's tunnel on, even apps that ignore the proxy work — TCP, UDP, and
**ICMP (ping)** all flow through the tunnel. ICMP uses rootless kernel
ping sockets on the host (`OsPingSocket`): no raw socket, no root.

**Android shows "Connected, no internet"** on the client — this is
Android's captive-portal check running *without* the HTTP proxy. Once the
client sets its proxy to `192.168.49.1:8080`, real traffic flows and the
banner is cosmetic (harmless on most ROMs).

## 7. Honest limitations & notes

- **ICMP (ping) in tunnel mode.** The relay (`IcmpRelayServer` +
  `OsPingSocket`) is implemented and JVM-tested. On stock Android, SELinux
  blocks `untrusted_app` from binding ICMP sockets (`name_bind` denied on
  `icmp_socket`), so the relay degrades to dropping — ping works on rooted
  devices or custom ROMs with relaxed policy.
- **One radio, one channel.** The P2P group shares the Wi-Fi radio with the
  upstream connection; throughput is reduced and some devices briefly drop the
  upstream when the group forms (NetShare has the same behavior).
- **Battery.** A foreground service + Wi-Fi radio in two roles costs battery —
  that's why Tier 0 (OS Wi-Fi Sharing) is preferable when the hardware supports
  it.
- **Carrier TOS.** Tethering is a data-plan feature on many carriers; the app
  makes hotspot data indistinguishable from regular data, which some plans
  prohibit. Use it on your own connections.
- **PC / Linux clients.** Linux desktops connect to the Wi-Fi Direct hotspot as
  regular WiFi clients (station mode), not as Wi-Fi Direct P2P peers. On most
  Android devices this works fine — the GO bridges station-mode traffic. If your
  PC gets an IP but can't reach the phone, check that:
  (a) the PC is actually connected to the `DIRECT-…` network (not your regular
  WiFi), (b) IP forwarding is enabled on the phone (`/proc/sys/net/ipv4/ip_forward`
  should be `1`), and (c) the HTTP proxy is configured. See `docs/PC-CLIENT.md`.
- **Known quirks.** A few OEMs (notably some Samsung builds) tear the P2P group
  down aggressively; some devices use a different GO subnet than `192.168.49.1`
  (the proxy binds to whatever address `P2pAddressResolver` finds and shows it
  in the UI).

## 8. Roadmap

Done so far: host sharing (Tier 1) · full tunnel client mode (UDP + TCP + ICMP,
Tier 2) · DNS for P2P clients · OS Wi-Fi Sharing tip card · premium M3 UI with
dark mode · pairing-PIN client auth · SSRF/LAN destination policy · live traffic
stats · QR join code · privacy policy (in-app + `docs/PRIVACY-POLICY.md`) ·
heartbeat liveness + sticky service restart · RTO backoff + dup-ACK fast
retransmit · rootless ICMP relay via kernel ping sockets · CI + version
catalog. Validation status is tracked in `docs/VALIDATION.md`.

1. **Play distribution.** Staged in `docs/PLAY-STORE.md` (signing, Data
   Safety answers, permission declarations) but **parked**: nothing ships
   until the real-world gate in `docs/VALIDATION.md` passes (two-device E2E,
   multi-client, throughput, battery, soak).
2. **Two-device automation.** `scripts/two-device-test.sh` now drives the
   client's join too: it taps the `DIRECT-…` network in Wi-Fi settings, types
   the passphrase, fills in the pairing PIN, and confirms the VPN dialog via
   adb UI automation — with manual fallbacks when a device/ROM resists.
3. **ICMP relay** for ping through tunnel mode (needs a privileged socket).
4. **Lossy-link validation on-device.** Fast retransmit (dup-ACK driven) is
   implemented in `TcpTunnelCore` and covered by a JVM test; confirming it
   under real packet loss on hardware is tracked in `docs/VALIDATION.md`.

## 9. Verified on hardware (Galaxy A03s, Android 13)

- Wi-Fi Direct group created **while connected to Wi-Fi** (STA + P2P-GO
  concurrency): `DIRECT-Lz-Galaxy A03s`, passphrase shown.
- Proxy bound to `192.168.49.1:8080`, UDP relay to `192.168.49.1:5555`,
  TCP tunnel relay to `192.168.49.1:7777`.
- Real HTTP request through the proxy → internet response returned (chunked
  body forwarded). CONNECT (HTTPS) → `HTTP/1.1 200 Connection Established`.
- **Full TCP tunnel frame round-trip on-device**: CONNECT frame → relay opened
  a real socket to example.com → full HTTP 200 response (chunked body) returned
  as REMOTE_DATA frames → clean REMOTE_CLOSE.
- The DNS forwarder backs off gracefully when the system owns port 53 on the
  P2P interface (clients still get DNS from the system's server).
- Premium M3 UI verified in dark mode: hero status card with live radar orb,
  credential copy buttons, client mode card, OS-settings deep link.
-On-device testing caught and fixed: P2P broadcast receivers needing
`RECEIVER_EXPORTED` on Android 13, `createGroup` BUSY retries, the resolver
picking the upstream `wlan0` address, a notification format crash
(`%d`/String arg order), and an **uncaught `SocketException` in the proxy's
CONNECT tunnel** — when a client closed right after `200 Connection
Established`, the upstream pump's socket close could race the downstream
thread's `getInputStream()` and crash the whole process (regression-tested).
- Field test also verified live on-device: the tunnel refuses an unauthenticated
  client with the exact `AUTH_REJECTED` frame (empty PIN → 5-byte rejection),
  confirming the pairing gate works over the real Wi-Fi Direct link.

## 10. Privacy

ShareNet collects nothing, stores nothing, and sends nothing anywhere except
the traffic you explicitly share through your own phone. See
`docs/PRIVACY-POLICY.md` and the in-app **About → Privacy policy** card.

**Hosting the policy (required for Play Store).** `docs/PRIVACY-POLICY.html`
is a self-contained page ready to publish on any static host (GitHub Pages,
Netlify, Cloudflare Pages — no server needed). Once it is live, build with
`-PprivacyPolicyUrl=https://your.host/privacy.html` (or put the property in
gradle.properties) and the About dialog gains a **View online** button that
opens the hosted policy in the browser; without the property the dialog shows
the in-app text only. Paste the same URL into the Play Console listing and the
Data Safety form.

**Crash reporting (optional, off by default).** The app bundles the Sentry
Android SDK (the `sentry-android-core` artifact only — no native libs, no
screen recording) but it is **inactive unless a DSN is configured**: copy
`sentry.properties.example` to `sentry.properties` (gitignored) and replace
`dsn=` with your own, and the release build embeds it. Then
crashes are reported to that Sentry project, with the Wi-Fi Direct subnet
(`192.168.49.x`) and tunnel range (`26.0.0.x`) scrubbed from messages before
upload. Without the file, `BuildConfig.SENTRY_DSN` is empty and no crash data
leaves the device. (Adding the Sentry Gradle plugin later enables symbol
upload for readable stack traces; the DSN-only setup still captures crashes.)
Sentry's auto-init provider is disabled in the manifest; the app initializes
the SDK itself only when a DSN is present.
