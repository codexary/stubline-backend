English | [简体中文](0005-bound-payment-provider-calls.zh-CN.md)

# ADR 0005: Bound payment provider calls

## Status

Accepted on 2026-05-11. Corrected on 2026-10-31: the Consequences section described 4xx handling that the code never had.

## Context

Ticketing now calls an external payment provider. A slow or unavailable provider can hold a buyer request. A missing charge reply does not establish whether the charge ran. The fault injection spike demonstrated delayed delivery, limited bandwidth, timeout, and connection reset as distinct client observations.

## Decision

Each payment route supplies a two second overall deadline. Every HTTP attempt receives only the remaining budget, and the whole response body is awaited under that deadline. Non-positive budgets are rejected before a request is sent. The order ID is URL encoded. `charge` sends at most once in this implementation. `charge-status` attempts at most three times, with exponential backoff and full jitter between transient failures. A provider 4xx response is treated as a definite rejection and is not retried. A Resilience4j circuit breaker tracks provider attempts in a four call sliding window, opens when at least half fail, waits three seconds by default, and permits one half open probe. State transitions are logged.

## Alternatives considered

- A fixed timeout for every attempt could exceed the buyer's total waiting budget after retries and backoff.
- Retrying `charge` after a missing reply could repeat a completed business effect. A provider recognized idempotency key is deferred to the keyed-payment implementation.
- Unlimited status retries would add load during an outage and hold buyer requests longer.
- No circuit breaker would continue sending calls during sustained failures. The breaker adds temporary rejection even if the provider recovers early.

## Consequences

The caller bounds its wait for the full response and reduces avoidable load during an outage. A timeout leaves the charge outcome unknown. The API maps an unknown outcome to 503 and a definite provider rejection to 402 or 422, so only a 503 leaves the charge outcome open. Status retries consume provider capacity and deadline time. The breaker is local to each `PaymentProviderClient` instance; `OPEN` is evidence of recent failures, not proof that the provider process crashed. A provider 4xx response is a definite answer, not a provider failure, and is not recorded as a breaker failure; `PaymentProviderClientTest.providerRejectionsDoNotCountAsBreakerFailures` covers this. Operators use state transition logs together with provider request observations.
