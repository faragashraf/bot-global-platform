#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROFILE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/sentricam-first-run.XXXXXX")"
HUB_PORT="${SENTRICAM_FRESH_PORT:-5173}"

echo "Starting an isolated SentriCam first-run profile."
echo "Profile: ${PROFILE_ROOT}"
echo "Dashboard: http://127.0.0.1:${HUB_PORT}"
echo "The profile is retained for restart verification and does not touch existing Hub data."
echo "Restart command after setup:"
echo "ASPNETCORE_ENVIRONMENT=Development SentriCam__Home='${PROFILE_ROOT}' dotnet run --project '${PROJECT_ROOT}/server/src/SentriCam.Api/SentriCam.Api.csproj' --no-launch-profile -- --urls 'http://127.0.0.1:${HUB_PORT}'"

ASPNETCORE_ENVIRONMENT=Development \
SentriCam__FreshInstall=true \
SentriCam__Home="${PROFILE_ROOT}" \
dotnet run \
  --project "${PROJECT_ROOT}/server/src/SentriCam.Api/SentriCam.Api.csproj" \
  --no-launch-profile \
  -- \
  --urls "http://127.0.0.1:${HUB_PORT}"
