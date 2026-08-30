English | [简体中文](0012-discover-inventory-replicas.zh-CN.md)

# ADR 0012: Discover inventory replicas by name

## Status

Accepted on 2026-08-31.

## Context

Inventory is scaled from one replica to two before an on-sale. Compose DNS returns two addresses after scale-up, but a client that resolves once and builds a channel from the first numeric address remains pinned to one replica. A long-lived healthy gRPC connection also does not reconnect just because the JVM DNS cache TTL expires. In the recorded demo, diagnostics returned one address before scale-up and two after it; following the connection-age wait, the replicas handled 11 and 13 of 24 requests.

## Decision

Keep `inventory-service:9090` as the channel target and use the gRPC DNS name resolver with `round_robin`. Set `-Dsun.net.inetaddr.ttl=5` in the `ticketing-api` JVM and report the JVM-visible addresses and cache configuration through the internal `/diagnostics/dns?name=` route. Set a 15-second maximum connection age and a five-second grace period on the inventory server so even healthy long-lived connections retire and the client requests a refresh; gRPC re-resolves only when at least 30 seconds have passed since its last resolution. More than 30 seconds had passed since the API first resolved in the demo. The five-second positive JVM cache setting limits old answers in that layer; it does not refresh connections. Fifteen seconds is a demo value; a production value must reflect deployment frequency, connection count, and draining requirements.

## Alternatives considered

- Pin one container address: simple configuration, but scale-up and container replacement require manual updates.
- Resolve with the JDK before every RPC: this can obtain a newer address set but adds query and connection-management costs; an existing channel still needs separate refresh handling.
- Add a proxy or service mesh: centralises discovery and distribution, but adds deployment, monitoring, and failure modes that this local Compose deployment does not require.

## Consequences

Connection retirement adds DNS queries, TCP reconnections, and future TLS handshakes; RPCs may briefly redistribute during transitions. Grace gives in-flight RPCs time, but calls exceeding it may fail. `round_robin` does not guarantee an exact split in a finite sample, and a healthy address does not guarantee a successful business call. The diagnostics listener is bound to container loopback; the public entry point returns 404 for `/diagnostics/`, while the admin entry point handles diagnostics requests. Actual traffic still needs per-replica logs. DNS caches, gRPC connections, and health checks are separate layers to investigate.
