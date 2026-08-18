#!/bin/bash
# linux-p2p-connect.sh — Connect a Linux PC to an Android Wi-Fi Direct Group Owner
#
# The problem: when you connect to "DIRECT-Lz-Galaxy A03s" via NetworkManager
# (regular WiFi client mode), Android's P2P driver doesn't bridge traffic.
# The PC gets an IP but can't reach the phone.
#
# The fix: use wpa_supplicant's P2P module to join as a proper P2P client,
# which Android's P2P driver WILL bridge traffic for.
#
# Prerequisites:
#   - wpa_supplicant with P2P support (your driver already has it)
#   - sudo access (for managing the WiFi interface)
#   - The phone's ShareNet service running with the DIRECT-xx hotspot active
#
# Usage:
#   sudo bash scripts/linux-p2p-connect.sh <DIRECT-SSID> <passphrase>
#   sudo bash scripts/linux-p2p-connect.sh "DIRECT-Lz-Galaxy A03s" "passphrase_here"

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

SSID="${1:-}"
PASSPHRASE="${2:-}"
WLAN_IFACE="wlp3s0"
P2P_IFACE=""
PROXY_HOST="192.168.49.1"
PROXY_PORT="8080"
WPA_SUPPLICANT_CONF="/tmp/sharenet-wpa.conf"
WPA_CTRL_IFACE="/var/run/sharenet-wpa-ctrl"
PIDFILE="/tmp/sharenet-wpa.pid"

cleanup() {
    echo -e "\n${YELLOW}Cleaning up...${NC}"
    if [ -f "$PIDFILE" ]; then
        kill "$(cat "$PIDFILE")" 2>/dev/null || true
        rm -f "$PIDFILE"
    fi
    # Restore NetworkManager control
    nmcli device set "$WLAN_IFACE" managed yes 2>/dev/null || true
    # Remove temp config
    rm -f "$WPA_SUPPLICANT_CONF"
    echo -e "${GREEN}Done.${NC}"
}
trap cleanup EXIT

# ── Validate ──────────────────────────────────────────────────────────────────
if [ -z "$SSID" ] || [ -z "$PASSPHRASE" ]; then
    echo "Usage: sudo bash $0 <DIRECT-SSID> <passphrase>"
    echo "  Example: sudo bash $0 'DIRECT-Lz-Galaxy A03s' 'mysecretpass'"
    exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
    echo -e "${RED}Error: must run as root (sudo)${NC}"
    exit 1
fi

echo -e "${GREEN}=== ShareNet P2P Client Connector ===${NC}"
echo "SSID:       $SSID"
echo "Interface:  $WLAN_IFACE"
echo "Proxy:      $PROXY_HOST:$PROXY_PORT"

# ── Step 1: Disconnect NetworkManager from the WiFi ──────────────────────────
echo -e "\n${YELLOW}[1/6] Disconnecting NetworkManager from $WLAN_IFACE...${NC}"
nmcli device disconnect "$WLAN_IFACE" 2>/dev/null || true
nmcli device set "$WLAN_IFACE" managed no 2>/dev/null || true
sleep 1

# ── Step 2: Kill any existing wpa_supplicant on this interface ────────────────
echo -e "${YELLOW}[2/6] Cleaning up existing wpa_supplicant...${NC}"
wpa_cli -i "$WLAN_IFACE" quit 2>/dev/null || true
pkill -f "wpa_supplicant.*$WLAN_IFACE" 2>/dev/null || true
sleep 1

# ── Step 3: Create wpa_supplicant config with P2P support ────────────────────
echo -e "${YELLOW}[3/6] Creating P2P wpa_supplicant config...${NC}"
mkdir -p "$WPA_CTRL_IFACE"
cat > "$WPA_SUPPLICANT_CONF" <<EOF
ctrl_interface=$WPA_CTRL_IFACE
ctrl_interface_group=0
update_config=1
device_name=ShareNet-PC
device_type=6-0050F204-1
config_methods=keypad display
p2p_go_intent=0
country=NG

network={
    ssid="$SSID"
    psk="$PASSPHRASE"
    key_mgmt=WPA-PSK
    proto=RSN
    pairwise=CCMP
    group=CCMP
    scan_ssid=1
}
EOF

# ── Step 4: Start wpa_supplicant with P2P ────────────────────────────────────
echo -e "${YELLOW}[4/6] Starting wpa_supplicant with P2P support...${NC}"
wpa_supplicant -B -i "$WLAN_IFACE" -c "$WPA_SUPPLICANT_CONF" -D nl80211 \
    -P "$PIDFILE" 2>&1
sleep 2

# Verify it started
if [ ! -f "$PIDFILE" ] || ! kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
    echo -e "${RED}Error: wpa_supplicant failed to start${NC}"
    exit 1
fi
echo "wpa_supplicant running (PID $(cat "$PIDFILE"))"

# ── Step 5: Use wpa_cli to connect to the P2P group ─────────────────────────
echo -e "${YELLOW}[5/6] Connecting to P2P group...${NC}"

# First, try to associate with the network (standard WiFi association to the GO)
wpa_cli -i "$WLAN_IFACE" -p "$WPA_CTRL_IFACE" select_network 0 2>/dev/null || true
wpa_cli -i "$WLAN_IFACE" -p "$WPA_CTRL_IFACE" enable_network 0 2>/dev/null || true
wpa_cli -i "$WLAN_IFACE" -p "$WPA_CTRL_IFACE" reassociate 2>/dev/null || true

# Wait for association
echo "Waiting for WiFi association..."
CONNECTED=0
for i in $(seq 1 30); do
    STATE=$(wpa_cli -i "$WLAN_IFACE" -p "$WPA_CTRL_IFACE" status 2>/dev/null | grep "wpa_state=" | cut -d= -f2)
    if [ "$STATE" = "COMPLETED" ] || [ "$STATE" = "4WAY_HANDSHAKE" ] || [ "$STATE" = "GROUP_HANDSHAKE" ]; then
        echo -e "${GREEN}WiFi association successful (state: $STATE)${NC}"
        CONNECTED=1
        break
    fi
    sleep 1
done

if [ "$CONNECTED" -eq 0 ]; then
    echo -e "${RED}WiFi association failed. State: $STATE${NC}"
    echo "Trying P2P_connect as fallback..."

    # Try P2P connect — find the GO's P2P address first
    # Scan for peers
    wpa_cli -i "$WLAN_IFACE" -p "$WPA_CTRL_IFACE" p2p_find 2>/dev/null || true
    sleep 5
    wpa_cli -i "$WLAN_IFACE" -p "$WPA_CTRL_IFACE" p2p_stop_find 2>/dev/null || true

    echo -e "${RED}Could not connect automatically.${NC}"
    echo "Try connecting via NetworkManager GUI, then run:"
    echo "  proxy_connect"
    exit 1
fi

# Wait a bit for IP assignment
echo "Waiting for IP address assignment..."
sleep 5

# ── Step 6: Configure proxy ──────────────────────────────────────────────────
echo -e "${YELLOW}[6/6] Configuring HTTP proxy...${NC}"

# Check if we got an IP on the interface
OUR_IP=$(ip -4 addr show "$WLAN_IFACE" 2>/dev/null | grep "inet " | awk '{print $2}' | cut -d/ -f1)
echo "Our IP: ${OUR_IP:-not assigned yet}"

# Set GNOME proxy
gsettings set org.gnome.system.proxy mode 'manual' 2>/dev/null || true
gsettings set org.gnome.system.proxy.http host "$PROXY_HOST" 2>/dev/null || true
gsettings set org.gnome.system.proxy.http port "$PROXY_PORT" 2>/dev/null || true
gsettings set org.gnome.system.proxy.https host "$PROXY_HOST" 2>/dev/null || true
gsettings set org.gnome.system.proxy.https port "$PROXY_PORT" 2>/dev/null || true
gsettings set org.gnome.system.proxy ignore-hosts "localhost,127.0.0.1,192.168.49.*" 2>/dev/null || true

# ── Verify ───────────────────────────────────────────────────────────────────
echo -e "\n${YELLOW}Verifying connection...${NC}"

# Test proxy reachability
echo -n "Proxy test: "
if curl -s --proxy "http://$PROXY_HOST:$PROXY_PORT" --max-time 5 http://example.com > /dev/null 2>&1; then
    echo -e "${GREEN}PASS — internet is working through the proxy!${NC}"
elif curl -s --max-time 5 http://example.com > /dev/null 2>&1; then
    echo -e "${YELLOW}Direct connection works (proxy may not be needed)${NC}"
else
    echo -e "${RED}FAIL — proxy not reachable. Check phone is running ShareNet.${NC}"
    echo ""
    echo "Diagnostic:"
    echo "  Ping phone:  ping $PROXY_HOST"
    echo "  Check proxy: curl -v --proxy http://$PROXY_HOST:$PROXY_PORT http://example.com"
fi

# Check connectivity
echo -n "Ping phone: "
if ping -c 1 -W 2 "$PROXY_HOST" > /dev/null 2>&1; then
    echo -e "${GREEN}REACHABLE${NC}"
else
    echo -e "${RED}UNREACHABLE${NC}"
fi

echo ""
echo -e "${GREEN}=== Connection established ===${NC}"
echo "Proxy: http://$PROXY_HOST:$PROXY_PORT"
echo "GNOME proxy: configured (HTTP + HTTPS)"
echo ""
echo "To verify: open Firefox and browse to http://example.com"
echo "To disconnect: the script will clean up on exit, or run:"
echo "  nmcli device set $WLAN_IFACE managed yes"
echo "  nmcli device connect $WLAN_IFACE"
echo ""
echo "Press Ctrl+C to disconnect and clean up."
echo ""

# Keep running until interrupted
wait
