English | [简体中文](0006-bound-container-resources.zh-CN.md)

# ADR 0006: Bound container resources

## Status

Proposed on 2026-05-21.

## Context

`ticketing-api` and `payment-provider-stub` are separate Java processes. Wang Hao needs a repeatable resource envelope on the platform team's container hosts and evidence to distinguish a JVM heap failure from a kernel OOM kill. The container-resource Compose stack also runs PostgreSQL.

## Decision

Build the Java services with separate JDK build and pinned Temurin 21 JRE runtime stages. Run them as UID 10001 with `-XX:MaxRAMPercentage=60`. Compose sets `ticketing-api` and `postgres` to 1 CPU and 512 MiB each; `payment-provider-stub` gets 0.5 CPU and 256 MiB. Use readiness health checks. Keep runtime diagnostics on the container's loopback admin listener. On call, compare application logs, container exit code, `OOMKilled`, and the cgroup limit before changing heap or container limits.

## Alternatives considered

- Run the JARs directly on the host: workable for debugging, but host service management would still need explicit resource limits and restart handling.
- Leave containers unlimited: simpler configuration, but one process could consume memory needed by its neighbors.
- Set `-Xmx` equal to the container memory limit: leaves no room for direct buffers, thread stacks, metadata, or other native memory.

## Consequences

The stack has a reproducible local resource envelope and a concrete diagnostic path. The chosen numbers require measurement under production traffic. `-XX:MaxRAMPercentage=60` limits the Java heap, not all JVM memory. The container resource experiment observed `availableProcessors=1`, `maxHeapBytes=312213504`, and `containerMemoryLimitBytes=536870912` inside the API container. The independent memory spike showed a Java heap OOM with exit 1 and `OOMKilled=false`, and a kernel kill with exit 137 and `OOMKilled=true`.
