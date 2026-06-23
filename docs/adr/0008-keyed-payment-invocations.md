English | [简体中文](0008-keyed-payment-invocations.zh-CN.md)

# ADR 0008: Key payment invocations at the API and provider

## Status

Accepted on 2026-06-24.

## Context

The original payment charge route sends once because a timeout does not prove the provider did not charge. A buyer pressing Pay again or a gateway retransmitting can nevertheless create a second charge. The request and the reply can fail independently. The API and provider also keep separate histories.

The [webhook receiver spike](https://github.com/codexary/stubline-spike-webhook-receiver/blob/main/README.md) establishes that signed delivery can repeat: it verifies HMAC over the timestamp and raw body, checks a five minute window, and filters event IDs. It does not make a charge request idempotent. Provider webhooks are not yet integrated into Stubline.

## Decision

Require `Idempotency-Key` on `POST /payments`. Treat the same key and exact body bytes as one invocation. Claim the key before calling the provider; return the stored status, reason, and body after completion. Return 409 for a matching in-flight claim and 422 for a different body under the same key. Release only after a definite pre-charge rejection. Keep unknown outcomes in flight for reconciliation.

Forward the same key to the provider's `POST /payments`. Use the PostgreSQL store in the deployed API and the in-memory store for local demonstrations. A completed API record expires after 24 hours; an in-flight record does not expire automatically. Reconcile an unknown outcome before any manual release.

The keyed-payment provider client builds the forwarded JSON with Jackson, so special characters in `orderId` are escaped. It distinguishes the provider's 422 key mismatch from 402 and other 4xx rejections, which the stub treats as definitive pre-charge failures. An actual provider integration must verify that classification against its contract. A 5xx or I/O failure retains the claim.

## Alternatives considered

- Never retry a charge: reduces automatic duplication but leaves buyers and gateways able to resubmit, and leaves timeouts unresolved.
- Retry without a key: can charge twice when the first reply is lost.
- Deduplicate only at the API: cannot protect a charge whose provider result exists but whose API record was lost.
- Drop duplicate requests without retaining replies: prevents another local call but cannot give a retrying caller its original result.

## Consequences

The key and body hash form a bounded request history. A PostgreSQL unique key serializes competing claims, and saved replies survive API restart. Forwarding the key lets the provider return its existing charge when the API history is absent. There is no atomic transaction spanning PostgreSQL and the provider. The provider stub's history is in memory, and the 24 hour API expiry bounds the protection. Operations must compare provider charge history with API rows and orders before resolving stuck `IN_FLIGHT` records.

The webhook spike informs a later provider callback integration. Its in-memory event IDs and lack of durable effect tracking are not sufficient for production.
