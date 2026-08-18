#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# ShareNet PC Transparent Tunnel
#
# Routes ALL PC traffic (HTTP, chat apps, games, everything) through the
# phone's shared internet connection — no per-app proxy configuration needed.
#
# Requirements: Linux with sudo, redsocks, iptables
#
# Usage:
#   bash setup-transparent-tunnel.sh [socks5_host] [socks5_port]
#
# Default: 192.168.49.1:1080 (phone's SOCKS5 proxy on the P2P network)
#
# To undo:
#   bash setup-transparent-tunnel.sh --stop
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SOCKS5_HOST="${1:-192.168.49.1}"
SOCKS5_PORT="${2:-1080}"
REDSOCKS_PORT=12345
REDSOCKS_CONF="/etc/redsocks.conf"
IPTABLES_CHAIN="sharenet"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}[✓]${NC} $*"; }
warn() { echo -e "${YELLOW}[!]${NC} $*"; }
err()  { echo -e "${RED}[✗]${NC} $*" >&2; }
info() { echo -e "${CYAN}[i]${NC} $*"; }

# ── Stop mode ───────────────────────────────────────────────────────────────

if [[ "${1:-}" == "--stop" ]]; then
    info "Stopping ShareNet transparent tunnel..."
    sudo iptables -t nat -F "$IPTABLES_CHAIN" 2>/dev/null || true
    sudo iptables -t nat -D PREROUTING -j "$IPTABLES_CHAIN" 2>/dev/null || true
    sudo iptables -t nat -F "$IPTABLES_CHAIN" 2>/dev/null || true
    sudo iptables -t nat -X "$IPTABLES_CHAIN" 2>/dev/null || true

    # Also handle OUTPUT chain for locally-generated traffic
    sudo iptables -t nat -F "${IPTABLES_CHAIN}-out" 2>/dev/null || true
    sudo iptables -t nat -D OUTPUT -j "${IPTABLES_CHAIN}-out" 2>/dev/null || true
    sudo iptables -t nat -F "${IPTABLES_CHAIN}-out" 2>/dev/null || true
    sudo iptables -t nat -X "${IPTABLES_CHAIN}-out" 2>/dev/null || true

    sudo systemctl stop redsocks 2>/dev/null || true
    log "ShareNet transparent tunnel stopped."
    exit 0
fi

# ── Pre-flight checks ───────────────────────────────────────────────────────

if [[ $EUID -ne 0 ]]; then
    err "This script must be run with sudo."
    exit 1
fi

info "Setting up ShareNet transparent tunnel..."
info "SOCKS5 proxy: $SOCKS5_HOST:$SOCKS5_PORT"

# Check if we can reach the SOCKS5 proxy
if ! nc -zw2 "$SOCKS5_HOST" "$SOCKS5_PORT" 2>/dev/null; then
    err "Cannot reach SOCKS5 proxy at $SOCKS5_HOST:$SOCKS5_PORT"
    err "Make sure you're connected to the ShareNet Wi-Fi Direct network."
    exit 1
fi
log "SOCKS5 proxy is reachable."

# ── Install redsocks if needed ───────────────────────────────────────────────

if ! command -v redsocks &>/dev/null; then
    warn "redsocks not found. Installing..."
    if command -v apt &>/dev/null; then
        apt update -qq && apt install -y -qq redsocks
    elif command -v pacman &>/dev/null; then
        pacman -S --noconfirm redsocks
    elif command -v dnf &>/dev/null; then
        dnf install -y redsocks
    else
        err "Cannot auto-install redsocks. Please install it manually."
        exit 1
    fi
    log "redsocks installed."
fi

# ── Configure redsocks ──────────────────────────────────────────────────────

cat > "$REDSOCKS_CONF" << EOF
base {
    log_debug = off;
    log_info = on;
    log = "syslog:daemon";
    daemon = on;
    user = redsocks;
    group = redsocks;
    redirector = iptables;
}

redsocks {
    local_ip = 0.0.0.0;
    local_port = $REDSOCKS_PORT;
    ip = $SOCKS5_HOST;
    port = $SOCKS5_PORT;
    type = socks5;
    timeout = 30;
}
EOF
log "redsocks configured."

# ── Start redsocks ──────────────────────────────────────────────────────────

# Kill any running redsocks
pkill redsocks 2>/dev/null || true
sleep 1

# Ensure redsocks user exists (some distros need it)
id -u redsocks &>/dev/null || useradd --system --no-create-home redsocks 2>/dev/null || true

redsocks -c "$REDSOCKS_CONF"
sleep 1

if pgrep redsocks >/dev/null; then
    log "redsocks started on port $REDSOCKS_PORT"
else
    err "redsocks failed to start. Check logs."
    exit 1
fi

# ── Set up iptables rules ──────────────────────────────────────────────────

# Create chains
sudo iptables -t nat -N "$IPTABLES_CHAIN" 2>/dev/null || sudo iptables -t nat -F "$IPTABLES_CHAIN"
sudo iptables -t nat -N "${IPTABLES_CHAIN}-out" 2>/dev/null || sudo iptables -t nat -F "${IPTABLES_CHAIN}-out"

# Skip traffic that shouldn't go through the tunnel
# - Localhost
sudo iptables -t nat -A "$IPTABLES_CHAIN" -d 127.0.0.0/8 -j RETURN
sudo iptables -t nat -A "${IPTABLES_CHAIN}-out" -d 127.0.0.0/8 -j RETURN
# - The phone itself (P2P gateway)
sudo iptables -t nat -A "$IPTABLES_CHAIN" -d "$SOCKS5_HOST" -j RETURN
sudo iptables -t nat -A "${IPTABLES_CHAIN}-out" -d "$SOCKS5_HOST" -j RETURN
# - Local network (the P2P subnet itself)
sudo iptables -t nat -A "$IPTABLES_CHAIN" -d 192.168.49.0/24 -j RETURN
sudo iptables -t nat -A "${IPTABLES_CHAIN}-out" -d 192.168.49.0/24 -j RETURN
# - VPN traffic (don't double-tunnel)
sudo iptables -t nat -A "$IPTABLES_CHAIN" -i tun0 -j RETURN 2>/dev/null || true

# Redirect all TCP traffic to redsocks
sudo iptables -t nat -A "$IPTABLES_CHAIN" -p tcp -j REDIRECT --to-ports "$REDSOCKS_PORT"
sudo iptables -t nat -A "${IPTABLES_CHAIN}-out" -p tcp -j REDIRECT --to-ports "$REDSOCKS_PORT"

# Hook into PREROUTING (for forwarded traffic) and OUTPUT (for local apps)
sudo iptables -t nat -A PREROUTING -j "$IPTABLES_CHAIN"
sudo iptables -t nat -A OUTPUT -j "${IPTABLES_CHAIN}-out"

log "iptables rules configured."

# ── Summary ─────────────────────────────────────────────────────────────────

echo ""
echo -e "${GREEN}════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  ShareNet Transparent Tunnel — Active!${NC}"
echo -e "${GREEN}════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "  SOCKS5 proxy:    ${CYAN}$SOCKS5_HOST:$SOCKS5_PORT${NC}"
echo -e "  redsocks port:   ${CYAN}$REDSOCKS_PORT${NC}"
echo -e "  Traffic:         ${CYAN}ALL TCP (HTTP, chat, games, everything)${NC}"
echo ""
echo -e "  ${YELLOW}To stop:${NC}  bash $0 --stop"
echo ""
