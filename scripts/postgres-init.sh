#!/bin/sh
set -eu
for service in inventory ticketing notifications; do
  case "$service" in
    inventory) password=${STUBLINE_INVENTORY_DB_PASSWORD:?missing inventory database password} ;;
    ticketing) password=${STUBLINE_TICKETING_DB_PASSWORD:?missing ticketing database password} ;;
    notifications) password=${STUBLINE_NOTIFICATIONS_DB_PASSWORD:?missing notifications database password} ;;
  esac
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v service="$service" -v password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service', :'password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'service', :'service') \gexec
SQL
done
