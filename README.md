English | [简体中文](README.zh-CN.md)

# Stubline

Stubline is Elsworne's event ticketing platform for events, seats, orders, payments, notifications and e-tickets. It began as a modular monolith. The current deployment has `ticketing-api`, a PostgreSQL-backed gRPC `inventory-service`, and a Kafka `notification-service`.

## Build and test

Install Java 21. The included Maven wrapper downloads Maven 3.9.16. From this repository root:

```sh
./mvnw -q verify
```

Expected: exit status 0; `-q` suppresses normal build output. The build runs JUnit tests and Spotless checks. To apply formatting, run `./mvnw spotless:apply`.

See [the project team](docs/team.md). The UDP heartbeat, event codec, and lost-reply payment demos remain standalone. The raw and JDK seat routes, executor load example, early PostgreSQL commands, and early Compose examples were written for the in-process inventory and single-database layout; the current `TicketingMain` uses a gRPC inventory service and service-specific database credentials. Use `git log -- README.md ticketing-api/src/main/java/com/elsworne/stubline/http/TicketingMain.java compose.yaml` to find the commits that introduced those examples. For the current deployment, use the Compose commands under Remote inventory and Durable order events.

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

`OrderPlaced` lives in `ticketing-api/src/main/protobuf/order_placed.proto` and has one Java model behind `EventCodec`. It carries the order, event and seat IDs, buyer, amount in minor units, currency, timestamp, and an optional buyer note. Run `./mvnw -q -pl ticketing-api -am package` then `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.orders.events.EventSizeMain` to print the JSON and Protobuf sizes, the Protobuf hex bytes, and each field tag. For the pinned sample, the first line is `json=197 bytes protobuf=82 bytes`. In the hex stream, tags `0a`, `12`, `1a`, `22`, `28`, `32`, and `3a` identify fields 1–7; `42` identifies optional field 8 when present. Field 9 and its old name `legacy_source` are reserved and cannot be reused.

JSON fits when humans inspect messages and consumers can tolerate its larger text payload and field-name coupling. Jackson is easy to debug and needs no code generation, but renaming a field breaks old consumers unless an alias or migration is provided. `FAIL_ON_UNKNOWN_PROPERTIES` is deliberately off so an old consumer can read an event with a newly added field; missing established fields are still rejected. Protobuf fits high-volume or cross-language messaging with a shared schema: smaller bytes, stable numeric field IDs, and explicit compatibility rules. It costs the team a pinned `protoc` build step, generated code, schema review, and discipline never to reuse removed field numbers. Producers and consumers must build from the same committed `.proto` contract.

## Keyed payments

With `STUBLINE_PAYMENT_PROVIDER_URL` set to the provider origin, send `POST /payments` with `Idempotency-Key` and JSON such as `{"orderId":"order-1","amountCents":2500}`. The first request returns 201. The same key and exact body replays the stored response without charging again; a different body returns 422, and a concurrent retry returns 409. A definite provider decline before money moves releases the key for a later attempt. Unknown outcomes retain the in-flight claim for reconciliation. Completed records expire after 24 hours; in-flight records do not expire automatically. The payment Flyway migration records the unique key, SHA-256 body hash, state, expiry, and reply in its own schema history. In PostgreSQL mode it uses a dedicated Hikari pool; without PostgreSQL the API uses an in-memory store.

The same key travels to the provider's `POST /payments`. Its independent charge map returns the original charge for a repeated key and rejects a changed charge. The original `/payments/charge` operation still makes **one** provider call with no automatic retry: an outcome after a timeout may be unknown. A new client attempt with the **same idempotency key** is now safe, because both layers deduplicate. See the [timeout and circuit breaker discussion](#payment-provider-failures) for why an unkeyed charge is never retried automatically.

Run `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.payments.LostReplyClient`. Expected lines include `First reply timed out`, `Retry status: 201`, and `Provider charges: 1`; the demo restarts the API with an empty local store between attempts. The HTTP integration test also exercises this scenario against the payment provider's actual keyed endpoint. Run `./mvnw -q -Pdocker verify` for the PostgreSQL store contract.

Since inventory moved to its own service, before running a single-module `./mvnw -q -pl ... exec:java` command in a fresh checkout, run `./mvnw -q -DskipTests install` from the repository root. This installs sibling modules such as `inventory-api` into the local Maven repository; `verify` and `package` alone do not install them.

## Remote inventory

`inventory-api` owns the `.proto` contract and generated Java stub; `inventory-service` owns PostgreSQL seat rows and serves gRPC on port 9090. `ticketing-api` calls the generated blocking stub with a two-second deadline on **every** lookup and reservation. The stub is the proxy that remote invocation relies on: a local method call marshals a request, sends it to the remote process, and turns its reply or status into a Java result or exception. `NOT_FOUND` becomes HTTP 404, `FAILED_PRECONDITION` (taken seat) becomes 409, `DEADLINE_EXCEEDED` becomes 504, and `UNAVAILABLE` becomes 503. Unlike a plain socket call, the contract generates compatible client and server code, deadlines bound waiting, and status codes distinguish these failures. A 504 does **not** prove the reservation failed: the server may have changed the seat before the reply was lost or the deadline elapsed. Reconcile before retrying a non-idempotent operation.

Run `docker compose up --build -d --wait`, then `curl http://localhost:8080/events/concert-1/seats` and `curl -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://localhost:8080/orders`. Expect 200 then 201; repeating the order returns 409. Check the standard gRPC health service with `docker compose ps inventory-service`. `./mvnw -q -Pdocker verify` also exercises real gRPC transport and PostgreSQL through Testcontainers. Local HTTP demos now require the inventory service (`STUBLINE_INVENTORY_TARGET=localhost:9090`); the in-memory inventory remains for isolated tests.

## Durable order events

In PostgreSQL mode, `POST /orders` writes an order and its Protobuf `OrderPlaced` payload to `order_outbox` in one transaction. The relay selects pending rows with `FOR UPDATE SKIP LOCKED`, sends them to Kafka's `order-placed` topic with the **concert ID as the partition key**, and then marks them published. Publishing directly after the order commit would leave a crash window in which the order exists but its event is lost. The outbox removes that window. A crash after Kafka accepts the event but before the relay marks the row may publish it again, so delivery is **at least once**, not exactly once. Kafka preserves order within a partition; all orders for one event use the same key and therefore retain their order there. It does not order different events globally.

`notification-service` consumes in group `stubline-notifications`, writes the handled message ID and a notification row in one database transaction, logs `Sending e-ticket ...` only for a newly handled ID, and commits its Kafka offset after handling. A crash before offset commit causes redelivery; the unique message ID drops the duplicate. The Docker-tagged test injects exactly that crash and restarts the consumer. The logged send is a demonstration; an external email provider would require its own idempotency key or outbox to close the side-effect gap.

Run `docker compose up --build -d --wait`, create an order with the order-creation `curl` command under Remote inventory, then `docker compose logs notification-service` to see one `Sending e-ticket ...` line. Inspect lag with `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group stubline-notifications`; check `CURRENT-OFFSET`, `LOG-END-OFFSET`, and `LAG` per partition. Compose creates `order-placed` explicitly with three partitions before producer and consumer startup; broker auto-creation is disabled. For a fresh stack, run `docker compose down -v` and `./scripts/kafka-partitions-demo.sh`. Inventory seeds `concert-1` A1/A2 and `concert-2` B1/B2 when it starts, so all four demo orders have seats. Four records use `concert-1` and `concert-2` keys, each key staying on one partition. The group reports partitions 0–2 with zero lag. Run `./mvnw -q -Pdocker verify` for the relay, consumer, and crash/redelivery tests.


## Database ownership after the outbox integration

Compose creates three PostgreSQL databases: `inventory`, `ticketing`, and `notifications`, each owned by a different login. Inventory owns seat rows, ticketing owns payment decisions and the order outbox, and notifications owns delivery and deduplication rows. Services exchange gRPC calls and Kafka events instead of reading one another's tables. This keeps migrations, permissions, and failures within a service boundary. The two ticketing migrations share one Flyway history so neither encounters an already populated schema without its own history.

Run `./scripts/verify-tags.sh` to check every `chapter-NN` tag in an isolated worktree with `./mvnw -q verify`. Docker tests are separate: `./mvnw -q -Pdocker verify`. For a clean Compose database, run `docker compose down -v` before `docker compose up --build -d --wait`; PostgreSQL init scripts run only when its data directory is new.

## E-ticket objects

After `POST /orders` reserves a seat, `POST /payments` with that order ID and an `Idempotency-Key` charges the buyer. A successful payment renders a one-page PDF containing the order ID, event, seat, and a deterministic QR-like visual mark. The API uploads it under `tickets/<order-id>.pdf` in the `stubline-tickets` bucket and conditionally records that key with the order while holding its database row lock. Concurrent issuers wait, then skip the upload once the key exists. If the process crashes after upload but before commit, the deterministic object key may be orphaned; a later retry replaces that same object and records the key. `GET /orders/{id}/ticket` returns `{"url":"..."}` for a paid order and 404 for an unknown or unpaid order. The signed GET URL lasts five minutes; sharing it delegates read access to that one object until expiry, without disclosing S3 credentials. Whoever has the URL can use it during that interval.

Start with `docker compose up --build -d --wait`. Create an order using the order-creation command under Durable order events and copy its `id`. Then run `curl -i -H 'Idempotency-Key: ticket-demo-1' -H 'Content-Type: application/json' -d '{"orderId":"<id>","amountCents":2500}' http://localhost:8080/payments`, followed by `curl -s http://localhost:8080/orders/<id>/ticket`. Expect 201 then a JSON `url` pointing to `http://localhost:8333/stubline-tickets/tickets/<id>.pdf` with `X-Amz-Expires=300`; downloading that URL returns a PDF beginning `%PDF-1.4`. A missing ID returns 404. Run `./mvnw -q -Pdocker verify` for a real SeaweedFS presigned PUT upload, signed download, and expired-URL rejection.

A ticket is a file with a separate lifecycle and read pattern from order rows. Object storage handles its bytes, access delegation, and scaling without enlarging the transactional database or requiring every API replica to mount the same directory. PostgreSQL retains the authoritative order and object key. The application uses only S3 operations, so set `STUBLINE_S3_BUCKET`, `STUBLINE_S3_REGION`, and standard AWS credentials for AWS S3; omit endpoint overrides there. For SeaweedFS set `STUBLINE_S3_ENDPOINT`, `STUBLINE_S3_PUBLIC_ENDPOINT`, and `STUBLINE_S3_PATH_STYLE=true`. The public endpoint must resolve from the buyer's browser because its host is part of the signature.

A distributed file system such as NFS exposes file paths and client caches, often with close-to-open and attribute-cache freshness rules. The S3 API exposes whole-object PUT and GET and prefix listing instead of POSIX open/write/close. Treat a successful PUT as the publication point for a new immutable ticket key. Avoid overwriting issued tickets: caches and gateways can retain an old representation, and listing visibility and overwrite behaviour vary by S3-compatible implementation. Fetch by the recorded exact key rather than using a listing as the source of truth.

## Configuration

| Name | Meaning | Default | Required |
| --- | --- | --- | --- |
| `STUBLINE_ADMIN_PORT` | Loopback diagnostics listener | `8081` | No |
| `STUBLINE_JDBC_URL` | Service database JDBC URL | In-memory for API if absent | Required for inventory and notification services |
| `STUBLINE_DB_USER` | PostgreSQL user | None | When JDBC URL is set |
| `STUBLINE_DB_PASSWORD` | PostgreSQL password | None | When JDBC URL is set |
| `STUBLINE_DB_STRATEGY` | Reservation strategy | `ROW_LOCK` | No |
| `STUBLINE_DB_NAME` | earlier single-database Compose name | `stubline` | No |
| `STUBLINE_PAYMENT_PROVIDER_URL` | Optional payment provider simulator | Disabled | No |
| `STUBLINE_INVENTORY_TARGET` | Inventory gRPC target | `localhost:9090` | No |
| `STUBLINE_INVENTORY_PORT` | Inventory service gRPC listener | `9090` | No |
| `STUBLINE_S3_BUCKET` | Ticket object bucket | None | When ticket storage is enabled |
| `STUBLINE_S3_ENDPOINT` | Local S3-compatible endpoint | AWS default | No |
| `STUBLINE_S3_PUBLIC_ENDPOINT` | Browser-accessible signed URL origin | S3 endpoint | No |
| `STUBLINE_S3_REGION` | S3 region | `us-east-1` | No |
| `STUBLINE_S3_PATH_STYLE` | S3 path-style requests | `true` | No |
| `AWS_ACCESS_KEY_ID` | Local S3 access key | None | With local S3 endpoint |
| `AWS_SECRET_ACCESS_KEY` | Local S3 secret key | None | With local S3 endpoint |

Run the CI checks locally with `./mvnw -q verify`; this also writes `target/bom.json`. CI scans the file system, SBOM, and locally built Compose images for fixable HIGH and CRITICAL vulnerabilities using `.trivyignore`. Renovate groups Maven, Docker image, and GitHub Actions updates once the Renovate app is installed.

Seat listing accepts `limit` (1–100, default 20) and an opaque `cursor`; the response has `seats` and `next`. Supply `next` as the next request's `cursor`. Copy `.env.example` to `.env` for local PostgreSQL use. Compose reads its database credentials from that file; the application stops at startup with a named error if PostgreSQL is enabled without credentials.

Since container resource limits were introduced, Compose probes `/health/ready`; `/health/live` reports process liveness, while readiness checks the local PostgreSQL connection without querying the payment provider. `/health` remains an alias for earlier scripts. Containers emit JSON log lines with `timestamp`, `level`, `service`, `logger`, `message` and MDC fields; local Maven runs retain readable logs. SIGTERM closes listeners and allows up to ten seconds for active requests, matching the 15-second Compose grace period.

When inventory moved to its own service, the inventory and payment stores start independent Flyway histories (`inventory_flyway_history` and `payment_flyway_history`). The original `V1` and `V2` files remain under `ticketing-api/src/main/resources/db/migration` as the original database history; their checksums do not change. This example deployment starts with empty service data and inserts only the sample seats. For a live rollout, stop writes, copy `seats` to the inventory service database and `payment_idempotency` to the payment database, verify row counts and keys, then switch traffic. New service migrations start at V1 because their history tables are new.

The inventory extraction separates `inventory` and `ticketing` databases and users; the outbox integration adds `notifications`. Each service owns its tables and Flyway history. The local development password comes from `.env`; there is no password fallback in application code or Compose. Database startup fails with the missing environment variable name. An existing shared database needs a one-off copy of seats into `inventory` and payments into `ticketing` before switching traffic; this demo starts empty and populates sample seats. Ticketing's new Flyway history starts at V1 (payment) and grows to V2 (orders outbox), while the archived original migration files retain their original numbers and checksums.

## License

This repository is licensed under the [MIT License](LICENSE).

## About this repository

Elsworne, Stubline and the team are fictional.

The code and documents in this repository were written with the help of AI tools; every recorded output comes from actually running them.
