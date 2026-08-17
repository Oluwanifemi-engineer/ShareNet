# Play Store listing draft

Ready-to-paste copy for the Play Console listing. Complements
`docs/PLAY-STORE.md` (signing, Data Safety, permissions) and
`docs/PRIVACY-POLICY.md` (the required privacy-policy URL — host it and put
the link in the listing, the in-app About dialog, and the Play Data Safety
form).

## App identity

- **App name:** ShareNet — Wi-Fi Sharing (13 chars; consider dropping "—" if
  the console rejects em dashes, e.g. "ShareNet Wi-Fi Sharing")
- **Short description** (80 chars max):
  > Share your Wi-Fi while staying on it — no root, no tethering plan.
- **Full description** (see below, ~4,000 chars budget)
- **Category:** Tools → Utility; secondary: Communication
- **Content rating:** Everyone (no objectionable content; confirm in the
  questionnaire that the app relays user-directed traffic only)
- **Price:** Free
- **Countries:** all

## Full description (paste-ready)

```
ShareNet turns your Android phone into a Wi-Fi hotspot that re-shares your
phone's own internet connection — so you can stay connected to Wi-Fi yourself
while sharing it with your laptop, tablet, or second phone. No root. No
tethering plan. Works where the built-in hotspot can't: on phones whose
single Wi-Fi radio refuses to be a client and an access point at the same
time.

HOW IT WORKS
- Start sharing: ShareNet creates a secure Wi-Fi Direct network
  (DIRECT-xxxx) and shows the name, password, and proxy address on screen
  and in a QR code — scan or type, and nearby devices are online in seconds.
- Pairing PIN: every session gets a random 4-digit PIN. Devices must enter
  it before the host routes their traffic, so strangers on the hotspot
  can't use your connection.
- Stay on Wi-Fi: your upstream Wi-Fi (or cellular) keeps working the whole
  time — ShareNet shares it instead of replacing it.
- Client tunnel mode: a second phone running ShareNet can join and tunnel
  ALL of its traffic (UDP included — games, calls, streaming) over the
  link, with no root required.

PRIVACY & SAFETY
- Runs entirely on your phone: ShareNet is a relay. It collects nothing,
  stores nothing, and never inspects your traffic.
- Protected against LAN probing: joined devices can reach the internet and
  the hotspot subnet, but never your home network or your phone's local
  services.
- No ads. No accounts. No analytics. Crash reporting is off by default and
  only active if the developer enables it in the build.

NOTES
- Sharing uses your phone's data plan; check with your carrier if tethering
  is restricted.
- One Wi-Fi radio does double duty, so throughput is reduced while sharing
  and the battery drains faster.
- Ping (ICMP) is not supported in tunnel mode.

PERMISSIONS
- Nearby Wi-Fi devices (Android 13+): creates the Wi-Fi Direct group.
- Location (Android 12 and below): required by Android for Wi-Fi Direct on
  those versions; ShareNet never reads your location.
- Notifications: shows the ongoing sharing status.
```

## Screenshots checklist (7 required; make the first 2 tell the story)

1. **Hero — sharing live:** the host screen mid-session: green status card,
   `DIRECT-…` name, password, proxy address, and the QR code visible.
   Caption: "One tap to share — scan the QR to join."
2. **Pairing PIN close-up:** the PIN row + copy button on the host card.
   Caption: "Every session is PIN-protected."
3. **Client mode card:** the Connect-as-client card with host/PIN fields.
   Caption: "Tunnel ALL your traffic — UDP included, no root."
4. **Traffic stats:** the hero card showing bytes up/down and active
   connections. Caption: "See exactly what's moving."
5. **Dark mode:** the same screens in dark theme (Play shows theme variety).
6. **OS tip card:** the "your phone may already do this" card deep-linking
   to Settings → Hotspot & tethering. Caption: "Prefer the built-in
   hotspot? One tap takes you there."
7. **Device context shot:** laptop/tablet joining the DIRECT network (use a
   mockup, never a photo of a real person's screen without consent).

Rules: 1080×1920 (9:16) or 1080×2400, PNG/JPG, no text below the bottom
~8% or inside status-bar area, keep captions inside the frame.

## Feature graphic (1024×500, required for the store page)

- Left: phone silhouette with a signal-orb motif (the app's hero visual).
- Right: big "Share Wi-Fi, stay on Wi-Fi" headline; small "No root · No
  tethering plan" subline.
- Keep the logo top-left, no important content in the outer 15% margins
  (text can be cropped there in some placements).

## Optional store assets

- **Promo video (YouTube, ≤ 30s):** host starts sharing → QR scanned by a
  second phone → client taps Connect → both devices browsing.
- **Phone frames** on the screenshots: rounded corners, no mockups that
  obscure the status card.
- **Localized strings** for the description: at minimum de, es, fr, pt-BR,
  hi, ar — translate the short description first, then the hero screenshot
  captions.

## Tag / keyword ideas

`wifi sharing`, `share wifi`, `wifi repeater`, `internet sharing`,
`hotspot`, `wifi direct`, `tethering`, `no root`, `lan`, `proxy`

## Pre-submit checklist

- [ ] Privacy policy hosted at a public URL; link added to the listing, the
      in-app About dialog, and the Data Safety form (see
      `docs/PRIVACY-POLICY.md`).
- [ ] Release APK signed with the real keystore (`docs/PLAY-STORE.md` §1).
- [ ] Data Safety form matches `docs/PLAY-STORE.md` §4 (no data collected /
      transmitted when crash reporting is off; declare Sentry as a third
      party only if a DSN is configured in the build you ship).
- [ ] App access section: account deletion "not applicable" (no accounts).
- [ ] Ads declaration: "No" (no ad SDK in the project).
- [ ] Screenshots re-verified against the final UI (ids: `toggleButton`,
      `pinValue`, `passphraseValue` — the QR card sits under the hero card).
- [ ] `versionCode` bumped per upload (`docs/PLAY-STORE.md` §2).
