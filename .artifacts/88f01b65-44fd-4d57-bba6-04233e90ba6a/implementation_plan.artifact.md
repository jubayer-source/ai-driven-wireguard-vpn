# VPN Handshake and Internet Restoration Plan (Final Iteration)

The goal is to fix the "Handshake Failed" loop and ensure internet connectivity works perfectly when the VPN is active.

## User Review Required

> [!IMPORTANT]
> **Why it fails now:** The app is currently "excluded" from the VPN. This means the app cannot reach `10.0.0.1` or see the "VPN IP". I will re-include the app but fix the "No Internet" issue properly this time.

## Proposed Changes

### 1. Re-include App and Fix DNS (`MyVpnService.java`)
- **Action:** Remove `.excludeApplication(getPackageName())`.
- **Action:** Add `8.8.8.8` and `1.1.1.1` as DNS servers.
- **Action:** Increase MTU to `1420` (default) since we are on a hotspot (Wi-Fi) which handles larger packets better than cellular.

### 2. Reliable Success Detection (`MainActivity.java`)
- **Action:** Update the success check to fetch the public IP *through* the tunnel.
- **Action:** If the first IP check fails, it will wait 2 more seconds for the handshake to finish before giving up.

### 3. Server NAT Check
- **Action:** I will provide a final "Clean NAT" command for your Ubuntu server to ensure no old rules are blocking the traffic.

## Verification Plan
1. **Hotspot:** Connect and verify "VPN IP" matches your server.
2. **Internet:** Open a browser and verify you can load Google.
