#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

docker compose pull backend
docker compose up -d --no-build backend caddy
docker image prune -f >/dev/null

docker compose ps
