#!/bin/sh
set -eu
for service in inventory ticketing notifications; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres -v service="$service" -v password="$STUBLINE_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'service', :'password') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'service', :'service') \gexec
SQL
done
