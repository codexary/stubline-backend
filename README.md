English | [简体中文](README.zh-CN.md)

# Stubline

Stubline is Elsworne's event ticketing platform for events, seats, orders, payments, notifications and e-tickets. The current modular monolith has `catalog`, `inventory`, `orders`, `payments`, `http`, `health` packages in `ticketing-api`. Inventory is in memory and resets on restart. Later features are added in subsequent chapters.

## Build and test

Install Java 21. The included Maven wrapper downloads Maven 3.9.16. From this repository root:

```sh
./mvnw -q verify
```

Expected: exit status 0; `-q` suppresses normal build output. The build runs JUnit tests and Spotless checks. To apply formatting, run `./mvnw spotless:apply`.

See [the project team](docs/team.md).

## UDP heartbeat and failure detection

The detector stays in `ticketing-api`'s `health` package because it observes these API instances and needs no separate operations deployment yet. Run these from the repository root in separate terminals:

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='detector 9999 700'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-a 127.0.0.1 9999 200 3'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-b 127.0.0.1 9999 200'
```

The detector prints the complete membership snapshot whenever any instance changes state. Expected detector transitions include `api-a ALIVE` and `api-b ALIVE`. Sender `api-a` logs `dropped api-a sequence=3` (and every third sequence); the detector remains alive if subsequent packets arrive before 700 ms. Stop a sender with Ctrl-C: about 700 ms after its last accepted packet the detector logs `api-a SUSPECTED`. Temporarily suspend the same sender process with Ctrl-Z and resume it with `fg`: it logs `api-a ALIVE` when a newer sequence arrives. A fresh sender process must use a new instance id until the detector restarts, because sequence numbers restart at 1. The tests also inject reordered and duplicate sequences and show a slow instance can be suspected and recover. `SUSPECTED` is only an observation about missing timely packets; it cannot distinguish a stopped process from delay or packet loss.

To inspect the UDP datagrams on Linux, in another terminal run `sudo tcpdump -i lo -n -X udp port 9999`; on macOS use `-i lo0`. Expected: UDP packets to port 9999 with a version byte, length-prefixed UTF-8 instance id, 64-bit sequence and 64-bit timestamp. This command needs capture privileges.

## HTTP endpoints

Start the raw `ServerSocket` implementation from the repository root:

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='raw 18080'
```

To use the JDK `HttpServer` instead, replace `raw` with `jdk` in that command. Each server starts with two available seats in a local inventory; use a fresh server for this sequence. In a second terminal run these exact commands:

```sh
curl -v http://127.0.0.1:18080/events/concert-1/seats
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://127.0.0.1:18080/orders
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://127.0.0.1:18080/orders
```

Expected `curl -v` response status lines are `< HTTP/1.1 200 OK`, `< HTTP/1.1 201 Created`, and `< HTTP/1.1 409 Conflict`. The first body contains `seats` with A1 and A2, and `next: null` when the page is complete. The second body contains `"seatId":"A1"`, a generated `id`, `createdAt`, and `eventId`; their order, ID and timestamp vary. The third body is an RFC 9457 problem response with `status: 409` and `seatId: A1`. Both implementations use the same transport-independent routes. The raw parser demonstrates HTTP/1.1 framing and bounded request handling; the JDK server supplies the protocol implementation for normal use. Tests cover malformed request lines, POST without Content-Length, a body split across writes, and two requests on one keep-alive connection.

## Server executors under blocking load

The raw HTTP server assigns one worker to each connection. Choose `THREAD_PER_REQUEST` for a platform thread per connection, `FIXED_POOL` for a bounded worker pool, or `VIRTUAL` for a virtual thread per connection. The JDK HTTP server accepts the same choices; its executor dispatches HTTP exchanges rather than socket connections. The final argument adds a blocking seat lookup delay in milliseconds to stand in for a slow database call. Run one server at a time from the repository root:

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='raw 8080 FIXED_POOL 4 100'
./mvnw -q -pl load-generator exec:java -Dexec.mainClass=com.elsworne.stubline.load.LoadGenerator -Dexec.args='http://127.0.0.1:8080/events/concert-1/seats 32 10'
```

The load generator prints successful requests per second and p50/p95/p99 latency. Replace `FIXED_POOL` with the other strategies for comparison. Use `loadtest/` for the k6 run. The loopback-only admin listener uses `STUBLINE_ADMIN_PORT` (default `8081`). Its diagnostic route `GET /diagnostics/thread` returns `{"virtual":true}` for virtual workers and `false` for platform workers.

## HTTP contract and limits

`openapi.yaml` is the public OpenAPI 3.1 contract. Run `docker run --rm -v "$PWD:/spec" -w /spec redocly/cli:2.64.1@sha256:b9b4585a3bda40f77b58f5fa4535ad5ce7b7d1e4d6f8ed98ebc2c7694dbf3f5b lint openapi.yaml` to lint it. The default Maven build validates real raw and JDK HTTP responses using Atlassian OpenAPI Request Validator 3.0.0, which supports OpenAPI 3.1. Errors use RFC 9457 problem details. The request line and each header line are limited to 8192 bytes, request bodies to 1 MiB, and reads to 5 seconds. Diagnostics are only available on the loopback admin port and are never published by the public listener.

## One seat, one winner

`UnsafeInventory` demonstrates a check-then-act race: two buyers can both see an available seat. `InMemoryInventory.reserve` synchronizes the check and update, so one process admits only one buyer. `PostgresInventory` keeps that invariant across processes with two selectable strategies: `ROW_LOCK` uses `SELECT ... FOR UPDATE` inside a transaction; `OPTIMISTIC` reads a version and issues `UPDATE ... WHERE version = ? AND available = TRUE`, retrying a conflict. Flyway creates the `seats` table and HikariCP bounds JDBC connections.

```sh
./mvnw -q verify
./mvnw -q -Pdocker verify
docker compose up -d postgres
```

The default verification runs the gated in-memory race and skips the Docker-tagged PostgreSQL test. With Docker available, `-Pdocker` starts a Testcontainers PostgreSQL instance, runs 32 buyers through each database strategy, asserts exactly one winner and prints optimistic retry counts. `compose.yaml` supplies a pinned local PostgreSQL instance for manual exploration. Run the Docker profile and Compose commands on a host with Docker available.

To serve orders from PostgreSQL after `docker compose up -d postgres`, run:

```sh
STUBLINE_JDBC_URL=jdbc:postgresql://localhost:5432/stubline STUBLINE_DB_USER=stubline STUBLINE_DB_PASSWORD=stubline STUBLINE_DB_STRATEGY=ROW_LOCK ./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='jdk 8080'
```

Use `STUBLINE_DB_STRATEGY=OPTIMISTIC` to compare versioned updates. Flyway applies the migration at startup; the sample seats are inserted idempotently. Without `STUBLINE_JDBC_URL`, the server retains the synchronized in-memory inventory.

```sh
docker compose down
```

The raw server's bounded pool assigns a worker to each **connection**. With HTTP keep-alive, four workers can remain occupied by four idle or repeatedly requesting clients, while other accepted connections wait indefinitely. Successful-request p95 therefore conceals starvation. The Java generator now prints per-client completions, timeouts and clients with no completions; k6 fails requests after 2 seconds and checks the resulting failure rate. JDK `HttpServer` invokes its executor for HTTP exchanges, allowing a worker to return to the pool between requests.

`versionConflicts` counts optimistic updates that lost the version race; `retries` counts the subsequent attempts. Most of 32 buyers may read the seat only after it has sold and return immediately, so even a synchronized start does not imply 31 conflicts.

## Payment provider failures

Start the separate provider with `PROVIDER_LATENCY_MS=100 PROVIDER_ERROR_RATE=0.25 PROVIDER_OUTAGE=false ./mvnw -q -pl payment-provider-stub exec:java -Dexec.mainClass=com.elsworne.stubline.provider.PaymentProviderMain`. In another terminal, set `STUBLINE_PAYMENT_PROVIDER_URL=http://127.0.0.1:8081` when starting `ticketing-api`. `POST /payments/charge` makes one provider call; `GET /payments/charge-status` may retry twice with exponential full-jitter delays. Both use a two-second request deadline, and each call gets only the remaining time. The client logs circuit-breaker state transitions; four failures open it, further calls fail fast, and after three seconds a successful trial closes it. Charging cannot be blindly retried: an uncertain response may follow a completed charge. Safe charge retries require an idempotency key, which is added later. Run `./scripts/payment-breaker-demo.sh` to see 503 responses, fast rejection and eventual 200 recovery.

## Processes in containers

Build and start the three services with `docker compose up --build -d --wait`; stop with `docker compose down`. The two Java images use multi-stage builds and a pinned Eclipse Temurin 21 JRE, run as UID 10001, and set `-XX:MaxRAMPercentage=60`. Compose sets CPU and memory limits and checks `/health`. Inspect use with `docker stats`, `docker compose exec -T ticketing-api wget -qO- http://127.0.0.1:8081/diagnostics/runtime`, and `docker compose exec ticketing-api cat /sys/fs/cgroup/memory.max`. The endpoint reports `availableProcessors`, `maxHeapBytes`, and `containerMemoryLimitBytes` (`-1` when the cgroup file is unavailable or unlimited). BuildKit caches the Maven wrapper and dependencies under `/root/.m2`; copying POMs before sources keeps dependency resolution reusable when Java code changes. This speeds repeated builds, reduces downloads, and lowers CI cost.

## Order event formats

`OrderPlaced` lives in `ticketing-api/src/main/protobuf/order_placed.proto` and has one Java model behind `EventCodec`. It carries the order, event and seat IDs, buyer, amount in minor units, currency, timestamp, and an optional buyer note. Run `./mvnw -q -pl ticketing-api -am package` then `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.orders.events.EventSizeMain` to print the JSON and Protobuf sizes, the Protobuf hex bytes, and each field tag. For the pinned sample, the first line is `json=175 bytes protobuf=73 bytes`. In the hex stream, tags `0a`, `12`, `1a`, `22`, `28`, `32`, and `3a` identify fields 1–7; `42` identifies optional field 8 when present. Field 9 and its old name `legacy_source` are reserved and cannot be reused.

JSON fits when humans inspect messages and consumers can tolerate its larger text payload and field-name coupling. Jackson is easy to debug and needs no code generation, but renaming a field breaks old consumers unless an alias or migration is provided. `FAIL_ON_UNKNOWN_PROPERTIES` is deliberately off so an old consumer can read an event with a newly added field; missing established fields are still rejected. Protobuf fits high-volume or cross-language messaging with a shared schema: smaller bytes, stable numeric field IDs, and explicit compatibility rules. It costs the team a pinned `protoc` build step, generated code, schema review, and discipline never to reuse removed field numbers. Producers and consumers must build from the same committed `.proto` contract.

## Keyed payments

With `STUBLINE_PAYMENT_PROVIDER_URL` set to the provider origin, send `POST /payments` with `Idempotency-Key` and JSON such as `{"orderId":"order-1","amountCents":2500}`. The first request returns 201. The same key and exact body replays the stored response without charging again; a different body returns 422, and a concurrent retry returns 409. A definite provider decline before money moves releases the key for a later attempt. Unknown outcomes retain the in-flight claim for reconciliation. Completed records expire after 24 hours; in-flight records do not expire automatically. The Flyway `V2` migration records the unique key, SHA-256 body hash, state, expiry, and reply. In PostgreSQL mode it uses the existing Hikari pool; without PostgreSQL the API uses an in-memory store.

The same key travels to the provider's `POST /payments`. Its independent charge map returns the original charge for a repeated key and rejects a changed charge. The original `/payments/charge` operation still makes **one** provider call with no automatic retry: an outcome after a timeout may be unknown. A new client attempt with the **same idempotency key** is now safe, because both layers deduplicate. See the [timeout and circuit breaker discussion](#payment-provider-failures) for why an unkeyed charge is never retried automatically.

Run `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.payments.LostReplyClient`. Expected lines include `First reply timed out`, `Retry status: 201`, and `Provider charges: 1`; the demo restarts the API with an empty local store between attempts. The HTTP integration test also exercises this scenario against the payment provider's actual keyed endpoint. Run `./mvnw -q -Pdocker verify` for the PostgreSQL store contract.

## Configuration

| Name | Meaning | Default | Required |
| --- | --- | --- | --- |
| `STUBLINE_ADMIN_PORT` | Loopback diagnostics listener | `8081` | No |
| `STUBLINE_JDBC_URL` | Enable PostgreSQL inventory | In-memory if absent | No |
| `STUBLINE_DB_USER` | PostgreSQL user | None | When JDBC URL is set |
| `STUBLINE_DB_PASSWORD` | PostgreSQL password | None | When JDBC URL is set |
| `STUBLINE_DB_STRATEGY` | Reservation strategy | `ROW_LOCK` | No |
| `STUBLINE_DB_NAME` | Compose database name | `stubline` | No |
| `STUBLINE_PAYMENT_PROVIDER_URL` | Optional payment provider simulator | Disabled | No |

Run the CI checks locally with `./mvnw -q verify`; this also writes `target/bom.json`. CI scans the file system, SBOM, and locally built Compose images for fixable HIGH and CRITICAL vulnerabilities using `.trivyignore`. Renovate groups Maven, Docker image, and GitHub Actions updates once the Renovate app is installed.

Seat listing accepts `limit` (1–100, default 20) and an opaque `cursor`; the response has `seats` and `next`. Supply `next` as the next request's `cursor`. Copy `.env.example` to `.env` for local PostgreSQL use. Compose reads its database credentials from that file; the application stops at startup with a named error if PostgreSQL is enabled without credentials.

Since container resource limits were introduced, Compose probes `/health/ready`; `/health/live` reports process liveness, while readiness checks the local PostgreSQL connection without querying the payment provider. `/health` remains an alias for earlier scripts. Containers emit JSON log lines with `timestamp`, `level`, `service`, `logger`, `message` and MDC fields; local Maven runs retain readable logs. SIGTERM closes listeners and allows up to ten seconds for active requests, matching the 15-second Compose grace period.

## License

This repository is licensed under the [MIT License](LICENSE).

## About this repository

Elsworne, Stubline and the team are fictional.

The code and documents in this repository were written with the help of AI tools; every recorded output comes from actually running them.
