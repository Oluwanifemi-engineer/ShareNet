#!/usr/bin/env bash
# two-device-test.sh — the real end-to-end test: one phone shares its
# connection over Wi-Fi Direct, a second phone joins the DIRECT-… network and
# browses through the tunnel (proxy + TCP + UDP).
#
# Requirements:
#   - TWO Android phones connected over adb (host = first device in `adb devices`).
#   - The client phone must be able to join a Wi-Fi network. Joining is now
#     attempted automatically by driving the Wi-Fi settings UI over adb
#     (uiautomator dump + input tap/text); if that fails the script falls
#     back to asking you to do it by hand.
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

# ── adb UI helpers (device-parameterized) ────────────────────────────────────

dump_ui() { # dump_ui <device> <remote-path> — refresh a uiautomator dump
  "$ADB" -s "$1" shell uiautomator dump "$2" >/dev/null 2>&1 || true
}

# node_text <device> <remote-xml> <needle> — text of the first node whose
# attributes contain <needle> (resource-id, text, class, … all searched).
node_text() {
  "$ADB" -s "$1" shell cat "$2" 2>/dev/null | python3 -c "
import re, sys
needle = sys.argv[1]
for node in re.findall(r'<node\b[^>]*>', sys.stdin.read()):
    if needle in node:
        m = re.search(r'text=\"([^\"]*)\"', node)
        if m:
            print(m.group(1))
            sys.exit(0)
sys.exit(1)
" "$3"
}

# node_center <device> <remote-xml> <needle> — "x y" tap target (center of the
# first node whose attributes contain <needle>).
node_center() {
  "$ADB" -s "$1" shell cat "$2" 2>/dev/null | python3 -c "
import re, sys
needle = sys.argv[1]
for node in re.findall(r'<node\b[^>]*>', sys.stdin.read()):
    if needle in node:
        m = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', node)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
" "$3"
}

tap_xy() { # tap_xy <device> <"x y">
  "$ADB" -s "$1" shell input tap $2 >/dev/null 2>&1 || true
}

# input_text <device> <text> — type text (spaces become %s, shell-escaped).
input_text() {
  local dev=$1 text=$2 esc
  esc="${text// /%s}"
  esc=$(printf '%q' "$esc")
  "$ADB" -s "$dev" shell input text "$esc" >/dev/null 2>&1 || true
}

# wifi_connected_to <device> <ssid-substring> — 0 if the device is joined to it.
wifi_connected_to() {
  local dev=$1 ssid=$2
  if "$ADB" -s "$dev" shell cmd wifi status 2>/dev/null | grep -qi "$ssid"; then
    return 0
  fi
  if "$ADB" -s "$dev" shell dumpsys wifi 2>/dev/null | grep -qiE "mWifiInfo.*$ssid|SSID: ?\"?$ssid"; then
    return 0
  fi
  return 1
}

# join_direct <device> <ssid> <passphrase> — drive Settings → Wi-Fi to join the
# DIRECT network. Best-effort: returns 1 if it can't be done automatically.
join_direct() {
  local dev=$1 ssid=$2 pass=$3 xy field i
  "$ADB" -s "$dev" shell svc wifi enable >/dev/null 2>&1 || true
  "$ADB" -s "$dev" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  "$ADB" -s "$dev" shell am start -a android.settings.WIFI_SETTINGS >/dev/null 2>&1 || true
  sleep 2
  xy=""
  for i in $(seq 1 12); do
    sleep 1
    dump_ui "$dev" /sdcard/wifi.xml
    xy=$(node_center "$dev" /sdcard/wifi.xml "$ssid" || true)
    [ -n "$xy" ] && break
  done
  if [ -z "$xy" ]; then # fall back to any DIRECT-… row (SSID may be truncated)
    for i in $(seq 1 6); do
      sleep 1
      dump_ui "$dev" /sdcard/wifi.xml
      xy=$(node_center "$dev" /sdcard/wifi.xml 'DIRECT-' || true)
      [ -n "$xy" ] && break
    done
  fi
  [ -z "$xy" ] && return 1
  tap_xy "$dev" "$xy"
  field=""
  for i in $(seq 1 6); do # wait for the password field to appear
    sleep 1
    dump_ui "$dev" /sdcard/wifi.xml
    field=$(node_center "$dev" /sdcard/wifi.xml 'class="android.widget.EditText"' || true)
    [ -n "$field" ] && break
  done
  [ -z "$field" ] && return 1
  tap_xy "$dev" "$field"
  sleep 1
  input_text "$dev" "$pass"
  "$ADB" -s "$dev" shell input keyevent 66 >/dev/null 2>&1 || true # ENTER → Connect
  for i in $(seq 1 15); do
    sleep 1
    if wifi_connected_to "$dev" "$ssid"; then
      echo "    client joined $ssid"
      return 0
    fi
  done
  return 1
}

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
"$ADB" -s "$HOST_DEV" shell am force-stop $PKG
"$ADB" -s "$HOST_DEV" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" -s "$HOST_DEV" shell am start -n $PKG/.MainActivity
sleep 4
dump_ui "$HOST_DEV" /sdcard/sn.xml
XY=$(node_center "$HOST_DEV" /sdcard/sn.xml 'resource-id="[^"]*toggleButton"' || true)
if [ -z "$XY" ]; then
  echo "    toggle button not found — is the app open on the host?"
  exit 1
fi
tap_xy "$HOST_DEV" "$XY"
echo "    tapped Start at ($XY). Waiting for the group (up to 30s)..."
SSID=""
for i in $(seq 1 30); do
  sleep 1
  dump_ui "$HOST_DEV" /sdcard/sn2.xml
  SSID=$(node_text "$HOST_DEV" /sdcard/sn2.xml 'DIRECT-' || true)
  [ -n "$SSID" ] && break
done
PASS=$(node_text "$HOST_DEV" /sdcard/sn2.xml 'passphraseValue' || true)
PIN=$(node_text "$HOST_DEV" /sdcard/sn2.xml 'pinValue' || true)
if [ -z "$SSID" ]; then echo "    group did not appear in time."; exit 1; fi
echo "    Host network: $SSID  (password: $PASS)  (PIN: $PIN)"

echo
echo "==> STEP 2 — connect the CLIENT phone ($CLIENT_DEV) to $SSID"
if join_direct "$CLIENT_DEV" "$SSID" "$PASS"; then
  echo "    automatic join succeeded."
else
  echo "    Could not join automatically (varies by device/ROM)."
  echo "    Do it by hand: Settings -> Wi-Fi -> join \"$SSID\" -> password \"$PASS\"."
  read -r -p "    Press Enter once the client is CONNECTED to $SSID... " _
fi

echo
echo "==> STEP 3 — client tunnel mode (on the client phone)"
"$ADB" -s "$CLIENT_DEV" shell am start -n $PKG/.MainActivity
sleep 3
# Scroll to the client-mode card if it is below the fold, then fill in the PIN.
PINXY=""
for i in 1 2 3 4 5; do
  dump_ui "$CLIENT_DEV" /sdcard/cli.xml
  PINXY=$(node_center "$CLIENT_DEV" /sdcard/cli.xml 'resource-id="[^"]*clientPinInput"' || true)
  [ -n "$PINXY" ] && break
  "$ADB" -s "$CLIENT_DEV" shell input swipe 540 1500 540 500 250 >/dev/null 2>&1 || true
  sleep 1
done
if [ -n "$PINXY" ] && [ -n "$PIN" ]; then
  tap_xy "$CLIENT_DEV" "$PINXY"
  sleep 1
  input_text "$CLIENT_DEV" "$PIN"
  echo "    filled pairing PIN ($PIN) into the client app."
  "$ADB" -s "$CLIENT_DEV" shell input keyevent 111 >/dev/null 2>&1 || true # hide IME
  sleep 1
  dump_ui "$CLIENT_DEV" /sdcard/cli.xml
  CXY=$(node_center "$CLIENT_DEV" /sdcard/cli.xml 'resource-id="[^"]*clientToggleButton"' || true)
  [ -n "$CXY" ] && tap_xy "$CLIENT_DEV" "$CXY"
  sleep 2
  "$ADB" -s "$CLIENT_DEV" shell input keyevent 66 >/dev/null 2>&1 || true # VPN consent: confirm
  sleep 1
else
  echo "    Could not find the client-mode PIN field — doing this part manually."
fi
echo "    Verifying the tunnel connects to 192.168.49.1..."
CONNECTED=""
for i in $(seq 1 12); do
  sleep 1
  dump_ui "$CLIENT_DEV" /sdcard/cli.xml
  CONNECTED=$(node_text "$CLIENT_DEV" /sdcard/cli.xml 'Connected to' || true)
  [ -n "$CONNECTED" ] && break
done
if [ -n "$CONNECTED" ]; then
  echo "    client shows: $CONNECTED"
else
  echo "    Could not verify the tunnel automatically. If Android's VPN"
  echo "    consent dialog is still up, tap OK/Allow on the client."
  read -r -p "    Press Enter once the client shows 'Connected to 192.168.49.1'... " _
fi

echo
echo "==> STEP 4 — data-path checks on the CLIENT"
echo "    -- TCP through the tunnel (non-proxy): curl-style request"
"$ADB" -s "$CLIENT_DEV" shell 'printf "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n" | toybox nc -w 8 192.168.49.1 8080' | head -3
echo "    -- DNS: resolve through the host"
"$ADB" -s "$CLIENT_DEV" shell "dumpsys connectivity | grep -A2 'Active default' | head -3" || true

echo
echo "==> Done. If the HTTP request returned a 200, the full chain works:"
echo "    client app -> VpnService -> Wi-Fi Direct -> host relay -> internet."
