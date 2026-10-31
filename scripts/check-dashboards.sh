#!/usr/bin/env bash
set -euo pipefail

# Keep a trickle of API traffic while the checks run. A zero value is healthy;
# an empty Prometheus result is not. The HTTP error panel uses a zero fallback
# because a stack without 5xx responses has no error time series.
# rate() needs the counter to grow between two exported samples. On a fresh
# stack a short burst creates the series at its final value, so its rate stays
# zero however long the check waits.
while :; do
  curl --cacert certs/dev/public-ca.crt --fail --silent --show-error \
    'https://localhost:8080/events?limit=1' >/dev/null || true
  sleep 1
done &
traffic=$!
trap 'kill "$traffic" 2>/dev/null || true' EXIT

python3 - <<'PY'
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base = "http://localhost:3000"
names = {
    1: "HTTP request rate",
    2: "HTTP errors",
    3: "p95 latency",
    4: "outbox backlog",
    5: "consumer lag",
    6: "Hikari active connections",
}

def get(path):
    with urllib.request.urlopen(base + path, timeout=10) as response:
        return json.load(response)

try:
    dashboard = get("/api/dashboards/uid/stubline-service-health")["dashboard"]
except (urllib.error.URLError, KeyError, ValueError) as error:
    print(f"FAIL Stubline dashboard (stubline-service-health): {error}")
    sys.exit(1)

print("PASS Stubline dashboard (stubline-service-health) is loaded")
panels = {panel["id"]: panel for panel in dashboard.get("panels", [])}
failed = False
business_routes = {
    "/events", "/events/{eventId}/seats", "/orders", "/orders/{orderId}",
    "/orders/{orderId}/ticket", "/payments", "/payments/charge",
    "/payments/charge-status",
}
for panel_id, name in names.items():
    panel = panels.get(panel_id)
    if not panel or not panel.get("targets"):
        print(f"FAIL {name}: panel or query missing")
        failed = True
        continue
    target = panel["targets"][0]
    datasource = target.get("datasource", {})
    if datasource.get("uid") != "prometheus":
        print(f"FAIL {name}: Prometheus datasource is not selected")
        failed = True
        continue
    query = urllib.parse.urlencode({"query": target["expr"]})
    path = "/api/datasources/proxy/uid/prometheus/api/v1/query?" + query
    for attempt in range(12):
        try:
            result = get(path)
            series = result.get("data", {}).get("result", []) if result.get("status") == "success" else []
            if series:
                if panel_id == 1:
                    routes = sorted((item.get("metric", {}).get("http_route", "<missing>"), float(item["value"][1])) for item in series)
                    if not any(route in business_routes and rate > 0 for route, rate in routes):
                        if attempt < 11:
                            time.sleep(5)
                            continue
                        print("HTTP request rate routes: " + ", ".join(f"{route}={rate:.4g}/s" for route, rate in routes))
                        print("FAIL HTTP request rate: no active business route template; API traffic may be grouped under /")
                        failed = True
                        break
                    print("HTTP request rate routes: " + ", ".join(f"{route}={rate:.4g}/s" for route, rate in routes))
                print(f"PASS {name}: {len(series)} series")
                break
        except (urllib.error.URLError, ValueError):
            pass
        if attempt < 11:
            time.sleep(5)
    else:
        print(f"FAIL {name}: no series after 60 seconds")
        failed = True

sys.exit(1 if failed else 0)
PY
