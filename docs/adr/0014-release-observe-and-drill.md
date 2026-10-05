English | [简体中文](0014-release-observe-and-drill.zh-CN.md)

# ADR 0014: Identify releases, connect order traces, and rehearse failures

## Status

Accepted on 2026-10-06.

## Context

An on-sale is approaching. The team needs to identify running code, follow one order across synchronous and asynchronous services, and describe buyer-visible results when dependencies fail. The existing outbox separates the HTTP request from Kafka publication. A trace created only inside the relay would lose its relationship to the order request.

## Decision

Build versioned images only from a clean Git worktree after `./mvnw -q verify`. Label application images with the release version and 12-character commit revision. Use `compose.release.yaml` with those images, then inspect the labels on running containers.

Use the OpenTelemetry Java agent in each Java service for common operations, and manual `order.place`, `outbox.publish`, and `notification.handle` spans for business boundaries. Save W3C `traceparent` with the order outbox row and restore it when publishing. Send telemetry through the Collector, filtering health-check spans from traces, into the local Grafana LGTM stack.

Run isolated drills for inventory replica loss, Kafka outage, notification consumer crash before offset commit, and payment provider outage. Check buyer responses, metrics, and durable state. Restore database dumps into separate temporary databases and compare selected table fingerprints. A separate kind experiment compares sudden worker loss with planned drain.

Give each emitted `OrderPlaced` message its own ID, distinct from the order and concert IDs. Deduplicate notification handling by that message ID, so redelivery is dropped while another legitimate message for the same order can be handled. Check order ownership before `POST /payments` calls the provider. Issue a ticket under an order row lock, with a deterministic object key, so concurrent issuers do not upload twice.

## Alternatives considered

- Use logs alone: simple, but they do not provide the parent-child relationship across HTTP, gRPC, the outbox, and Kafka.
- Build from an uncommitted worktree: fast, but the image cannot be reliably reconstructed from its commit label.
- Use only readiness and replica counts as drill evidence: they cannot say whether a buyer request failed or whether a committed notification was duplicated.
- Treat the kind experiment as a Stubline production deployment: this would overstate what a small, stateless local test demonstrates.

## Consequences

The recorded connected order trace includes API, inventory, outbox publish, and notification handling. The four local drills and backup restore produced the recorded local outcomes. The trace check covers order creation through notification, not the later payment and ticket download steps. The local release workflow does not supply a remote image registry or immutable deployed digest. Drills delete Compose volumes; they belong in a disposable environment. Node failure remained buyer-visible in the kind run even though planned drain had zero sampled failures.

