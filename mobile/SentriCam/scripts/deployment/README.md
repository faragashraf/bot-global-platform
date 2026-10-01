# Deployment scripts

Production defaults must remain secure, and secrets must never be stored here.

## macOS LAN HTTPS

`macos/install-caddy-launchd-integration.sh` installs a reusable launchd wrapper and
Caddyfile template into an existing SentriCam runtime. The wrapper asks the published
Hub binary for `--print-advertised-hub-url`, so QR pairing continues to prefer the
dynamically discovered LAN IPv4 address for Android compatibility. On macOS, the wrapper
also reads the current Bonjour `LocalHostName` with `scutil` and adds its normalized
`.local` HTTPS identity to the same Caddy site. Neither identity is hardcoded.

If Bonjour identity discovery is unavailable or invalid, rendering safely continues with
the advertised LAN identity only. The wrapper renders and validates the machine-local
Caddyfile before every Caddy start, preserving Caddy's existing data directory and local
CA. Both identities share the same React, health, API, SignalR/WebSocket, and SPA routes.

After publishing the Hub into the runtime, install or update the integration with:

```sh
scripts/deployment/macos/install-caddy-launchd-integration.sh \
  --app-root /path/to/SentriCam \
  --activate
```

The runtime Caddyfile, launchd plist, certificates, keys, data, and backups are local
deployment artifacts and must not be committed.

### Full-tunnel VPNs

A full-tunnel VPN can route private LAN traffic away from a LAN-only Hub even when the
Hub and device are on the same network. Disable the VPN while using the local Hub, or
configure the VPN to allow/bypass private LAN traffic. SentriCam does not circumvent the
device's VPN routing policy or weaken HTTPS to compensate for it.
