English | [简体中文](2026-07-01-payment-retry-double-charge.zh-CN.md)

# Draft post-mortem: possible duplicate charge after a lost reply

## Summary

Finance reconciliation identified a buyer with two charges after a timeout and another payment submission. This is the motivating incident scenario, not a reproduced production trace. the original `POST /payments/charge` client made only one provider call per attempt. A buyer submitting Pay again or an HTTP gateway retrying is a separate attempt and can produce the same duplicate effect. A timeout says that the caller did not receive a reply by its deadline; it does not establish whether the provider charged.

## Impact

The scenario contains one buyer and two charges for one intended payment. The amount, actual incident time, affected population, and refund state are not established by the repository evidence. They must be filled from finance and provider records before this draft becomes an incident record.

## Timeline

- Time unknown: the buyer initiates a payment; the provider outcome is not returned before the client timeout.
- Time unknown: the buyer or a gateway submits the payment again.
- Time unknown: finance reconciliation identifies two provider charges for the intended purchase.
- Keyed-payment investigation: the team reproduces a lost first reply and an empty API idempotency store. The recorded demo returns 201 on retry with one provider charge because the key is forwarded.

## Root cause and trigger

The trigger is a second submission after an ambiguous timeout. The pre-idempotency charge route had no shared invocation identity across submissions. The original payment-provider client deliberately did not retry its own charge call, but that did not govern buyer or gateway behavior. A missing response was treated outside the service as if a fresh payment could safely be submitted. This describes a system gap, not an individual error. The repository cannot prove the exact path of the scenario's second submission.

## Resolution

For the scenario, confirm both provider charge IDs and the order, then have finance handle any duplicate according to its normal refund process. The repository contains no evidence that a refund was issued. The keyed-payment implementation adds `Idempotency-Key` to `POST /payments`, a persisted API reply history, and forwarding of the key to the provider. A definitive pre-charge decline releases the API claim; an unknown outcome stays in flight for reconciliation.

## What went well

The original payment-provider client bounded provider calls and avoided a blind automatic charge retry. The keyed-payment implementation's recorded lost reply demo and HTTP tests show that the same key can recover a 201 response while the provider charge count stays one. PostgreSQL tests cover reply replay across store instances.

## What went wrong

The earlier route had no common key for independent buyer or gateway submissions. The API and provider cannot commit their records atomically, so a charge can succeed while the API still says `IN_FLIGHT`. The provider stub's map is volatile.

## Where we got lucky

The scenario supplies no evidence about whether other buyers were affected. The test demonstration succeeds because the provider stub remains alive and retains its keyed map through the API restart; it does not demonstrate provider crash recovery.

## Action items

| Owner | Action | Verification |
|---|---|---|
| Sofia Marquez | Define client key ownership and reuse rules for one checkout attempt; document provider retention and lookup behavior. | Contract and tests for same key, changed body, and timeout recovery. |
| Tomás Ribeiro | Maintain and back up the PostgreSQL idempotency table; design a reconciliation query for old `IN_FLIGHT` rows. | Restore drill and reviewed reconciliation procedure. |
| Wang Hao | Alert on aged `IN_FLIGHT` payments and compare them with provider charge records. | Drill with a simulated failed API completion. |
| Liu Jia | Keep HTTP and PostgreSQL tests for replay, conflict, mismatch, decline, and lost reply. | `verify` and Docker profile results. |
| Mei Lin | Review expiry and provider retention before allowing automatic retry beyond the shared window. | ADR update and operational review. |

No action item asks an individual to be more careful. The team will close this draft's unknowns with actual finance and provider records.
