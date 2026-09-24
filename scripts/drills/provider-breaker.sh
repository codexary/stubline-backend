#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
start_drill
curl -fsS -X POST -d true http://localhost:8081/admin/outage >/dev/null
failures=0
for i in {1..6}; do
  code=$(status -X POST https://localhost:8080/payments/charge || echo 000)
  printf '%s provider charge %s\n' "$(date -u +%H:%M:%S)" "$code"
  [[ "$code" == 503 ]] && failures=$((failures+1))
done
[[ $failures -ge 4 ]]
sleep 6
assert_metric 'stubline_payment_breaker_state'
[[ "$(metric_value 'stubline_payment_breaker_state')" == 1* ]]
compose logs ticketing-api | grep -q 'CLOSED to OPEN' 
curl -fsS -X POST -d false http://localhost:8081/admin/outage >/dev/null
sleep 4
recovered=$(status -X POST https://localhost:8080/payments/charge)
[[ "$recovered" == 200 ]]
sleep 6
[[ "$(metric_value 'stubline_payment_breaker_state')" == 0* ]]
compose logs ticketing-api | grep -q 'HALF_OPEN to CLOSED' 
printf 'User: %s failed charges, recovered HTTP %s. Telemetry: breaker opened then closed.\n' "$failures" "$recovered"
show_load
