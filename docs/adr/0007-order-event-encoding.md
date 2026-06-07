English | [简体中文](0007-order-event-encoding.zh-CN.md)

# ADR 0007: Order event encoding and schema evolution

Proposed location: `docs/adr/0007-order-event-encoding.md`

## Status

Draft. The current codec work implements and verifies both codecs; it does not publish `OrderPlaced` to a broker yet.

## Context

Future consumers may retain order events for years and upgrade at different times. `ticketing-api` defines `OrderPlaced`, a Jackson JSON codec, and a Protocol Buffers codec based on `order_placed.proto`. The pinned sample encodes to 175 JSON bytes and 73 Protobuf bytes. Compatibility tests cover an added optional Protobuf field, a reserved removed field, a deliberately ignored unknown JSON field, and a JSON field rename that breaks the old consumer. Decoder tests also establish that missing amounts and other required fields are rejected, while an explicit zero amount round trips.

## Decision

Use the `.proto` schema as the shared contract for the future long-lived, high-volume order event stream, with a Protobuf wire format when the stream is connected. Keep the JSON codec for inspection and integrations that explicitly choose JSON. Record the format and schema version for each stream. Never reuse a removed Protobuf field number or name. Add new fields under new numbers, and test old consumers against new producer bytes before release. Treat JSON field names as wire identities; a rename requires a staged consumer migration.

## Alternatives considered

- JSON on the long-lived stream: readable without generated code and straightforward for new consumers, but the sample is larger and field names are repeated in every message. Its forward compatibility depends on each consumer's unknown-field policy.
- Protobuf for every integration: compact and has stable numeric identities, but forces even simple inspection paths to obtain a schema and generated code.

## Consequences

The team must maintain a pinned `protoc` build, distribute the `.proto` contract, review schema changes and preserve compatibility tests. Both codecs now reject missing required fields; business validation for amount range and currency remains a separate concern. The event codec code makes no claim about broker delivery or replay behavior.
