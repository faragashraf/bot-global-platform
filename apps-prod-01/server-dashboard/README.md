# Server Dashboard

Standalone, unauthenticated server dashboard for a single Docker host.

It is intentionally not coupled to Bot Global backend code. The dashboard
serves a static HTML page and a small metrics sidecar writes `status.json`
from host `/proc`, disk usage, and Docker container stats.

## Local Structure

- `docker-compose.yml` starts the dashboard web server and metrics sidecar.
- `www/index.html` is the dashboard UI.
- `metrics/metrics.sh` generates `www/status.json` every five seconds.
- `Caddyfile` serves the static dashboard.

## Run

```bash
docker compose up -d
```

Open:

```text
http://SERVER_IP:18081
```

## Security Note

This first version has no identity by design. Keep it on a temporary port,
behind a firewall, or behind access control before exposing deeper host
details publicly.
