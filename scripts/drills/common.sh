#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
set -a
source .env.release
set +a
compose() { docker compose -f compose.yaml -f compose.release.yaml "$@"; }
cleanup() {
  if [[ -n "${load_pid:-}" ]]; then kill "$load_pid" 2>/dev/null || true; wait "$load_pid" 2>/dev/null || true; fi
  if [[ "${STUBLINE_PAUSE_BEFORE_COMMIT_MS:-0}" != 0 ]]; then STUBLINE_PAUSE_BEFORE_COMMIT_MS=0 compose up -d --no-build --force-recreate notification-service >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
start_drill() {
  ./scripts/dev-ca.sh
  compose down -v >/dev/null
  compose up -d --no-build --wait --scale inventory-service=2
  docker run --rm --add-host=host.docker.internal:host-gateway \
    -e SSL_CERT_FILE=/certs/public-ca.crt -e DURATION=90s \
    -v "$PWD/certs/dev/public-ca.crt:/certs/public-ca.crt:ro" \
    -v "$PWD/scripts/drills:/scripts:ro" grafana/k6:2.3.0 run /scripts/load.js > /tmp/stubline-drill-k6.log 2>&1 &
  load_pid=$!
}
api() { curl --cacert certs/dev/public-ca.crt --silent --show-error --max-time 3 "$@"; }
status() { api -o /dev/null -w '%{http_code}' "$@"; }
show_load() { wait "$load_pid"; load_pid=; tail -15 /tmp/stubline-drill-k6.log; }
metric() {
  local query=$1
  curl --fail --silent --get --data-urlencode "query=$query" http://localhost:9091/api/v1/query
}
metric_value() {
  metric "$1" | python3 -c 'import json,sys; data=json.load(sys.stdin); rows=data["data"]["result"]; print(rows[0]["value"][1] if rows else "NaN")'
}
assert_metric() {
  local value
  value=$(metric_value "$1")
  printf 'Telemetry %s=%s\n' "$1" "$value"
  [[ "$value" != NaN ]]
}
new_order() {
  local seat=$1 token=$2
  api -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"concert-1\",\"seatId\":\"$seat\"}" https://localhost:8080/orders
}
