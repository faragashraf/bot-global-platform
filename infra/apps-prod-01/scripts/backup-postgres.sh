#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

compose=(docker compose)
if ! docker info >/dev/null 2>&1; then
  compose=(sudo -n docker compose)
fi

backup_root="${BACKUP_ROOT:-/home/deploy/backups/bot-global-platform/postgres}"
retention_days="${RETENTION_DAYS:-14}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive="$backup_root/botglobal-postgres-$timestamp.dumpall.sql.gz"
tmp="$archive.tmp"

umask 077
mkdir -p "$backup_root"

"${compose[@]}" exec -T postgres sh -lc 'pg_dumpall -U "$POSTGRES_USER"' \
  | gzip -9 > "$tmp"

mv "$tmp" "$archive"
sha256sum "$archive" > "$archive.sha256"

find "$backup_root" -type f \( -name '*.dumpall.sql.gz' -o -name '*.dumpall.sql.gz.sha256' \) \
  -mtime +"$retention_days" -delete

printf 'PostgreSQL backup written: %s\n' "$archive"
