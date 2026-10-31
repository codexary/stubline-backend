English | [简体中文](0009-extract-inventory-with-grpc.zh-CN.md)

# ADR 0009: Extract inventory with gRPC

## Status

Accepted on 2026-07-13. Corrected on 2026-10-31: the last paragraph now matches the readiness check that was merged.

## Context

Ticketing API and seat inventory previously ran in one process. Inventory needs its own release and scaling path. Tomás Ribeiro notes that the authoritative seat rows must move with the component; two services must not each treat their own copy as authoritative. A reservation is a state change, so a missing reply leaves its outcome unknown.

## Decision

Put the Protobuf service contract and generated Java classes in `inventory-api`. Compose creates separate `inventory` and `ticketing` databases and users. `inventory-service` owns the seat table and implements `ListSeats` and `Reserve` on gRPC port 9090. `ticketing-api` uses a generated blocking stub with a two second deadline on every call. Translate `NOT_FOUND`, `FAILED_PRECONDITION`, `DEADLINE_EXCEEDED`, and `UNAVAILABLE` at the HTTP boundary. Keep `InMemoryInventory` for isolated tests. Expose the standard gRPC health service for the Compose probe. The API client also checks overall gRPC health with a deadline capped at 500 milliseconds and treats errors as not ready.

After a deadline on `Reserve`, do not assume that the seat remained available and do not automatically issue another unkeyed order. Check the authoritative seat state and reconcile the order outcome. A future reservation request key and stored result would permit safe replay, following the payment design in ADR 0008.

## Alternatives considered

- Keep inventory in the API process: fewer network failure paths, but inventory cannot be released or scaled independently.
- Java RMI: a direct remote interface, but Java coupling and the registry/export model do not fit the intended service boundary as well as the shared schema.
- REST with JSON: easy to inspect and suitable for the public HTTP boundary, but this internal call benefits from generated message and service types.

## Consequences

The API now depends on a remote service and must distinguish business rejection from transport failure. The deadline bounds waiting, not whether a late server action happened. Inventory data and its migration belong to `inventory-service`. The inventory service code has no reservation idempotency key, so reconciliation remains necessary after an unknown outcome.

The API readiness client checks the named `stubline.inventory.v1.Inventory` health status, which `InventoryMain` updates from database readiness. `InventoryReadinessDockerTest.inventoryDatabaseLossMakesApiNotReady` shows that losing the inventory database makes the API report not ready.
