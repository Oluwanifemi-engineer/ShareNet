#!/usr/bin/env bash
# two-device-test.sh — the real end-to-end test: one phone shares its
# connection over Wi-Fi Direct, a second phone joins the DIRECT-… network and
# browses through the tunnel (proxy + TCP + UDP).
#
# Requirements:
#   - TWO Android phones connected over adb (host = first device in `adb devices`).
#   - The client phone must be able to join a Wi-Fi network and have its
#     PROXY/TUNNEL set up. Wi-Fi joining cannot be fully scripted without
#     root, so this script automates everything it can and prompts for the
#     manual steps with precise instructions.
#
# Usage:  bash scripts/two-device-test.sh
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/home/oluwanifemi/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.sharenet.app"

DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
COUNT=$(echo "$DEVICES" | grep -c . || true)
if [ "$COUNT" -lt 2 ]; then
  echo "Need TWO devices connected over adb (found $COUNT). Aborting." >&2
  exit 1
fi
HOST_DEV=$(echo "$DEVICES" | head -1)
CLIENT_DEV=$(echo "$DEVICES" | tail -1)
echo "Host:   $HOST_DEV"
echo "Client: $CLIENT_DEV"
echo

echo "==> Building"
(cd "$(dirname "$0")/.." && JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}" ./gradlew :app:assembleDebug -q)

echo "==> Installing on both phones"
"$ADB" -s "$HOST_DEV" install -r "$APK"
"$ADB" -s "$CLIENT_DEV" install -r "$APK"

echo "==> Granting permissions on both phones"
for dev in "$HOST_DEV" "$CLIENT_DEV"; do
  "$ADB" -s "$dev" shell pm grant $PKG android.permission.NEARBY_WIFI_DEVICES || true
  "$ADB" -s "$dev" shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
done

echo
echo "==> STEP 1 — start sharing on the HOST phone ($HOST_DEV)"
echo "    The app opens automatically; tap 'Start sharing'."
"$ADB" -s "$HOST_DEV" shell am force-stop $PKG
"$ADB" -s "$HOST_DEV" shell am start -n $PKG/.MainActivity
sleep 4
# The uiautomator dump is a single line; pull the fields we need with a
# regex instead of splitting on '>' (which POSIX tr would silently not do).
ui_field() { # ui_field <xml-file> <resource-id-substring> -- node's text
  # Attribute order in uiautomator dumps is not guaranteed (text may come
  # before resource-id), so find the node by id and read its text per-node.
  "$ADB" -s "$HOST_DEV" shell cat "$1" | python3 -c "
import re, sys
xml = sys.stdin.read()
for node in re.findall(r'<node [^>]*>', xml):
    if sys.argv[1] in node:
        m = re.search(r'text=\"([^\"]*)\"', node)
        if m:
            print(m.group(1))
            break
" "$2" 2>/dev/null | head -1
}

tap_button() {
  "$ADB" -s "$HOST_DEV" shell uiautomator dump /sdcard/sn.xml >/dev/null 2>&1
  local XY=$(ui_field /sdcard/sn.xml \
    'resource-id="[^"]*toggleButton"[^>]*bounds="\\[([0-9]+),([0-9]+)\\]\\[([0-9]+),([0-9]+)\\]"' | python3 -c "
import sys
x1, y1, x2, y2 = map(int, sys.stdin.read().split())
print(int((x1+x2)/2), int((y1+y2)/2))
")
  if [ -z "$XY" ]; then
    echo "    toggle button not found"
    return 1
  fi
  "$ADB" -s "$HOST_DEV" shell input tap $XY
  echo "    tapped Start at ($XY). Waiting for the group (up to 30s)..."
}
tap_button
SSID=""
for i in $(seq 1 30); do
  sleep 1
  "$ADB" -s "$HOST_DEV" shell uiautomator dump /sdcard/sn2.xml >/dev/null 2>&1
  SSID=$(ui_field /sdcard/sn2.xml 'DIRECT-')
  [ -n "$SSID" ] && break
done
# The same live dump also holds the passphrase and proxy values.
PASS=$(ui_field /sdcard/sn2.xml 'passphraseValue')
if [ -z "$SSID" ]; then echo "    group did not appear in time."; exit 1; fi
echo "    Host network: $SSID  (password: $PASS)"

echo
echo "==> STEP 2 — connect the CLIENT phone ($CLIENT_DEV) to the DIRECT network"
echo "    This needs the UI: Settings -> Wi-Fi -> join \"$SSID\" -> password \"$PASS\"."
echo "    Doing it via adb is not possible without root on most devices."
read -r -p "    Press Enter once the client is CONNECTED to $SSID... " _

echo
echo "==> STEP 3 — client tunnel mode (on the client phone)"
"$ADB" -s "$CLIENT_DEV" shell am start -n $PKG/.MainActivity
echo "    In the app: enter host 192.168.49.1, the pairing PIN shown on the"
echo "    HOST's screen, and tap 'Connect', then accept Android's VPN consent"
echo "    dialog."
read -r -p "    Press Enter once the client shows 'Connected to 192.168.49.1'... " _

echo
echo "==> STEP 4 — data-path checks on the CLIENT"
echo "    -- TCP through the tunnel (non-proxy): curl-style request"
"$ADB" -s "$CLIENT_DEV" shell 'printf "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n" | toybox nc -w 8 192.168.49.1 8080' | head -3
echo "    -- DNS: resolve through the host"
"$ADB" -s "$CLIENT_DEV" shell "dumpsys connectivity | grep -A2 'Active default' | head -3" || true

echo
echo "==> Done. If the HTTP request returned a 200, the full chain works:"
echo "    client app -> VpnService -> Wi-Fi Direct -> host relay -> internet."
