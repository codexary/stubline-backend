#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
export STUBLINE_PAUSE_BEFORE_COMMIT_MS=10000
start_drill
token=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=drill-buyer')
new_order A1 "$token"
for i in {1..60}; do
  compose logs notification-service | grep -q 'Pausing before offset commit' && break
  sleep 1
done
compose logs notification-service | grep -q 'Pausing before offset commit'
container=$(compose ps -q notification-service)
docker kill "$container" >/dev/null
export STUBLINE_PAUSE_BEFORE_COMMIT_MS=0
compose up -d --no-build --force-recreate notification-service
for i in {1..90}; do
  count=$(compose exec -T postgres psql -U postgres -d notifications -Atqc 'SELECT count(*) FROM notifications')
  duplicates=$(compose logs notification-service | grep -c 'Duplicate notification dropped' || true)
  [[ "$count" == 1 && "$duplicates" -gt 0 ]] && break
  sleep 1
done
[[ "$count" == 1 && "$duplicates" -gt 0 ]]
assert_metric 'stubline_consumer_lag'
printf 'User: orders accepted; one durable notification; redelivery duplicate dropped %s time(s).\n' "$duplicates"
show_load
