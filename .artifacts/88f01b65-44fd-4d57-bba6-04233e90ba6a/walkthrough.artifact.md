# VPN Fix Walkthrough (FINAL SYNC)

Your server was not working because the `wg0` interface was down and the keys didn't match. I have synchronized the keys between the app and the server.

## 1. UPDATED APP
The app now has a fresh set of cryptographic keys. Deploy the app to your phone now.

## 2. SERVER CONFIG (Paste this on Ubuntu)
You must reset your server configuration to match the new keys.

### Step A: Create Config
Run this EXACT command to overwrite your server config:
```bash
sudo tee /etc/wireguard/wg0.conf <<EOF
[Interface]
PrivateKey = KOoJgFay262F3DESeobEO/ORAbt2zgAHbU9v8n8ZUlc=
Address = 10.0.0.1/24
ListenPort = 443

[Peer]
# Client (Phone)
PublicKey = 8dCZN1L+Vla1JCAroWFNuIvkw1zfr16pUX0eCsZ+Ohc=
AllowedIPs = 10.0.0.2/32
EOF
```

### Step B: Restart WireGuard
```bash
sudo wg-quick down wg0
sudo wg-quick up wg0
```

### Step C: Verify Server is Listening
Run `sudo wg show`. It MUST show:
- `interface: wg0`
- `listening port: 443`
- `peer: 8dCZN...`

### Step D: NAT (Ensure this is active)
```bash
sudo sysctl -w net.ipv4.ip_forward=1
sudo iptables -t nat -A POSTROUTING -s 10.0.0.0/24 -o wlp2s0 -j MASQUERADE
```

## 3. Test on Phone
Once `sudo wg show` is working on Ubuntu, open the app on your phone (using Airtel) and connect. It should resolve the IP immediately.
