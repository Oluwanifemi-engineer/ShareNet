#!/bin/bash
# setup-p2p-nat.sh — Configure NAT/masquerade on the phone's P2P interface
#
# This enables internet sharing for ANY client (Android, PC, etc.) that
# connects to the ShareNet Wi-Fi Direct hotspot. Without this, clients
# get an IP but can't reach the internet because there's no NAT.
#
# Run via adb:
#   adb shell sh /sdcard/setup-p2p-nat.sh
#
# Or push and run:
#   adb push scripts/setup-p2p-nat.sh /sdcard/
#   adb shell sh /sdcard/setup-p2p-nat.sh

set -euo pipefail

P2P_IF="p2p0"
P2P_NET="192.168.49.0/24"

# Find the upstream interface (the one with the default route)
UPSTREAM_IF=$(ip route show default | awk '{print $5}' | head -1)

if [ -z "$UPSTREAM_IF" ]; then
    echo "ERROR: No default route found. Is Wi-Fi or cellular connected?"
    exit 1
fi

echo "P2P interface:  $P2P_IF (192.168.49.1)"
echo "Upstream interface: $UPSTREAM_IF"
echo ""

# 1. Enable IP forwarding (should already be 1, but ensure it)
echo "[1/4] Enabling IP forwarding..."
echo 1 > /proc/sys/net/ipv4/ip_forward
echo "  ip_forward = $(cat /proc/sys/net/ipv4/ip_forward)"

# 2. Enable masquerade on upstream interface (outbound traffic)
echo "[2/4] Adding NAT masquerade on $UPSTREAM_IF..."
iptables -t nat -A POSTROUTING -s "$P2P_NET" -o "$UPSTREAM_IF" -j MASQUERADE 2>/dev/null || \
    echo "  WARNING: iptables MASQUERADE failed (may need root)"

# 3. Allow forwarding from P2P to upstream
echo "[3/4] Adding forward rules..."
iptables -A FORWARD -i "$P2P_IF" -o "$UPSTREAM_IF" -j ACCEPT 2>/dev/null || \
    echo "  WARNING: iptables FORWARD in failed"
iptables -A FORWARD -i "$UPSTREAM_IF" -o "$P2P_IF" -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || \
    echo "  WARNING: iptables FORWARD out failed"

# 4. Verify
echo "[4/4] Verifying..."
echo ""
echo "NAT table:"
iptables -t nat -L POSTROUTING -n 2>/dev/null | grep -E "MASQ|$P2P_IF" || echo "  (no rules visible)"
echo ""
echo "Forward rules:"
iptables -L FORWARD -n 2>/dev/null | grep -E "$P2P_IF|$UPSTREAM_IF" || echo "  (no rules visible)"
echo ""
echo "IP forwarding: $(cat /proc/sys/net/ipv4/ip_forward)"
echo ""
echo "Done. Clients on the P2P network should now have internet access."
echo "Note: This setup is lost on reboot. Re-run after each reboot."
