#!/usr/bin/env bash
set -euo pipefail

./scripts/dev-ca.sh
docker compose up --build -d --wait --scale inventory-service=1
printf '\nInitially registered addresses:\n'
docker compose exec -T ticketing-api /bin/busybox wget -qO- 'http://127.0.0.1:8081/diagnostics/dns?name=inventory-service'
printf '\nPinned gRPC client (one resolved address):\n'
docker compose exec -T ticketing-api java -cp 'app.jar:lib/*' com.elsworne.stubline.diagnostics.PinnedInventoryClient inventory-service 9090

docker compose up -d --wait --scale inventory-service=2
printf '\nAddresses after scale-up:\n'
docker compose exec -T ticketing-api /bin/busybox wget -qO- 'http://127.0.0.1:8081/diagnostics/dns?name=inventory-service'
printf '\nPinned gRPC client after scale-up (still one resolved address):\n'
docker compose exec -T ticketing-api java -cp 'app.jar:lib/*' com.elsworne.stubline.diagnostics.PinnedInventoryClient inventory-service 9090
printf '\nWaiting for the 15 s gRPC connection age and 5 s grace...\n'
sleep 25
started=$(date -u +%Y-%m-%dT%H:%M:%SZ)
for attempt in {1..24}; do
  curl --cacert certs/dev/public-ca.crt --fail --silent 'https://localhost:8080/events/concert-1/seats' >/dev/null
done
printf 'Round-robin requests served by each replica (24 total):\n'
while IFS= read -r container; do
  name=$(docker inspect --format '{{.Name}}' "$container")
  count=$(docker logs --since "$started" "$container" 2>&1 | grep -c 'listSeats served by' || true)
  printf '%s: %s\n' "${name#/}" "$count"
done < <(docker compose ps -q inventory-service)
