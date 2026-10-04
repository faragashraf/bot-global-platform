#!/usr/bin/env sh
set -eu

for database in \
  botglobal_catalog \
  botglobal_identity \
  botglobal_communication \
  botglobal_platform_clients \
  botglobal_pairing \
  botglobal_notifications \
  botglobal_games
do
  psql --username "$POSTGRES_USER" --dbname postgres \
    --command "CREATE DATABASE ${database} OWNER ${POSTGRES_USER};"
done
