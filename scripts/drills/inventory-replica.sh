#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
start_drill
replicas=($(compose ps -q inventory-service))
[[ ${#replicas[@]} == 2 ]]
printf 'Killing inventory replica %s\n' "${replicas[0]}"
start=$(date +%s)
docker kill "${replicas[0]}" >/dev/null
first_success=
failures=0
for second in {0..14}; do
  requests=0
  failed=0
  for sample in {1..5}; do
    code=$(status https://localhost:8080/events/concert-1/seats || echo 000)
    requests=$((requests+1))
    if [[ "$code" != 200 ]]; then failed=$((failed+1)); failures=$((failures+1)); fi
    sleep 0.2
  done
  if [[ "$failed" == 0 && -z "$first_success" ]]; then first_success=$(date +%s); fi
  printf 'second=%s requests=%s failures=%s\n' "$second" "$requests" "$failed"
done
[[ -n "$first_success" ]] || { echo 'No full second of successful requests after kill' >&2; exit 1; }
printf 'First full second without failures after kill: %ss; total failures=%s\n' "$((first_success-start))" "$failures"
assert_metric 'sum(rate(http_server_request_duration_seconds_count[1m]))'
show_load
