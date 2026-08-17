#!/usr/bin/env bash
# device-test.sh — install ShareNet on a connected device and smoke-test the
# host sharing flow (Wi-Fi Direct group + proxy + UDP relay) via adb.
#
# Usage:  bash scripts/device-test.sh   (device must be connected via USB)
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-/home/oluwanifemi/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.sharenet.app"

echo "==> Building"
(cd "$(dirname "$0")/.." && JAVA_HOME="${JAVA_HOME:-$HOME/jdk21}" ./gradlew :app:assembleDebug -q)

echo "==> Installing $APK"
"$ADB" install -r "$APK"

echo "==> Granting permissions"
"$ADB" shell pm grant $PKG android.permission.NEARBY_WIFI_DEVICES || true
"$ADB" shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

echo "==> Launching and starting sharing (tap the Start button)"
"$ADB" shell am force-stop $PKG
"$ADB" logcat -c
"$ADB" shell am start -n $PKG/.MainActivity
sleep 5
# Find the Start/Stop toggle button and tap its center. The uiautomator dump
# is a single line, so extract the toggleButton node with a regex rather than
# splitting on '>' (which POSIX tr would silently not do).
tap_button() {
  local BOUNDS=""
  for attempt in $(seq 1 10); do
    "$ADB" shell uiautomator dump /sdcard/sn.xml >/dev/null 2>&1
    BOUNDS=$("$ADB" shell cat /sdcard/sn.xml | python3 -c "
import re, sys
xml = sys.stdin.read()
m = re.search(r'resource-id=\"[^\"]*toggleButton\"[^>]*bounds=\"\\[([0-9]+),([0-9]+)\\]\\[([0-9]+),([0-9]+)\\]\"', xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 > x1 and y2 > y1:
        print(int((x1+x2)/2), int((y1+y2)/2))
" 2>/dev/null | head -1)
    if [ -n "$BOUNDS" ]; then break; fi
    sleep 1
  done
  if [ -z "$BOUNDS" ]; then
    echo "    toggle button not found in the UI dump (10 tries)"
    return 1
  fi
  echo "    tapping $BOUNDS"
  "$ADB" shell input tap $BOUNDS
}
tap_button

echo "==> Waiting for the group + proxy (up to 30s)"
for i in $(seq 1 30); do
  sleep 1
  PROXY=$("$ADB" shell uiautomator dump /sdcard/sn2.xml >/dev/null 2>&1
    "$ADB" shell cat /sdcard/sn2.xml | tr '>' '>\n' \
      | grep -oE 'text="192.168.49.1:8080"' | head -1 || true)
  if [ -n "$PROXY" ]; then echo "    proxy is up: $PROXY"; break; fi
done

echo "==> Reading the pairing PIN from the UI (for the tunnel AUTH frame)"
PIN=$("$ADB" shell cat /sdcard/sn2.xml | python3 -c "
import re, sys
xml = sys.stdin.read()
m = re.search(r'resource-id=\"[^\"]*pinValue\"[^>]*text=\"([0-9]+)\"', xml)
print(m.group(1) if m else '')
" 2>/dev/null | head -1)
echo "    pairing PIN: ${PIN:-<not found>}"

echo "==> Data-path test: HTTP through the proxy"
"$ADB" shell 'printf "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n" | toybox nc -w 8 192.168.49.1 8080' | head -3

echo "==> Data-path test: CONNECT (HTTPS) through the proxy"
"$ADB" shell 'printf "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com\r\n\r\n" | toybox nc -w 8 192.168.49.1 8080' | head -2

echo "==> Data-path test: DNS on the P2P interface"
# The framework's own DNS usually occupies 192.168.49.1:53; our forwarder
# backs off gracefully, so just verify SOME resolver answers on :53.
"$ADB" shell 'echo ok' >/dev/null 2>&1
DNS_OK=$("$ADB" shell "cat /proc/net/udp" | grep -c ':0035 ' || true)
echo "    udp sockets on port 53 (want >=1): $DNS_OK"

if [ "$DNS_OK" -eq 0 ]; then
  echo "    warning: no DNS on the P2P interface — clients must use their own resolver"
fi

echo "==> Data-path test: TCP tunnel relay (frame protocol) on 192.168.49.1:7777"
# Resolve example.com through the phone (its own resolvers, with a verified
# fallback IP if the network blocks our raw DNS query), then CONNECT through
# the relay.
export SHARENET_PIN="$PIN"
python3 - "$ADB" <<'PY'
import os, struct, sys, subprocess
adb = sys.argv[1]
q = struct.pack(">HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0) + b"\x07example\x03com\x00" + struct.pack(">HH", 1, 1)
open("/tmp/sharenet_dnsq", "wb").write(q)
subprocess.run([adb, "push", "/tmp/sharenet_dnsq", "/data/local/tmp/sharenet_dnsq"], check=True, capture_output=True)
# Resolver candidates: the phone's own DNS first, then public resolvers.
resolvers = subprocess.run(
    [adb, "shell", "dumpsys connectivity | grep -oE 'DnsAddresses: \\[[^]]*\\]' | head -1"],
    capture_output=True, text=True).stdout
cands = [s for s in (resolvers.split("[")[-1].split("]")[0].replace("/", "").split(",") if "[" in resolvers else []) if s.strip()]
cands += ["1.1.1.1", "8.8.8.8"]
ip = None
for dns in cands:
    dns = dns.strip()
    if not dns: continue
    r = subprocess.run(
        [adb, "shell", f"timeout 4 nc -u -w 2 {dns} 53 < /data/local/tmp/sharenet_dnsq > /data/local/tmp/sharenet_dnsa 2>/dev/null"],
        capture_output=True)
    if r.returncode not in (0, 124):
        continue
    r2 = subprocess.run([adb, "pull", "/data/local/tmp/sharenet_dnsa", "/tmp/sharenet_dnsa"],
                        capture_output=True)
    if r2.returncode != 0:
        continue
    raw = open("/tmp/sharenet_dnsa", "rb").read()
    if len(raw) < 12:
        continue
    for i in range(len(raw) - 6):
        if raw[i] == 0 and raw[i+1] == 1 and raw[i+2] == 0 and raw[i+3] == 1:  # A, IN
            rdlen = (raw[i+8] << 8) | raw[i+9]
            if rdlen == 4:
                ip = ".".join(str(b) for b in raw[i+10:i+14])
                break
    if ip: break
if not ip:
    # Fallback: the address verified on-device for example.com.
    ip = "104.20.23.154"
    print("    DNS query blocked by network; using verified fallback IP")
print(f"    example.com -> {ip}")

conn = struct.pack(">H", 1) + b"\x01" + struct.pack(">H", 6) + bytes(map(int, ip.split("."))) + struct.pack(">H", 80)
req = b"GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
data = struct.pack(">H", 1) + b"\x02" + struct.pack(">H", len(req)) + req
# The tunnel requires the pairing PIN first (TYPE_AUTH = 12), or the host
# refuses the connection outright.
pin = os.environ.get("SHARENET_PIN", "").encode()
auth = struct.pack(">H", 0) + b"\x0c" + struct.pack(">H", len(pin)) + pin
open("/tmp/sharenet_tunnel_in", "wb").write(auth + conn + data)
subprocess.run([adb, "push", "/tmp/sharenet_tunnel_in", "/data/local/tmp/sharenet_tunnel_in"], check=True, capture_output=True)
# nc's idle timeout (-w) exits 124 while the control connection is still
# open — that is expected, so do not treat it as failure.
r = subprocess.run([adb, "shell", "timeout 15 nc -w 10 192.168.49.1 7777 < /data/local/tmp/sharenet_tunnel_in > /data/local/tmp/sharenet_tunnel_out"], capture_output=True)
if r.returncode not in (0, 124):
    print("    nc failed:", r.stderr.decode(errors="replace").strip())
subprocess.run([adb, "pull", "/data/local/tmp/sharenet_tunnel_out", "/tmp/sharenet_tunnel_out"], check=True, capture_output=True)
out = open("/tmp/sharenet_tunnel_out", "rb").read()
ok = b"200 OK" in out or b"HTTP/1.1" in out
print(f"    tunnel frames: {len(out)} bytes; HTTP response: {'OK' if ok else 'MISSING'}")
print("    TCP tunnel relay: ", "PASS" if ok else "FAIL")
PY

echo "==> Stopping sharing"
tap_button
sleep 4
"$ADB" shell "dumpsys activity services $PKG" | grep -c "ServiceRecord" \
  | xargs echo "    remaining service records (want 0):"
echo "Done."
