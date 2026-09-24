#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
started=$(date +%s)
backup_dir=$(mktemp -d)
suffix="restore_$$"
cleanup() {
  for database in inventory ticketing notifications; do
    docker compose exec -T postgres dropdb -U postgres --if-exists "${database}_${suffix}" >/dev/null 2>&1 || true
  done
  rm -rf "$backup_dir"
}
trap cleanup EXIT
snapshot=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for database in inventory ticketing notifications; do
  docker compose exec -T postgres pg_dump -U postgres -Fc --no-owner --no-acl "$database" > "$backup_dir/$database.dump"
  docker compose exec -T postgres createdb -U postgres "${database}_${suffix}"
  docker compose exec -T postgres pg_restore -U postgres --no-owner --no-acl -d "${database}_${suffix}" < "$backup_dir/$database.dump"
done
fingerprint() {
  local database=$1 table=$2 key=$3
  docker compose exec -T postgres psql -U postgres -d "$database" -At -v ON_ERROR_STOP=1 \
    -c "SELECT count(*) || ':' || md5(COALESCE(string_agg(row_to_json(t)::text, ',' ORDER BY $key), '')) FROM $table t"
}
for item in 'ticketing orders id' 'ticketing payment_idempotency idempotency_key' 'notifications notifications event_id'; do
  read -r database table key <<< "$item"
  original=$(fingerprint "$database" "$table" "$key")
  restored=$(fingerprint "${database}_${suffix}" "$table" "$key")
  [[ "$original" == "$restored" ]] || { echo "Mismatch for $database.$table: $original != $restored" >&2; exit 1; }
  printf '%s.%s source and restored: %s\n' "$database" "$table" "$original"
done
printf 'Snapshot %s; backup and restore took %ss\n' "$snapshot" "$(( $(date +%s) - started ))"
