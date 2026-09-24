#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/dev-ca.sh >/dev/null
TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=trace-buyer')
seat=${1:-A2}
order=$(curl --cacert certs/dev/public-ca.crt -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "{\"eventId\":\"concert-1\",\"seatId\":\"$seat\"}" https://localhost:8080/orders)
order_id=$(printf '%s' "$order" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
python3 - "$order_id" <<'PY'
import json,sys,time,urllib.parse,urllib.request
order_id=sys.argv[1]
base='http://127.0.0.1:3200'
query='{ name = "order.place" && span.order.id = "'+order_id+'" }'
def get(url):
    with urllib.request.urlopen(url,timeout=5) as response: return json.load(response)
def spans(trace):
    for resource in trace.get('resourceSpans',[]):
        attrs={a['key']:a['value'].get('stringValue') for a in resource.get('resource',{}).get('attributes',[])}
        for scope in resource.get('scopeSpans',[]):
            for span in scope.get('spans',[]): yield attrs.get('service.name',''),span.get('name','')
    for batch in trace.get('batches',[]):
        attrs={a['key']:a['value'].get('stringValue') for a in batch.get('resource',{}).get('attributes',[])}
        for scope in batch.get('scopeSpans',[]):
            for span in scope.get('spans',[]): yield attrs.get('service.name',''),span.get('name','')
for attempt in range(45):
    search=get(base+'/api/search?'+urllib.parse.urlencode({'q':query,'limit':20}))
    for row in search.get('traces',[]):
        trace_id=row.get('traceID') or row.get('traceId')
        if not trace_id: continue
        found=list(spans(get(base+'/api/traces/'+trace_id)))
        required=[('ticketing-api','POST /orders'),('ticketing-api','order.place'),('inventory-service','Inventory'),('ticketing-api','outbox.publish'),('notification-service','notification.handle')]
        if all(any(service==s and (needle in name if needle=='Inventory' else name==needle) for s,name in found) for service,needle in required):
            print('Trace ID:',trace_id,'order:',order_id)
            for service,name in found: print(service,name)
            sys.exit(0)
    time.sleep(2)
sys.exit('No connected HTTP, order, inventory gRPC, outbox and notification trace for '+order_id)
PY
