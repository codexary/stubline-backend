English | [简体中文](0010-use-outbox-and-kafka-for-order-events.zh-CN.md)

# ADR 0010: Use an outbox and Kafka for order events

## Status

Accepted on 2026-07-22.

## Context

Grace Kim needs an e-ticket notice even when notification handling is slow. Sofia Marquez needs a committed order to remain discoverable if the API stops before publishing to the broker. A direct call from the buyer request would wait for notification availability. Publishing after the order commit leaves a crash window between two systems.

The RabbitMQ task-queue spike demonstrates a good fit for individually acknowledged rendering jobs, with prefetch one, a delay queue and a dead-letter queue. The Redis pub/sub spike demonstrates immediate fan-out to current listeners, but a late listener does not receive earlier counts. Redis Streams retain history for a consumer group. Order notifications need retained events and an independent consumer position.

## Decision

In PostgreSQL mode, `ticketing-api` writes the order and a Protobuf `OrderPlaced` payload to `order_outbox` in one transaction. The payload has a unique message `eventId`; its `concertId` identifies the concert. A relay selects unpublished rows with `FOR UPDATE SKIP LOCKED`, sends them to the three-partition Kafka `order-placed` topic using the concert ID as key, and marks rows published after Kafka acknowledges them. `notification-service` reads as group `stubline-notifications`, records the message ID in `handled_events` and a notification row in one database transaction, then commits its Kafka offset. A redelivery of the same message ID causes no second notification row; a distinct message for the same order is handled separately.

## Alternatives considered

- Synchronous notification from `POST /orders`: ties buyer latency and success to the notification process.
- Publish directly after the order commit: loses the event if the process stops in the gap.
- RabbitMQ work queue: useful for independent render jobs and per-job retries, but the order event is also a retained history for independently progressing groups.
- Redis pub/sub: useful for disposable live seat counts, but a late listener misses previous updates.

## Consequences

The relay can publish a record twice if it stops after Kafka accepts the send and before PostgreSQL records `published_at`. The consumer can read a record twice if it stops after database handling and before offset commit. The ID ledger makes the database notification effect once per message ID in the tested case. The log line is a demonstration, not a delivered email; an external mail provider needs its own idempotency key or outbox. Ordering is per Kafka partition. The concert ID keeps one concert's orders together, but there is no global order across concerts. Operators inspect group lag and the outbox when notifications are late.
