# Lamma TURN deployment

This package runs Coturn on a public Linux host with a stable public IP. It supports TURN over UDP and TCP on 3478, TURN over TLS/TCP on 443, and UDP/TCP relay allocations on 49160–49200. It is not suitable for shared web hosting such as SmarterASP.

## Host prerequisites

- A DNS A/AAAA record such as `voice.botglobalservice.com` resolving to the host.
- Docker Engine with Compose v2 on a dedicated or compatible Linux VPS.
- A valid TLS certificate available read-only to the container.
- Inbound firewall rules for `3478/udp`, `3478/tcp`, `443/tcp`, and `49160:49200/udp`; allow the relay TCP range too when TCP relay allocations are required.
- Outbound UDP and TCP internet access.

## Secure configuration

Create `.env` from `.env.example`, replace the documentation-only IP, and generate the REST secret with a cryptographically secure generator such as `openssl rand -base64 48`. Never put the secret in source control.

Render the runtime configuration on the host:

```sh
set -a
. ./.env
set +a
mkdir -p runtime
envsubst < turnserver.conf.template > runtime/turnserver.conf
chmod 600 runtime/turnserver.conf
docker compose config
docker compose up -d
```

Set the backend environment only after Coturn is reachable:

```text
Games__Voice__Ice__TurnUrls__0=turn:voice.botglobalservice.com:3478?transport=udp
Games__Voice__Ice__TurnUrls__1=turn:voice.botglobalservice.com:3478?transport=tcp
Games__Voice__Ice__TurnUrls__2=turns:voice.botglobalservice.com:443?transport=tcp
Games__Voice__Ice__TurnRestSecret=<same REST secret, server-side only>
Games__Voice__Ice__CredentialLifetimeMinutes=60
```

Restart/redeploy the backend after setting those values. The mobile app receives only time-limited HMAC credentials.

## Verification

Check container health and listeners with `docker compose ps`, `docker compose logs --tail=100 coturn`, and `ss -lntup | grep -E ':3478|:443'`. From a separate network, use Coturn's `turnutils_uclient` with a temporary REST username/password generated from the same secret. Finally build Lamma Debug with `-PfamilyGamesDebugVoiceIcePolicy=relay` and prove a selected `relay` candidate pair plus bidirectional RTP stats. A successful STUN binding test alone is not TURN proof.
