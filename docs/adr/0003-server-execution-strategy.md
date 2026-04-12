English | [简体中文](0003-server-execution-strategy.zh-CN.md)

# ADR 0003: Server execution strategy for the on-sale load

## Status

Proposed

## Context

Grace Kim's on-sale announcement requires the ticketing API to serve many buyers at once. In the raw HTTP server used for the blocking-load experiment, an executor task covers an entire keep-alive connection. A fixed pool of four workers can therefore be occupied by four clients waiting for another request while later connections remain queued. The recorded Java load run completed 112 successful requests in three seconds with FIXED_POOL, versus 896 with THREAD_PER_REQUEST and 864 with VIRTUAL. Its per-client report showed 28 of 32 FIXED_POOL clients with no completions. Successful-request p95 was approximately 109 ms for FIXED_POOL and did not reveal the queued clients. The k6 script for the blocking-load experiment with a two-second request timeout and failure thresholds reported 280 failed checks of 1043 (26.84%) for FIXED_POOL, with exit 99; THREAD_PER_REQUEST and VIRTUAL had 0% failed checks in that recorded run.

## Decision

Use VIRTUAL for the raw server's connection tasks in this implementation. Keep the JDK HTTP server as the product HTTP implementation, with its own exchange-level executor behavior. Treat the load results as evidence for this simulated blocking workload only. Re-test with actual database calls, a bounded database connection pool, and representative clients before an on-sale capacity commitment.

In the raw HTTP server, fixed-pool workers are occupied by keep-alive connections; do not select that configuration for this on-sale workload. Virtual threads can carry waiting connections, but database connection limits and request queues still need their own bounds.

## Alternatives considered

- THREAD_PER_REQUEST: passed this recorded workload, but one platform thread per long-lived raw connection has a larger resource cost as connections grow.
- FIXED_POOL: limits running platform workers, but the current raw server assigns workers to keep-alive connections, which caused starvation in the recorded test. Its queue is not given a capacity bound here.
- VIRTUAL: lets many blocking connections wait without one platform thread each, but does not limit database concurrency, remove blocking latency, or prevent all carrier pinning.

## Consequences

The raw-server demonstration can serve more waiting clients under the measured load. Operations must monitor failed requests as well as successful-request latency. The team still needs explicit downstream concurrency limits, timeouts, and a database-backed load test. The health-checker spike informs later deadline design for payment-provider calls.
