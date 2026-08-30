#!/usr/bin/env bash

set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="$ROOT_DIR/frontend"
BACKEND_PROJECT="$ROOT_DIR/backend/src/BotGlobal.Api/BotGlobal.Api.csproj"

frontend_pid=""
backend_pid=""

cleanup() {
  trap - INT TERM EXIT

  if [[ -n "$frontend_pid" ]] && kill -0 "$frontend_pid" 2>/dev/null; then
    kill "$frontend_pid" 2>/dev/null || true
  fi

  if [[ -n "$backend_pid" ]] && kill -0 "$backend_pid" 2>/dev/null; then
    kill "$backend_pid" 2>/dev/null || true
  fi

  wait "$frontend_pid" 2>/dev/null || true
  wait "$backend_pid" 2>/dev/null || true
}

trap cleanup INT TERM EXIT

command -v node >/dev/null 2>&1 || { echo "node is required" >&2; exit 1; }
command -v npm >/dev/null 2>&1 || { echo "npm is required" >&2; exit 1; }
command -v dotnet >/dev/null 2>&1 || { echo "dotnet is required" >&2; exit 1; }

if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  echo "Installing frontend dependencies..."
  npm --prefix "$FRONTEND_DIR" ci
fi

echo "Starting backend at http://localhost:5062"
dotnet run --project "$BACKEND_PROJECT" --launch-profile http &
backend_pid=$!

echo "Starting frontend at http://localhost:4200"
npm --prefix "$FRONTEND_DIR" start -- --host localhost --port 4200 &
frontend_pid=$!

echo "Frontend and backend are running. Press Ctrl+C to stop both."

while kill -0 "$backend_pid" 2>/dev/null && kill -0 "$frontend_pid" 2>/dev/null; do
  sleep 1
done

if ! kill -0 "$backend_pid" 2>/dev/null; then
  echo "Backend stopped; stopping frontend." >&2
else
  echo "Frontend stopped; stopping backend." >&2
fi