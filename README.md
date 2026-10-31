English | [简体中文](README.zh-CN.md)

# Stubline

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/codexary/stubline-backend)

## Run it in the browser

Open this repository with the Codespaces button, then run these commands in its terminal:

```sh
cp .env.example .env
./scripts/dev-ca.sh
docker compose up --build -d --wait
```

Open the forwarded API (8080), issuer (8443), and Grafana (3000) ports. Codespaces usage counts against your own GitHub quota.

Stubline is Elsworne's event ticketing platform for events, seats, orders, payments, notifications and e-tickets. It began as a modular monolith. The current deployment has `ticketing-api`, a PostgreSQL-backed gRPC `inventory-service`, and a Kafka `notification-service`.

## Browser sign-in (development issuer)

The development issuer now supports OAuth 2.0 authorization code with PKCE `S256` for a public browser client. Set `STUBLINE_OAUTH_CLIENT_ID`, comma-separated `STUBLINE_OAUTH_REDIRECT_URIS`, and comma-separated `STUBLINE_OAUTH_WEB_ORIGINS` in `.env` before starting Compose. Redirect URIs must match exactly. The browser client has no secret; do not put one in frontend code. These settings have no implicit defaults. The values in `.env.example` are local development examples.

The browser generates a fresh 43–128 character `code_verifier`, hashes it with SHA-256 and base64url-encodes the digest as `code_challenge`. Navigate to `https://localhost:8443/authorize?response_type=code&client_id=stubline-web&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fcallback&state=STATE&code_challenge=CHALLENGE&code_challenge_method=S256`, replacing `STATE` and `CHALLENGE` with fresh values. The sign-in form asks for a buyer subject and redirects to the exact configured callback with `code` and `state`. Check that state matches the value stored for the current browser session. Within 60 seconds, POST `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`, and `code_verifier` as `application/x-www-form-urlencoded` to `https://localhost:8443/token`. The JSON response contains an RS256 `access_token`, `token_type: Bearer`, and `expires_in: 300`; send the token as `Authorization: Bearer ...` to the API. Codes are single-use. The token endpoint permits CORS only from configured web origins. `GET /token?sub=buyer-a` remains available for local scripts and tests. This issuer and its buyer-subject form are for development only.

## Build and test

Install Java 21. The included Maven wrapper downloads Maven 3.9.16. From this repository root:

```sh
./mvnw -q clean verify
```

Expected: exit status 0; `-q` suppresses normal build output. The build runs JUnit tests and Spotless checks. To apply formatting, run `./mvnw spotless:apply`.

After checking out an older commit in one checkout, always build with `clean`: old Flyway migration class files can remain in `target/classes` and duplicate a renamed migration version.

See [the project team](docs/team.md). The UDP heartbeat, event codec, and lost-reply payment demos remain standalone. The raw and JDK seat routes, executor load example, early PostgreSQL commands, and early Compose examples were written for the in-process inventory and single-database layout; the current `TicketingMain` uses a gRPC inventory service and service-specific database credentials. Use `git log -- README.md ticketing-api/src/main/java/com/elsworne/stubline/http/TicketingMain.java compose.yaml` to find the commits that introduced those examples. For the current deployment, use the Compose commands under Remote inventory and Durable order events.

## UDP heartbeat and failure detection

The detector stays in `ticketing-api`'s `health` package because it observes these API instances and needs no separate operations deployment yet. Run these from the repository root in separate terminals:

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='detector 9999 700'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-a localhost 9999 200 3'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-b localhost 9999 200'
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
curl -v http://localhost:18080/events/concert-1/seats
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://localhost:18080/orders
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://localhost:18080/orders
```

Expected `curl -v` response status lines are `< HTTP/1.1 200 OK`, `< HTTP/1.1 201 Created`, and `< HTTP/1.1 409 Conflict`. The first body contains `seats` with A1 and A2, and `next: null` when the page is complete. The second body contains `"seatId":"A1"`, a generated `id`, `createdAt`, and `eventId`; their order, ID and timestamp vary. The third body is an RFC 9457 problem response with `status: 409` and `seatId: A1`. Both implementations use the same transport-independent routes. The raw parser demonstrates HTTP/1.1 framing and bounded request handling; the JDK server supplies the protocol implementation for normal use. Tests cover malformed request lines, POST without Content-Length, a body split across writes, and two requests on one keep-alive connection.

## Server executors under blocking load

The raw HTTP server assigns one worker to each connection. Choose `THREAD_PER_REQUEST` for a platform thread per connection, `FIXED_POOL` for a bounded worker pool, or `VIRTUAL` for a virtual thread per connection. The JDK HTTP server accepts the same choices; its executor dispatches HTTP exchanges rather than socket connections. The final argument adds a blocking seat lookup delay in milliseconds to stand in for a slow database call. Run one server at a time from the repository root:

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='raw 8080 FIXED_POOL 4 100'
./mvnw -q -pl load-generator exec:java -Dexec.mainClass=com.elsworne.stubline.load.LoadGenerator -Dexec.args='http://localhost:8080/events/concert-1/seats 32 10'
```

The load generator prints successful requests per second and p50/p95/p99 latency. Replace `FIXED_POOL` with the other strategies for comparison. Use `loadtest/` for the k6 run. The loopback-only admin listener uses `STUBLINE_ADMIN_PORT` (default `8081`). Its diagnostic route `GET /diagnostics/thread` returns `{"virtual":true}` for virtual workers and `false` for platform workers.

## HTTP contract and limits

`openapi.yaml` is the public OpenAPI 3.1 contract. Run `docker run --rm -v "$PWD:/spec" -w /spec redocly/cli:2.64.1@sha256:b9b4585a3bda40f77b58f5fa4535ad5ce7b7d1e4d6f8ed98ebc2c7694dbf3f5b lint openapi.yaml` to lint it. The default Maven build validates real raw and JDK HTTP responses using Atlassian OpenAPI Request Validator 3.0.0, which supports OpenAPI 3.1. Errors use RFC 9457 problem details. The request line and each header line are limited to 8192 bytes, request bodies to 1 MiB, and reads to 5 seconds. Diagnostics are only available on the loopback admin port and are never published by the public listener.

## One seat, one winner

`UnsafeInventory` demonstrates a check-then-act race: two buyers can both see an available seat. `InMemoryInventory.reserve` synchronizes the check and update, so one process admits only one buyer. `PostgresInventory` keeps that invariant across processes with two selectable strategies: `ROW_LOCK` uses `SELECT ... FOR UPDATE` inside a transaction; `OPTIMISTIC` reads a version and issues `UPDATE ... WHERE version = ? AND available = TRUE`, retrying a conflict. Flyway creates the `seats` table and HikariCP bounds JDBC connections.

```sh
./mvnw -q clean verify
./mvnw -q -Pdocker clean verify
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

Start the separate provider with `PROVIDER_PORT=8082 PROVIDER_LATENCY_MS=100 PROVIDER_ERROR_RATE=0.25 PROVIDER_OUTAGE=false ./mvnw -q -pl payment-provider-stub exec:java -Dexec.mainClass=com.elsworne.stubline.provider.PaymentProviderMain`. In another terminal, set `STUBLINE_PAYMENT_PROVIDER_URL=http://localhost:8082` when starting `ticketing-api`. `POST /payments/charge` makes one provider call; `GET /payments/charge-status` may retry twice with exponential full-jitter delays. Both use a two-second request deadline, and each call gets only the remaining time. The client logs circuit-breaker state transitions; four failures open it, further calls fail fast, and after three seconds a successful trial closes it. Charging cannot be blindly retried: an uncertain response may follow a completed charge. Safe charge retries require an idempotency key, which is added later. Run `./scripts/payment-breaker-demo.sh` to see 503 responses, fast rejection and eventual 200 recovery.

## Processes in containers

Build and start the three services with `docker compose up --build -d --wait`; stop with `docker compose down`. The two Java images use multi-stage builds and a pinned Eclipse Temurin 21 JRE, run as UID 10001, and set `-XX:MaxRAMPercentage=60`. Compose sets CPU and memory limits and checks `/health`. Inspect use with `docker stats`, `docker compose exec -T ticketing-api wget -qO- http://127.0.0.1:8081/diagnostics/runtime`, and `docker compose exec ticketing-api cat /sys/fs/cgroup/memory.max`. The endpoint reports `availableProcessors`, `maxHeapBytes`, and `containerMemoryLimitBytes` (`-1` when the cgroup file is unavailable or unlimited). BuildKit caches the Maven wrapper and dependencies under `/root/.m2`; copying POMs before sources keeps dependency resolution reusable when Java code changes. This speeds repeated builds, reduces downloads, and lowers CI cost.

## Order event formats

`OrderPlaced` lives in `ticketing-api/src/main/protobuf/order_placed.proto` and has one Java model behind `EventCodec`. It carries the order, event and seat IDs, buyer, amount in minor units, currency, timestamp, and an optional buyer note. Run `./mvnw -q -pl ticketing-api -am clean package` then `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.orders.events.EventSizeMain` to print the JSON and Protobuf sizes, the Protobuf hex bytes, and each field tag. For the pinned sample, the first line is `json=197 bytes protobuf=82 bytes`. In the hex stream, tags `0a`, `12`, `1a`, `22`, `28`, `32`, and `3a` identify fields 1–7; `42` identifies optional field 8 when present. Field 9 and its old name `legacy_source` are reserved and cannot be reused.

JSON fits when humans inspect messages and consumers can tolerate its larger text payload and field-name coupling. Jackson is easy to debug and needs no code generation, but renaming a field breaks old consumers unless an alias or migration is provided. `FAIL_ON_UNKNOWN_PROPERTIES` is deliberately off so an old consumer can read an event with a newly added field; missing established fields are still rejected. Protobuf fits high-volume or cross-language messaging with a shared schema: smaller bytes, stable numeric field IDs, and explicit compatibility rules. It costs the team a pinned `protoc` build step, generated code, schema review, and discipline never to reuse removed field numbers. Producers and consumers must build from the same committed `.proto` contract.

## Keyed payments

With `STUBLINE_PAYMENT_PROVIDER_URL` set to the provider origin, send `POST /payments` with `Idempotency-Key` and JSON such as `{"orderId":"order-1","amountCents":2500}`. The first request returns 201. The same key and exact body replays the stored response without charging again; a different body returns 422, and a concurrent retry returns 409. A definite provider decline before money moves releases the key for a later attempt. Unknown outcomes retain the in-flight claim for reconciliation. Completed records expire after 24 hours; in-flight records do not expire automatically. The payment Flyway migration records the unique key, SHA-256 body hash, state, expiry, and reply in its own schema history. In PostgreSQL mode it uses a dedicated Hikari pool; without PostgreSQL the API uses an in-memory store.

The same key travels to the provider's `POST /payments`. Its independent charge map returns the original charge for a repeated key and rejects a changed charge. The original `/payments/charge` operation still makes **one** provider call with no automatic retry: an outcome after a timeout may be unknown. A new client attempt with the **same idempotency key** is now safe, because both layers deduplicate. See the [timeout and circuit breaker discussion](#payment-provider-failures) for why an unkeyed charge is never retried automatically.

Run `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.payments.LostReplyClient`. Expected lines include `First reply timed out`, `Retry status: 201`, and `Provider charges: 1`; the demo restarts the API with an empty local store between attempts. The HTTP integration test also exercises this scenario against the payment provider's actual keyed endpoint. Run `./mvnw -q -Pdocker clean verify` for the PostgreSQL store contract.

Since inventory moved to its own service, before running a single-module `./mvnw -q -pl ... exec:java` command in a fresh checkout, run `./mvnw -q -DskipTests install` from the repository root. This installs sibling modules such as `inventory-api` into the local Maven repository; `verify` and `package` alone do not install them.

## Remote inventory

`inventory-api` owns the `.proto` contract and generated Java stub; `inventory-service` owns PostgreSQL seat rows and serves gRPC on port 9090. `ticketing-api` calls the generated blocking stub with a two-second deadline on **every** lookup and reservation. The stub is the proxy that remote invocation relies on: a local method call marshals a request, sends it to the remote process, and turns its reply or status into a Java result or exception. `NOT_FOUND` becomes HTTP 404, `FAILED_PRECONDITION` (taken seat) becomes 409, `DEADLINE_EXCEEDED` becomes 504, and `UNAVAILABLE` becomes 503. Unlike a plain socket call, the contract generates compatible client and server code, deadlines bound waiting, and status codes distinguish these failures. A 504 does **not** prove the reservation failed: the server may have changed the seat before the reply was lost or the deadline elapsed. Reconcile before retrying a non-idempotent operation.

Run `./scripts/dev-ca.sh`, then `docker compose up --build -d --wait` and `TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')`. Then run `curl --cacert certs/dev/public-ca.crt https://localhost:8080/events/concert-1/seats` and `curl --cacert certs/dev/public-ca.crt -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' https://localhost:8080/orders`. Expect 200 then 201; repeating the order returns 409. Check the standard gRPC health service with `docker compose ps inventory-service`. `./mvnw -q -Pdocker clean verify` also exercises real gRPC transport and PostgreSQL through Testcontainers. Local HTTP demos now require the inventory service (`STUBLINE_INVENTORY_TARGET=localhost:9090`); the in-memory inventory remains for isolated tests.

## Durable order events

In PostgreSQL mode, `POST /orders` writes an order and its Protobuf `OrderPlaced` payload to `order_outbox` in one transaction. The relay selects pending rows with `FOR UPDATE SKIP LOCKED`, sends them to Kafka's `order-placed` topic with the **concert ID as the partition key**, and then marks them published. Publishing directly after the order commit would leave a crash window in which the order exists but its event is lost. The outbox removes that window. A crash after Kafka accepts the event but before the relay marks the row may publish it again, so delivery is **at least once**, not exactly once. Kafka preserves order within a partition; all orders for one event use the same key and therefore retain their order there. It does not order different events globally.

`notification-service` consumes in group `stubline-notifications`, writes the handled message ID and a notification row in one database transaction, logs `Sending e-ticket ...` only for a newly handled ID, and commits its Kafka offset after handling. A crash before offset commit causes redelivery; the unique message ID drops the duplicate. The Docker-tagged test injects exactly that crash and restarts the consumer. The logged send is a demonstration; an external email provider would require its own idempotency key or outbox to close the side-effect gap.

Run `docker compose up --build -d --wait`, create an order with the order-creation `curl` command under Remote inventory, then `docker compose logs notification-service` to see one `Sending e-ticket ...` line. Inspect lag with `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group stubline-notifications`; check `CURRENT-OFFSET`, `LOG-END-OFFSET`, and `LAG` per partition. Compose creates `order-placed` explicitly with three partitions before the producer and consumer start; broker auto-creation is disabled. On a fresh stack (`docker compose down -v` first), run `./scripts/kafka-partitions-demo.sh`. Inventory seeds `concert-1` A1/A2 and `concert-2` B1/B2 when it starts, so all four demo orders have seats. Expected: four records with keys `concert-1` and `concert-2`; both records for each key have the same printed partition (the two keys may hash to the same partition). The group description lists partitions 0, 1 and 2, each with `LAG` 0. Run `./mvnw -q -Pdocker clean verify` for the relay, consumer, and crash/redelivery tests.


## Database ownership after the outbox integration

Compose creates three PostgreSQL databases: `inventory`, `ticketing`, and `notifications`, each owned by a different login. Inventory owns seat rows, ticketing owns payment decisions and the order outbox, and notifications owns delivery and deduplication rows. Services exchange gRPC calls and Kafka events instead of reading one another's tables. This keeps migrations, permissions, and failures within a service boundary. The two ticketing migrations share one Flyway history so neither encounters an already populated schema without its own history.

Run `./scripts/verify-tags.sh` to check all release tags in an isolated worktree with `./mvnw -q clean verify`. With no release tags, it prints `No release tags found` and exits 1. Docker tests are separate: `./mvnw -q -Pdocker clean verify`. For a clean Compose database, run `docker compose down -v` before `docker compose up --build -d --wait`; PostgreSQL init scripts run only when its data directory is new.

## E-ticket objects

After `POST /orders` reserves a seat, `POST /payments` with that order ID and an `Idempotency-Key` charges the buyer. A successful payment renders a one-page PDF containing the order ID, event, seat, and a deterministic QR-like visual mark. The API uploads it under `tickets/<order-id>.pdf` in the `stubline-tickets` bucket and conditionally records that key with the order while holding its database row lock. Concurrent issuers wait, then skip the upload once the key exists. If the process crashes after upload but before commit, the deterministic object key may be orphaned; a later retry replaces that same object and records the key. `GET /orders/{id}/ticket` returns `{"url":"..."}` for a paid order and 404 for an unknown or unpaid order. The signed GET URL lasts five minutes; sharing it delegates read access to that one object until expiry, without disclosing S3 credentials. Whoever has the URL can use it during that interval.

Start with `./scripts/dev-ca.sh` and `docker compose up --build -d --wait`; set `TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')`. Create an order using the order-creation command under Durable order events and copy its `id`. Then run `curl --cacert certs/dev/public-ca.crt -i -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: ticket-demo-1' -H 'Content-Type: application/json' -d '{"orderId":"<id>","amountCents":2500}' https://localhost:8080/payments`, followed by `curl --cacert certs/dev/public-ca.crt -s -H "Authorization: Bearer $TOKEN" https://localhost:8080/orders/<id>/ticket`. Expect 201 then a JSON `url` pointing to `http://localhost:8333/stubline-tickets/tickets/<id>.pdf` with `X-Amz-Expires=300`; downloading that URL returns a PDF beginning `%PDF-1.4`. A missing ID returns 404. Run `./mvnw -q -Pdocker clean verify` for a real SeaweedFS presigned PUT upload, signed download, and expired-URL rejection.

The local presigned ticket URL is plain HTTP because the local SeaweedFS endpoint is HTTP; a production object store is reached over HTTPS. A ticket is a file with a separate lifecycle and read pattern from order rows. Object storage handles its bytes, access delegation, and scaling without enlarging the transactional database or requiring every API replica to mount the same directory. PostgreSQL retains the authoritative order and object key. The application uses only S3 operations, so set `STUBLINE_S3_BUCKET`, `STUBLINE_S3_REGION`, and standard AWS credentials for AWS S3; omit endpoint overrides there. For SeaweedFS set `STUBLINE_S3_ENDPOINT`, `STUBLINE_S3_PUBLIC_ENDPOINT`, and `STUBLINE_S3_PATH_STYLE=true`. The public endpoint must resolve from the buyer's browser because its host is part of the signature.

A distributed file system such as NFS exposes file paths and client caches, often with close-to-open and attribute-cache freshness rules. The S3 API exposes whole-object PUT and GET and prefix listing instead of POSIX open/write/close. Treat a successful PUT as the publication point for a new immutable ticket key. Avoid overwriting issued tickets: caches and gateways can retain an old representation, and listing visibility and overwrite behaviour vary by S3-compatible implementation. Fetch by the recorded exact key rather than using a listing as the source of truth.

## Names and discovery

Compose gives each service a DNS name on its internal network. Ticketing connects to `inventory-service:9090`, `payment-provider-stub:8081`, `kafka:9092`, `postgres:5432`, and `seaweedfs:8333` by name, with each address configurable through its environment variable (`STUBLINE_INVENTORY_HOST` and `STUBLINE_INVENTORY_PORT`, `STUBLINE_PAYMENT_PROVIDER_URL`, `STUBLINE_KAFKA_BOOTSTRAP`, `STUBLINE_JDBC_URL`, and `STUBLINE_S3_ENDPOINT`). `localhost` in a container means that container, so it is used only by local health probes. The inventory host port is not published, allowing `docker compose up --scale inventory-service=2`.

Run `./scripts/dns-discovery-demo.sh` against a running or fresh stack. It starts with one inventory replica, scales to two without restarting `ticketing-api`, runs the pinned client again after scale-up, waits 25 seconds for connection retirement and DNS refresh, then sends 24 requests and prints the actual count served by each replica. Recorded: the second `/diagnostics/dns?name=inventory-service` returned `addresses:["172.22.0.9","172.22.0.6"]` and `effectiveTtl:"5"`. The two addresses are allocated by Docker and will vary. Each pinned-client run prints one selected address and completes its requests through that address, including the run after Compose DNS returns two addresses. One run recorded 11 and 13 requests served, totaling 24. Addresses and the split vary by run.

`JAVA_TOOL_OPTIONS=-Dsun.net.inetaddr.ttl=5` limits the JVM's positive DNS cache in the ticketing container. the admin listener’s `GET /diagnostics/dns?name=<host>` reports the JVM lookup and effective TTL from `networkaddress.cache.ttl` (or the JVM's `sun.net.inetaddr.ttl` fallback). A client that resolves a name once into an address and builds a channel to that address stays pinned regardless of later DNS changes. The normal gRPC channel uses the target name and `round_robin`, allowing its DNS resolver to refresh address sets and distribute calls. DNS TTL alone does not rebalance an already established connection.

The 5-second JVM DNS TTL prevents stale addresses from surviving indefinitely, but gRPC re-resolves on connection events, not on TTL expiry alone. The inventory server retires connections after 15 seconds with a 5-second grace period, prompting the named `round_robin` channel to reconnect and discover added replicas. This short age makes the demo quick; production usually uses a much longer age (for example 30 minutes, adjusted to deployment and connection-draining needs). Retirement costs extra TLS handshakes, connection setup, DNS lookups and brief load redistribution; grace lets in-flight RPCs finish.

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
| `STUBLINE_TLS_KEYSTORE` | API TLS keystore file | None | With HTTPS |
| `STUBLINE_TLS_PASSWORD` | TLS keystore password | None | With HTTPS |
| `STUBLINE_JWKS_URI` | Buyer token key set URL | Disabled | No |
| `STUBLINE_JWKS_CA` | CA for JWKS TLS | System trust | No |
| `STUBLINE_JWT_ISSUER` | Expected issuer | Development issuer | With JWKS |
| `STUBLINE_CORS_ORIGINS` | Allowed browser origins | Off | No |

Run the CI checks locally with `./mvnw -q verify`; this also writes `target/bom.json`. CI scans the file system, SBOM, and locally built Compose images for fixable HIGH and CRITICAL vulnerabilities using `.trivyignore`. Renovate groups Maven, Docker image, and GitHub Actions updates once the Renovate app is installed.

Seat listing accepts `limit` (1–100, default 20) and an opaque `cursor`; the response has `seats` and `next`. Supply `next` as the next request's `cursor`. Copy `.env.example` to `.env` for local PostgreSQL use. Compose reads its database credentials from that file; the application stops at startup with a named error if PostgreSQL is enabled without credentials.

Since container resource limits were introduced, Compose probes `/health/ready`; `/health/live` reports process liveness, while readiness checks the local PostgreSQL connection without querying the payment provider. `/health` remains an alias for earlier scripts. Containers emit JSON log lines with `timestamp`, `level`, `service`, `logger`, `message` and MDC fields; local Maven runs retain readable logs. On SIGTERM, ticketing stops the public listener and lets active requests finish for up to ten seconds, then closes the admin listener. It stops the outbox worker and waits up to 15 seconds for the current batch to commit or roll back; on timeout it interrupts the batch before closing the Kafka producer, ticket storage, database pools, and remote clients. Each step is logged. The ticketing container has a 40-second stop grace period; any row left unpublished after interruption is retried on restart.

When inventory moved to its own service, the inventory and payment stores start independent Flyway histories (`inventory_flyway_history` and `payment_flyway_history`). The original `V1` and `V2` files remain under `ticketing-api/src/main/resources/db/migration` as the original database history; their checksums do not change. This example deployment starts with empty service data and inserts only the sample seats. For a live rollout, stop writes, copy `seats` to the inventory service database and `payment_idempotency` to the payment database, verify row counts and keys, then switch traffic. New service migrations start at V1 because their history tables are new.

The inventory extraction separates `inventory` and `ticketing` databases and users; the outbox integration adds `notifications`. Each service owns its tables and Flyway history. The local development password comes from `.env`; there is no password fallback in application code or Compose. Database startup fails with the missing environment variable name. An existing shared database needs a one-off copy of seats into `inventory` and payments into `ticketing` before switching traffic; this demo starts empty and populates sample seats. Ticketing's new Flyway history starts at V1 (payment) and grows to V2 (orders outbox), while the archived original migration files retain their original numbers and checksums.

## Transport security and buyer tokens

Run `./scripts/dev-ca.sh` before Compose. It creates two short-lived development CAs under ignored `certs/dev/`: a public API CA and a separate internal gRPC CA. The server certificates include the Compose DNS names and `localhost` as SANs. The script exports PKCS#12 keystores and mounts only the certificates and keys each service needs, read-only. Mounted private keys and keystores have mode 600 and belong to the service UID 10001. A real team obtains certificates from its approved CA, protects private keys in a secrets manager, rotates them before expiry, and never commits them.

```sh
./scripts/dev-ca.sh
docker compose up --build -d --wait
TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')
curl --cacert certs/dev/public-ca.crt https://localhost:8080/health
curl --cacert certs/dev/public-ca.crt -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' https://localhost:8080/orders
curl --cacert certs/dev/public-ca.crt -i -X POST -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A2"}' https://localhost:8080/orders
./mvnw -q -pl ticketing-api -am install -DskipTests
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.JwtAnatomy -Dexec.args="$TOKEN https://localhost:8443/.well-known/jwks.json certs/dev/public-ca.crt"
```

The health request returns `{"status":"ok"}`. The order request returns 201 and an order ID. The request without a bearer token returns 401 with `WWW-Authenticate: Bearer realm="stubline"`. `JwtAnatomy` prints the decoded header, claims and `signature valid: true`; it uses only JDK cryptography for learning. The product uses Nimbus JOSE + JWT for validation. To observe rotation, run `curl --cacert certs/dev/public-ca.crt -X POST https://localhost:8443/rotate`, request a fresh token and call the API with it. The API fetches the new `kid` from JWKS.

TLS encrypts traffic, detects modification and authenticates the server through a trusted certificate and matching host name. It does not identify the buyer or authorize an order. Internal gRPC also requires client certificates so inventory accepts only services trusted by the internal CA. A JWT has an encoded header, claims and signature; its claims are readable, not encrypted. The signature, pinned RS256 algorithm, `iss`, `aud`, `exp`, `nbf` and `sub` checks limit forgery and misuse. Bearer tokens can be used by whoever steals them; HTTPS, short lifetimes, careful storage and revocation/rotation procedures reduce exposure. The development issuer's unauthenticated `/token` endpoint is for local use only.


The later ticketing migrations `V3` (ticket key), `V4` (buyer subject), and `V5` (outbox trace context) each add a nullable column. Existing rows and the running previous version remain valid; backfill and later constraints would be separate migrations after all instances have upgraded. The release workflow adds OpenTelemetry, drills, and release images without a new public API route.

## Release, telemetry and failure drills


Only browser and script endpoints are published on loopback: HTTPS API 8080, issuer 8443, provider controls 8081, SeaweedFS 8333, Grafana 3000, Prometheus 9091, Tempo 3200, and PostgreSQL 5432 for the local Java examples. OTLP stays inside Compose. The five Java service images attach the pinned OpenTelemetry Java agent 2.33.1, fetched during image build with SHA-256 verification. The Collector 0.164.0 receives OTLP traces, metrics and logs and forwards them to Grafana LGTM 0.38.1. Open `http://localhost:3000` and the provisioned **Stubline service health** dashboard. HTTP, gRPC, Kafka, JDBC and JVM work is instrumented automatically. `order.place`, `outbox.publish` and `notification.handle` add business meaning. The order span's `traceparent` is stored with the outbox row; the relay restores it before Kafka publish so the notification stays in the same trace. Run `./scripts/trace-check.sh` after starting the stack. It places an order and prints the trace ID and actual spans only when one trace includes the API HTTP and order spans, inventory gRPC, outbox publish, and notification handling. Search that trace ID in Tempo at `http://localhost:3200` to follow HTTP → inventory gRPC → database/outbox → Kafka → notification. Logs include `trace_id` and `span_id`. Watch request rate, 5xx and p95 latency by route, outbox backlog, consumer lag, breaker state and active Hikari connections.

Build a release from the final tagged commit. Each service image is built once and given version, commit and combined tags pointing to the same image. OCI labels record source, version and revision. `.env.release` is ignored. `compose.release.yaml` selects those images, disables builds at startup, adds restart and resource policies, and runs two inventory replicas.

```sh
./scripts/dev-ca.sh
./scripts/build-release.sh 1.1.0
set -a; source .env.release; set +a
docker compose -f compose.yaml -f compose.release.yaml up -d --no-build --wait --scale inventory-service=2
./scripts/deployed.sh
```

Release checklist: verify what the operator sees in Grafana: open the **Stubline service health** dashboard in the Stubline folder and inspect all six panels after the scripted check passes.

`deployed.sh` also runs `check-dashboards.sh`. Each of the six panels must return a Prometheus series; a value of zero is valid, including for HTTP errors. The request-rate panel must also show traffic on a business route such as `/events`, not only on `/`. The check fails if the dashboard, datasource, panel, or series is missing. While it waits, the check sends one `GET /events` per second: `rate()` needs the counter to grow between two exported samples, and on a fresh stack a short burst creates the series at its final value, so its rate would stay zero.

Each drill starts a fresh ephemeral stack (`down -v` removes its demo data), runs pinned k6 2.3.0 at two virtual users, injects and recovers a failure, prints timestamped statuses and telemetry, asserts the outcome, and exits nonzero on failure. Run them one at a time after building the release:

```sh
./scripts/drills/inventory-replica.sh
./scripts/drills/kafka-outage.sh
./scripts/drills/notification-crash.sh
./scripts/drills/provider-breaker.sh
```

- **Inventory replica:** one container is killed. Expect requests to continue on the surviving replica and request-rate telemetry to remain present. On-call checks ready replica count, gRPC deadline errors and per-replica traffic first.
- **Kafka outage:** the broker is stopped for 60 seconds. Expect an order accepted, outbox backlog above zero, then backlog and consumer lag back to zero and exactly one notification. On-call checks broker health, pending outbox rows and consumer offsets first.
- **Notification crash:** the consumer pauses after durable handling and before offset commit, then is killed. Expect redelivery, a logged duplicate drop and one notification row. On-call checks restart count, consumer lag and the deduplication log first.
- **Payment provider:** provider outage drives four or more 503s and opens the breaker; recovery closes it and the next call returns 200. On-call checks provider errors, breaker state and timeout traces first.

The backup and restore drill (`./scripts/drills/backup-restore.sh`) takes one PostgreSQL snapshot per service database, restores each into a fresh database, compares order, payment and notification row fingerprints, and prints elapsed seconds. Its recovery point is each database’s dump time; writes after that point are lost on restore, and the three snapshots are not cross-service atomic. The printed duration measures dump and restore work, not service restart or Kafka replay, so it is a drill measurement rather than a production recovery-time guarantee.

Each drill prints timestamped statuses and metrics and exits nonzero if its checks fail. The kind node-failure comparison is in `stubline-spike-kind-failover`.

## Buyer event and order lookup

`GET /events` lists the development catalog with `limit` (1–100, default 20), an opaque `cursor`, and `events`/`next` in the response. The sample catalog contains `concert-1`; its seats are seeded by the inventory service. `GET /orders/{id}` requires a buyer token and returns the order's `id`, `eventId`, `seatId` and `createdAt`. A missing order or one belonging to another buyer returns the same 404 Problem Details response.

From the repository root, after `./scripts/dev-ca.sh` and `docker compose up --build -d --wait`, run:

```sh
curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8080/events?limit=20'
TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')
ORDER_ID=$(curl --cacert certs/dev/public-ca.crt -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' https://localhost:8080/orders | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl --cacert certs/dev/public-ca.crt -i -H "Authorization: Bearer $TOKEN" "https://localhost:8080/orders/$ORDER_ID"
OTHER_TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-b')
curl --cacert certs/dev/public-ca.crt -i -H "Authorization: Bearer $OTHER_TOKEN" "https://localhost:8080/orders/$ORDER_ID"
```

Expect a 200 event page, a 200 order with the same ID, then 404 with `application/problem+json`. Use a clean demo database or choose an available seat if `A1` was already sold.

### Limitations

`POST /payments` accepts `amountCents` from the client; this demonstration does not derive or verify the price from a server-side order total.

## License

This repository is licensed under the [MIT License](LICENSE).

## About this repository

Elsworne, Stubline and the team are fictional.

The code and documents in this repository were written with the help of AI tools; every recorded output comes from actually running them.
