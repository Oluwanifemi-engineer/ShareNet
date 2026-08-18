#!/usr/bin/env bash
# throughput-test.sh — measure the host path's real throughput through the
# proxy, plus a short-session battery reading, on a connected device.
#
# What this measures / what it does not:
#   - The transfer goes host-shell -> proxy on the P2P interface -> upstream
#     internet, so it captures proxy + upstream speed (NOT the Wi-Fi Direct
#     radio — that needs a second device actually joining the group).
#   - Battery drain is a short-session smoke number; the >=1h soak stays a
#     manual gate item in docs/VALIDATION.md.
#
# Usage:  bash scripts/throughput-test.sh   (device must be connected via USB)
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/home/oluwanifemi/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.sharenet.app"
PROXY_HOST="192.168.49.1"
PROXY_PORT="8080"  # toybox nc needs HOST PORT as separate args, not host:port

# Plain-HTTP large files, tried in order (the Host header must match the URL).
URLS=(
  "http://speedtest.tele2.net/10MB.zip"
  "http://ipv4.download.thinkbroadband.com/10MB.zip"
  "http://cachefly.cachefly.net/10mb.test"
)

echo "==> Building"
(cd "$(dirname "$0")/.." && JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}" ./gradlew :app:assembleDebug -q)

echo "==> Installing"
"$ADB" install -r "$APK" >/dev/null

echo "==> Permissions"
"$ADB" shell pm grant $PKG android.permission.NEARBY_WIFI_DEVICES || true
"$ADB" shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

battery_level() { "$ADB" shell dumpsys battery | grep -E '^\s*level:' | awk '{print $2}'; }
battery_temp()  { "$ADB" shell dumpsys battery | grep -E '^\s*temperature:' | awk '{print $2}'; }

echo "==> Starting sharing (AUTO_START intent)"
"$ADB" shell am force-stop $PKG
"$ADB" shell am start -n $PKG/.MainActivity -a com.sharenet.app.action.AUTO_START >/dev/null
sleep 4

echo "==> Waiting for the proxy (up to 30s)"
UP=""
for i in $(seq 1 30); do
  sleep 1
  UP=$("$ADB" shell uiautomator dump /sdcard/sn2.xml >/dev/null 2>&1
    "$ADB" shell cat /sdcard/sn2.xml | tr '>' '>\n' | grep -oE 'text="192.168.49.1:8080"' | head -1 || true)
  [ -n "$UP" ] && break
done
[ -z "$UP" ] && { echo "    proxy did not come up in time."; exit 1; }
echo "    proxy is up."

echo "==> Upstream context"
"$ADB" shell "dumpsys wifi | grep -m1 -E 'mWifiInfo|Wi-Fi is' || true"
"$ADB" shell "dumpsys connectivity | grep -m1 'Active default' || true"

if "$ADB" shell "dumpsys battery | grep -q 'powered: true'" 2>/dev/null; then
  echo "    NOTE: device is charging — the battery reading below is meaningless."
fi
LVL0=$(battery_level); TEMP0=$(battery_temp)
echo "    battery before: level ${LVL0}%  temp $((TEMP0 / 10)).$((TEMP0 % 10)) C"

echo "==> Throughput: ~10 MB through the proxy"
BEST_B=0; BEST_T=0
for url in "${URLS[@]}"; do
  host=$(echo "$url" | sed -E 's#https?://([^/]+)/.*#\1#')
  S=$(date +%s%N)
  "$ADB" shell "printf 'GET $url HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n' | toybox nc -w 60 $PROXY_HOST $PROXY_PORT > /data/local/tmp/dl.bin" >/dev/null 2>&1 || true
  E=$(date +%s%N)
  B=$("$ADB" shell "wc -c < /data/local/tmp/dl.bin" 2>/dev/null | tr -d ' \r' || true)
  [ -z "$B" ] && B=0
  echo "    $host: $B bytes in $(( (E - S) / 1000000 ))ms"
  if [ "$B" -gt "$BEST_B" ]; then BEST_B=$B; BEST_T=$((E - S)); fi
  [ "$B" -gt 1000000 ] && break
done
python3 - "$BEST_B" "$BEST_T" <<'PY'
import sys
b, t_ns = int(sys.argv[1]), int(sys.argv[2])
secs = t_ns / 1e9
if secs <= 0 or b <= 0:
    print("    speed:  <no usable download>")
else:
    print(f"    speed:  {b / secs / 1e6:.2f} MB/s  ({b * 8 / secs / 1e6:.2f} Mbit/s)")
PY

echo "==> Holding the session 60s for a battery reading"
sleep 60
LVL1=$(battery_level); TEMP1=$(battery_temp)
echo "    battery after:  level ${LVL1}%  temp $((TEMP1 / 10)).$((TEMP1 % 10)) C"
echo "    drain: $((LVL0 - LVL1))% over ~2 min; temp delta: +$(((TEMP1 - TEMP0) / 10)) C"

echo "==> Stopping sharing"
"$ADB" shell am force-stop $PKG
echo "Done."
