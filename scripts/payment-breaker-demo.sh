#!/bin/sh
set -eu
PROVIDER_OUTAGE=true PROVIDER_PORT=18081 ./mvnw -q -pl payment-provider-stub exec:java -Dexec.mainClass=com.elsworne.stubline.provider.PaymentProviderMain &
provider_pid=$!
STUBLINE_PAYMENT_PROVIDER_URL=http://127.0.0.1:18081 ./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='jdk 18080' &
api_pid=$!
trap 'kill "$provider_pid" "$api_pid" 2>/dev/null || true' EXIT
sleep 5
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w 'charge HTTP %{http_code} time %{time_total}s\n' -X POST http://127.0.0.1:18080/payments/charge
done
curl -s -X POST -d false http://127.0.0.1:18081/admin/outage >/dev/null
sleep 4
curl -s -o /dev/null -w 'recovered HTTP %{http_code} time %{time_total}s\n' -X POST http://127.0.0.1:18080/payments/charge
