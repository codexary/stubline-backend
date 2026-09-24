#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
start_drill
token=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=drill-buyer')
compose stop kafka
before=$(compose exec -T postgres psql -U postgres -d ticketing -Atqc 'SELECT count(*) FROM order_outbox WHERE published_at IS NULL')
reply=$(new_order A1 "$token")
printf 'Order during Kafka outage: %s\n' "$reply"
[[ "$reply" == *'"id"'* ]]
for i in {1..60}; do sleep 1; done
during=$(compose exec -T postgres psql -U postgres -d ticketing -Atqc 'SELECT count(*) FROM order_outbox WHERE published_at IS NULL')
printf 'Outbox before=%s during=%s\n' "$before" "$during"
[[ $during -gt $before ]]
assert_metric 'stubline_outbox_backlog'
metric_backlog=$(metric_value 'stubline_outbox_backlog')
python3 - "$metric_backlog" <<'PYCHECK'
import sys
assert float(sys.argv[1]) > 0
PYCHECK
compose start kafka
for i in {1..90}; do
  pending=$(compose exec -T postgres psql -U postgres -d ticketing -Atqc 'SELECT count(*) FROM order_outbox WHERE published_at IS NULL')
  [[ "$pending" == 0 ]] && break
  sleep 1
done
[[ "$pending" == 0 ]]
for i in {1..60}; do
  notifications=$(compose exec -T postgres psql -U postgres -d notifications -Atqc 'SELECT count(*) FROM notifications')
  [[ "$notifications" == 1 ]] && break
  sleep 1
done
[[ "$notifications" == 1 ]]
lag=$(compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group stubline-notifications)
printf '%s\n' "$lag"
printf '%s\n' "$lag" | awk '$1=="stubline-notifications" && $6!=0 {bad=1} END {exit bad}'
printf 'User: order accepted while broker stopped; outbox recovered to 0, one notification, lag 0.\n'
show_load
