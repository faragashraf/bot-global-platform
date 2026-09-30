# NQRB Cloudflare Realtime TURN

NQRB cross-network calling can use Cloudflare Realtime TURN for per-request ephemeral ICE servers. Keep Cloudflare disabled until the account key and API token are provisioned through the approved secret mechanism.

Configure only server-side values under `Calling:Ice:Cloudflare`:

```json
{
  "Enabled": true,
  "KeyId": "<cloudflare-turn-key-id>",
  "ApiToken": "<secret value from approved secret store>",
  "CredentialLifetimeSeconds": 3600,
  "RequestTimeoutSeconds": 5
}
```

When enabled, the Calling hub requests temporary ICE servers from:

`POST https://rtc.live.cloudflare.com/v1/turn/keys/{keyId}/credentials/generate-ice-servers`

with a bearer API token and JSON body `{"ttl": seconds}`. The key ID and API token stay on the backend. Clients receive only the existing calling ICE response shape: `servers` with `urls`, `username`, `credential`, plus `expiresAtUtc`.

Failure behavior is fail-closed. If Cloudflare is enabled but unavailable, invalid, or returns an unusable ICE response, the hub returns the generic `calling_ice_unavailable` error and does not fall back to STUN-only credentials for that call.

The hub only returns temporary ICE credentials to live call participants. Callers may request ICE while the call is still ringing so mobile setup can occur before `JoinCall`; callees may request ICE only after answering. Ended, rejected, expired, or unauthorized call IDs fail before credentials are returned.

Live Cloudflare verification, app deployment, and production call testing require separate scoped approval.
