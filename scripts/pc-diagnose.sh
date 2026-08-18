#!/bin/bash
# pc-diagnose.sh — Run this WHILE CONNECTED to the DIRECT-Lz-Galaxy A03s network.
# It saves results to ~/sharenet-diag.txt
# After running, reconnect to NACOS and share the file with the developer.

OUT=~/sharenet-diag.txt
echo "ShareNet PC Diagnostic — $(date)" > "$OUT"
echo "========================================" >> "$OUT"

echo -e "\n=== 1. WiFi connection ===" >> "$OUT"
nmcli -t -f NAME,DEVICE connection show --active 2>/dev/null | grep wlp3s0 >> "$OUT"
iw dev wlp3s0 link 2>/dev/null | head -10 >> "$OUT"

echo -e "\n=== 2. IP address ===" >> "$OUT"
ip addr show wlp3s0 2>/dev/null | grep inet >> "$OUT"

echo -e "\n=== 3. Route to phone ===" >> "$OUT"
ip route show 2>/dev/null | grep 192.168.49 >> "$OUT"

echo -e "\n=== 4. Ping phone ===" >> "$OUT"
ping -c 3 -W 2 192.168.49.1 2>&1 >> "$OUT"

echo -e "\n=== 5. DNS ===" >> "$OUT"
cat /etc/resolv.conf 2>/dev/null | grep nameserver >> "$OUT"

echo -e "\n=== 6. Test proxy (setup page) ===" >> "$OUT"
curl -v --max-time 5 http://192.168.49.1:8080/setup 2>&1 >> "$OUT"

echo -e "\n=== 7. Test proxy (PAC file) ===" >> "$OUT"
curl -v --max-time 5 http://192.168.49.1:8080/proxy.pac 2>&1 >> "$OUT"

echo -e "\n=== 8. Test proxy forwarding ===" >> "$OUT"
curl --proxy http://192.168.49.1:8080 --max-time 5 http://example.com 2>&1 | head -5 >> "$OUT"

echo -e "\n=== 9. ARP table ===" >> "$OUT"
arp -a 2>/dev/null | grep 192.168.49 >> "$OUT"

echo -e "\n=== 10. Current proxy settings ===" >> "$OUT"
echo "HTTP_PROXY: ${http_proxy:-not set}" >> "$OUT"
echo "HTTPS_PROXY: ${https_proxy:-not set}" >> "$OUT"
gsettings get org.gnome.system.proxy mode 2>/dev/null >> "$OUT"

echo "" >> "$OUT"
echo "========================================" >> "$OUT"
echo "DONE. File saved to $OUT" >> "$OUT"
echo "========================================" >> "$OUT"

echo "Done! Results saved to $OUT"
echo "Now reconnect to NACOS and share the file:"
echo "  cat ~/sharenet-diag.txt"
