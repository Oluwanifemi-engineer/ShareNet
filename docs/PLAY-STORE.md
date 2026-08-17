# Play Store release

Everything needed to take ShareNet from this repo to the Play Store.

## 1. Signing

Create a release keystore (do this ONCE — the Play App Signing key is the
one you must keep forever):

```bash
keytool -genkey -v -keystore sharenet-release.jks -keyalg RSA \
  -keysize 2048 -validity 10000 -alias sharenet
```

Then create `keystore.properties` next to `settings.gradle.kts`
(gitignored — never commit it):

```properties
storeFile=/absolute/path/to/sharenet-release.jks
storePassword=...
keyAlias=sharenet
keyPassword=...
```

Build the release APK / AAB:

```bash
./gradlew :app:bundleRelease     # Play requires the .aab
# or
./gradlew :app:assembleRelease   # plain APK for sideloading
```

Without `keystore.properties` the release build signs with the debug key —
fine for testing, not for upload.

## 2. Versioning

Bump `versionCode` (monotonic int) and `versionName` in
`app/build.gradle.kts` for every release.

## 3. Permissions — declaration required

ShareNet uses two permission groups that Google flags:

| Permission | Why | Play Console declaration |
|---|---|---|
| `NEARBY_WIFI_DEVICES` | Create/manage the Wi-Fi Direct group | "Nearby devices" — declare Wi-Fi usage; no user data accessed |
| `POST_NOTIFICATIONS` | Foreground-service status | Runtime permission — no declaration needed |
| `VpnService` (tunnel mode) | Client-mode traffic relay | NOT a user-visible permission, but Google may review under "VPN apps" policy — declare in the "App content" section |

No runtime data collection happens. Nothing leaves the device except the
bytes the user explicitly shares (their own traffic, forwarded by their own
phone). Data Safety form: **no data collected/shared**.

## 3b. Privacy policy (required, even with zero data collection)

Play's User Data policy requires a **privacy policy URL in the store listing
AND inside the app** for every app, regardless of whether it collects data.

- Host `docs/PRIVACY-POLICY.md` somewhere public (GitHub Pages, Google
  Sites, your own domain) and paste the URL into the Play Console.
- The app already shows the policy text in-app (About → Privacy policy); the
  store listing needs the hosted URL too.

## 3c. Tunnel pairing PIN (client mode)

Client (tunnel) mode now requires the 4-digit **pairing PIN** shown on the
host's screen. This is a consent boundary, not a data feature — no PIN or
session data leaves the devices involved. No extra Play declaration needed.

## 4. Data Safety form (recommended answers)

- **Does your app collect or share any user data?** No.
- **Is all data encrypted in transit?** N/A (no data collection).
- Note in the "other" disclosures: the app relays network traffic the user
  explicitly directs through their own device.

## 5. Content rating

Use the questionnaire defaults: no mature content. Suggested rating: **Everyone**.

## 6. Target audience & support

- Target SDK 36 (required for new uploads in 2026).
- minSdk 24 (Android 7.0) — covers virtually all active devices.
- Screenshots: the app's main screen in light and dark mode.
- Support email: set one in Play Console (required for store listing).

## 7. Before every upload

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

- All unit tests pass (currently 72: proxy, DNS, UDP relay, IPv4 codec, the
  user-space TCP stack, the destination policy, and the state machine).
- Lint clean.
- One device smoke test: `bash scripts/device-test.sh` (host sharing) and
  `bash scripts/two-device-test.sh` (client joining) — see README.

## Known limitations to state in the listing

- Works on Android only; the phone sharing must be the Wi-Fi Direct "host".
- Some apps ignore proxy settings — ShareNet's client mode covers those.
- A tiny fraction of devices/ISPs block Wi-Fi Direct; the OS built-in
  hotspot ("Wi-Fi Sharing") may be available as an alternative on Android 13+.
