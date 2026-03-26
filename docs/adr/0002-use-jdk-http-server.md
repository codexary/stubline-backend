English | [简体中文](0002-use-jdk-http-server.zh-CN.md)

# ADR 0002: Use the JDK HTTP server for the first ticketing API

Status: Accepted on 2026-03-27.

## Context

The first ticketing routes are `GET /events/{eventId}/seats` and `POST /orders`. A raw `ServerSocket` implementation makes request framing visible, including CRLF lines, the blank line, `Content-Length`, partial reads, and persistent connections. Maintaining an HTTP parser alongside the business routes would make the team responsible for more protocol behavior than these routes require.

## Decision

Keep `RawHttpServer` as a framing exercise and serve the routes with `JdkHttpServer`. Both call `TicketingApi.handle`. Verify both with request and response tests, including the public contract.

## Alternatives considered

- Continue using `RawHttpServer` for the API: useful for the exercise, but requires maintaining the request parser and response writer.
- Introduce a larger web framework now: offers more application features, but adds a layer before this implementation needs it.

## Consequences

`HttpServer` parses HTTP exchanges and manages the network connection. The team still owns body limits, routing, application errors, and tests. The in-memory inventory contains two sample seats and is reset when the process starts.
