# ShareNet Privacy Policy

_Last updated: August 2026_

This policy explains what ShareNet does with information. The short version:
**ShareNet is a relay. It collects nothing, stores nothing, and sends nothing
anywhere except the network traffic you explicitly choose to share through
your own device.**

## What ShareNet does

ShareNet turns your Android phone into a local Wi‑Fi hotspot that shares your
phone's internet connection (Wi‑Fi or cellular) with nearby devices. It does
this by creating a Wi‑Fi Direct group and running a local proxy/relay on your
phone. Devices you invite join that network and route their traffic through
your phone.

## Data collection

ShareNet does **not** collect, transmit, or store any personal data:

- No accounts, no sign‑in, no analytics, no advertising identifiers are used.
- **Crash reporting is opt‑in and off by default.** The app bundles the
  Sentry Android SDK but it is inactive unless the developer configures a
  Sentry DSN at build time; without that configuration no crash data is sent
  to anyone. When configured (release builds only, as the developer ships
  them), crash reports are sent to the developer's Sentry project and may
  contain device model, OS version, and stack traces; the app scrubs
  Wi‑Fi Direct session addresses (`192.168.49.x`, `26.0.0.x`) from report
  content before upload.
- The app never reads your location. On older Android versions (12 and
  below) Android itself requires a location permission to use Wi‑Fi Direct;
  ShareNet declares it only because the platform demands it and never reads
  location data.
- No data leaves your device except the network traffic you explicitly share:
  the bytes that pass through your phone's proxy/relay to the destinations
  you (or the devices you invited) request, forwarded by your own phone over
  your own connection.

## The pairing PIN and session details

While a sharing session is active, the app shows the hotspot name, password,
proxy address, and a randomly generated pairing PIN on the screen and in a
notification. These exist only for the current session, are generated on your
device, and are not transmitted anywhere except to the devices you choose to
share them with. The client host address and PIN you enter on a joining phone
are stored locally on that phone only.

## Permissions

- **Nearby Wi‑Fi devices** (Android 13+): required by Android to create and
  manage the Wi‑Fi Direct group.
- **Notifications**: shows the ongoing sharing/tunnel status.
- **Location** (Android 12 and below): required by the platform for Wi‑Fi
  Direct on those versions; never used to read your location.

## Network traffic

ShareNet relays traffic exactly as the user directs it. It does not inspect,
log, modify, or sell the content of any traffic. Because the relay runs on
your own phone, all forwarded traffic is subject to the privacy of your own
connection (your carrier/ISP) and of the destination services.

## Children

ShareNet does not target children and does not collect any data from anyone.

## Changes

If this policy changes, the updated version will be posted here with a new
"last updated" date.

## Contact

For questions about this policy, contact the developer at the support email
listed on the app's store listing.
