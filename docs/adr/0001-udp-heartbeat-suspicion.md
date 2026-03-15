English | [简体中文](0001-udp-heartbeat-suspicion.zh-CN.md)

# ADR 0001: Treat UDP heartbeat timeout as suspicion

## Status

Proposed

## Context

Wang Hao needs an operator facing indication when an API instance stops sending timely heartbeats. During the UDP heartbeat experiment, `ticketing-api` is a modular monolith; the `health` package contains a UDP sender and detector. The detector tests show that a stopped instance and a slow instance have the same `SUSPECTED` state at the timeout. A later heartbeat from the slow instance restores `ALIVE`.

## Decision

Use instance ID and increasing sequence number in each heartbeat. Record the local monotonic receipt time only when a newer sequence arrives. Show `SUSPECTED` when elapsed time reaches the configured threshold, and show `ALIVE` again on a newer heartbeat. Timeout state is named `SUSPECTED`, not `DEAD`. Until traffic switching rules exist, the detector provides observations and does not automatically remove an instance from traffic.

## Alternatives considered

- Declare a peer dead at timeout: rejected because delay, packet loss and partition can produce the same observation.
- Wait indefinitely: avoids a timeout based suspicion but leaves operators without a timely signal.
- Use sender wall time for elapsed time: rejected because clocks on different machines need not agree.

## Consequences

The threshold trades detection delay against false suspicion. Duplicate and older sequence numbers do not refresh it. A restarted sender must use a new instance ID while the detector retains the old sequence history. The present implementation logs status changes; it does not coordinate membership across detectors or move business traffic.
