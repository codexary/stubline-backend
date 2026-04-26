English | [简体中文](0004-protect-seat-reservations.zh-CN.md)

# ADR 0004: Protect seat reservations in PostgreSQL

## Status

Accepted on 2026-04-27.

## Context

Two concurrent buyers can both pass an unprotected availability check for one seat. A Java monitor protects one inventory object in one process, but two API processes have different objects. The seat state must be shared and the check and update must have one winner.

## Decision

Persist seat availability and a version in PostgreSQL. Provide two strategies behind `SeatInventory`: `ROW_LOCK` uses a transaction and `SELECT ... FOR UPDATE`; `OPTIMISTIC` uses a conditional update on availability and version and retries a failed version race. Use `ROW_LOCK` as the default local database strategy. Keep the synchronized in-memory implementation for isolated runs and tests.

## Alternatives considered

- A process-local `synchronized` method is simple but cannot coordinate replicas.
- A process-local atomic reference can make one state transition indivisible inside one JVM, but cannot coordinate replicas or persist the seat.
- A single event loop could serialize reservations in one process; durable shared state and failover still need design.

## Consequences

Both database strategies allow one winner for a seat. Row locks can make contenders wait. Optimistic updates can fail their version predicate and require another read; a high conflict rate wastes work. The seat reservation implementation has separate version-conflict and retry counters. Each failed conditional update increments the version-conflict counter. The retry counter increments only when another attempt remains; a failure on the final attempt increments the conflict counter and then throws without incrementing retries. The seat reservation and the returned order object are not persisted as one transaction in the seat reservation implementation; later order and payment work must address that boundary.
