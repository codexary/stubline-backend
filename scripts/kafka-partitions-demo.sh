#!/usr/bin/env bash
set -euo pipefail

docker compose up --build -d --wait
for pair in 'concert-1 A1' 'concert-2 B1' 'concert-1 A2' 'concert-2 B2'; do
  read -r event seat <<< "$pair"
  curl --fail --silent -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$event\",\"seatId\":\"$seat\"}" \
    http://localhost:8080/orders
  printf '\n'
done
printf '\nKafka records (partition and event key):\n'
docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka:9092 --topic order-placed --from-beginning --max-messages 4 \
  --formatter-property print.partition=true --formatter-property print.key=true --formatter-property print.value=false
printf '\nConsumer group offsets (three partitions, lag zero after processing):\n'
for attempt in {1..30}; do
  offsets=$(docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server kafka:9092 --describe --group stubline-notifications)
  if [[ $(printf '%s\n' "$offsets" | awk '$1=="stubline-notifications" {n++} END {print n+0}') == 3 ]] && \
     printf '%s\n' "$offsets" | awk '$1=="stubline-notifications" && $6!=0 {bad=1} END {exit bad}'; then
    printf '%s\n' "$offsets"
    exit 0
  fi
  sleep 1
done
printf '%s\n' "$offsets"
echo 'Consumer lag did not reach zero on all three partitions' >&2
exit 1
