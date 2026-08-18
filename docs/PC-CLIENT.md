# PC / Linux Client Connection Guide

ShareNet creates a Wi-Fi Direct hotspot that any WiFi-capable device can join.
This guide covers connecting a Linux PC (Ubuntu, Fedora, etc.) to the phone's
hotspot and getting internet access through the HTTP proxy.

## Quick Start

### 1. Start ShareNet on the phone

Open the app, tap **Start Sharing**. Note the:
- **SSID** (e.g., `DIRECT-Lz-Galaxy A03s`)
- **Passphrase** (shown on screen)
- **Proxy address** (e.g., `192.168.49.1:8080`)

### 2. Set up NAT on the phone (one-time per session)

On the phone, run via adb (or use a terminal emulator app with root):

```bash
adb shell sh /sdcard/setup-p2p-nat.sh
```

This enables internet forwarding from the P2P hotspot to the phone's
Wi-Fi/cellular connection. Without this, clients can connect but can't
reach the internet.

### 3. Connect the PC to the hotspot

**Using NetworkManager CLI:**

```bash
# Disconnect from current WiFi (optional, prevents auto-switch back)
nmcli device disconnect wlp3s0

# Connect to the phone's hotspot
nmcli device wifi connect "DIRECT-Lz-Galaxy A03s" password "YOUR_PASSPHRASE" ifname wlp3s0

# Prevent auto-reconnect to your regular WiFi
nmcli connection modify "YOUR_REGULAR_WIFI" connection.autoconnect no
```

**Using the GUI:**

1. Click the WiFi icon in the system tray
2. Select `DIRECT-Lz-Galaxy A03s` from the list
3. Enter the passphrase
4. Wait for "Connected"

### 4. Configure the HTTP proxy

The phone shares internet through an HTTP proxy. You must configure your
system or browser to use it.

**System-wide (GNOME):**

```bash
gsettings set org.gnome.system.proxy mode 'manual'
gsettings set org.gnome.system.proxy.http host '192.168.49.1'
gsettings set org.gnome.system.proxy.http port 8080
gsettings set org.gnome.system.proxy.https host '192.168.49.1'
gsettings set org.gnome.system.proxy.https port 8080
gsettings set org.gnome.system.proxy ignore-hosts "localhost,127.0.0.1,192.168.49.*"
```

**Firefox (independent of system proxy):**

1. Settings → General → Network Settings
2. Select **Manual proxy configuration**
3. HTTP Proxy: `192.168.49.1` / Port: `8080`
4. ☑ Also use for HTTPS

**Command-line tools:**

```bash
export http_proxy=http://192.168.49.1:8080
export https_proxy=http://192.168.49.1:8080
curl http://example.com
```

### 5. Verify

```bash
# Test connectivity to the phone
ping -c 3 192.168.49.1

# Test the proxy
curl --proxy http://192.168.49.1:8080 http://example.com

# Test DNS (should resolve through the phone)
nslookup example.com 192.168.49.1
```

## Troubleshooting

### "Connected but no internet"

1. **NAT not set up:** Run `setup-p2p-nat.sh` on the phone
2. **Proxy not configured:** Set the HTTP/HTTPS proxy (step 4 above)
3. **Android captive portal warning:** Normal — the phone can't probe
   internet without the proxy. Ignore the warning.

### Can't find the DIRECT network

- Make sure ShareNet is running on the phone
- The hotspot appears as a regular WiFi network (WPA2)
- Try scanning: `nmcli device wifi list ifname wlp3s0`

### PC gets APIPA address (169.254.x.x)

The phone's DHCP server didn't assign an IP. Try:

```bash
# Release and renew
sudo dhclient -r wlp3s0
sudo dhclient wlp3s0

# Or set a static IP
sudo ip addr add 192.168.49.100/24 dev wlp3s0
sudo ip route add default via 192.168.49.1
```

### DNS not resolving

Set DNS manually:

```bash
# Add phone's DNS resolver
sudo resolvectl dns wlp3s0 192.168.49.1

# Or add to /etc/resolv.conf
echo "nameserver 192.168.49.1" | sudo tee /etc/resolv.conf
```

### Performance is slow

- The phone shares its Wi-Fi upstream through a single radio
- Expect ~0.3–3 Mbit/s depending on signal and upstream quality
- Heavy downloads will be slow; light browsing and messaging work fine

## Network Architecture

```
┌──────────────┐     WiFi Direct      ┌──────────────┐     Wi-Fi      ┌──────────┐
│   Linux PC   │◄────────────────────►│  Android GO  │◄──────────────►│ Internet │
│  192.168.49.x│  P2P (channel 1)     │ 192.168.49.1 │   wlan0        │          │
│              │                      │              │                │          │
│  HTTP Proxy  │──────────────────────│  Proxy :8080 │                │          │
│  192.168.49.1│                      │  NAT/masq    │                │          │
└──────────────┘                      └──────────────┘                └──────────┘
```

- **Layer 2:** PC connects to phone's Wi-Fi Direct hotspot (WPA2)
- **Layer 3:** PC gets IP via DHCP (or static) on 192.168.49.0/24
- **Layer 7:** PC's browser/apps use HTTP proxy at 192.168.49.1:8080
- **NAT:** Phone forwards traffic from P2P to upstream (Wi-Fi/cellular)
