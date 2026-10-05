#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

compose=(docker compose)
docker_cmd=(docker)
if ! docker info >/dev/null 2>&1; then
  compose=(sudo -n docker compose)
  docker_cmd=(sudo -n docker)
fi

"${compose[@]}" pull backend
"${compose[@]}" up -d --no-build backend caddy
"${docker_cmd[@]}" image prune -f >/dev/null

"${compose[@]}" ps
