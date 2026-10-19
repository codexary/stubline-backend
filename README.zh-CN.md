[English](README.md) | 简体中文

# Stubline

Stubline 是 Elsworne 的活动票务平台，涵盖活动、座位、订单、支付、通知和电子票。最初采用 modular monolith。当前部署包括 `ticketing-api`、由 PostgreSQL 支持的 gRPC `inventory-service`，以及 Kafka `notification-service`。

## 浏览器登录（开发用 issuer）

开发用 issuer 现在为 public browser client 提供 OAuth 2.0 authorization code + PKCE `S256`。启动 Compose 前，在 `.env` 中设置 `STUBLINE_OAUTH_CLIENT_ID`、以逗号分隔的 `STUBLINE_OAUTH_REDIRECT_URIS` 和 `STUBLINE_OAUTH_WEB_ORIGINS`。redirect URI 必须完全匹配。浏览器客户端没有 secret，不要在前端代码中放置 secret。这些配置没有隐式默认值；`.env.example` 中的值只是本地开发示例。

浏览器生成新的 43–128 字符 `code_verifier`，对它求 SHA-256 并使用 base64url 编码，得到 `code_challenge`。打开 `https://localhost:8443/authorize?response_type=code&client_id=stubline-web&redirect_uri=http%3A%2F%2Flocalhost%3A5173%2Fcallback&state=STATE&code_challenge=CHALLENGE&code_challenge_method=S256`，将 `STATE` 和 `CHALLENGE` 替换为新值。登录表单要求填写 buyer subject，然后重定向到精确配置的 callback，带回 `code` 和 `state`。检查 state 与当前浏览器会话保存的值一致。在 60 秒内，将 `grant_type=authorization_code`、`code`、`redirect_uri`、`client_id` 和 `code_verifier` 以 `application/x-www-form-urlencoded` 格式 POST 到 `https://localhost:8443/token`。JSON 响应包含 RS256 `access_token`、`token_type: Bearer` 和 `expires_in: 300`；使用 `Authorization: Bearer ...` 向 API 发请求。code 只能使用一次。token 端点只允许配置的 web origin 使用 CORS。`GET /token?sub=buyer-a` 仍可供本地脚本和测试使用。此 issuer 与 buyer-subject 表单仅用于开发。

## 构建与测试

安装 Java 21。随仓库提供的 Maven wrapper 会下载 Maven 3.9.16。在仓库根目录运行：

```sh
./mvnw -q clean verify
```

预期退出码为 0；`-q` 隐藏正常构建输出。构建会运行 JUnit 测试和 Spotless 检查。运行 `./mvnw spotless:apply` 可自动格式化。

参见[项目团队](docs/team.zh-CN.md)。

UDP 心跳、事件编解码和支付丢失回复演示仍可独立运行。原始和 JDK HTTP 座位路由、executor 负载示例、早期 PostgreSQL 命令及 Compose 示例针对进程内库存和单数据库布局编写；当前 `TicketingMain` 使用 gRPC 库存服务和各服务独立的数据库凭据。可用 `git log -- README.zh-CN.md ticketing-api/src/main/java/com/elsworne/stubline/http/TicketingMain.java compose.yaml` 查找引入这些示例的提交。当前部署请使用远程库存与持久订单事件两节的 Compose 命令。

## UDP heartbeat 与故障检测

检测器放在 `ticketing-api` 的 `health` 包中，因为它直接观察这些 API 实例；目前无需单独部署运维服务。在仓库根目录的不同终端运行：

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='detector 9999 700'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-a localhost 9999 200 3'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-b localhost 9999 200'
```

任一实例状态变化时，检测器都会输出完整的成员状态快照。检测器预期出现 `api-a ALIVE` 和 `api-b ALIVE`。发送方 `api-a` 会记录 `dropped api-a sequence=3`（每三个丢一个）；只要之后的包在 700 ms 内到达，检测器仍显示存活。用 Ctrl-C 停止发送方：最后一个有效包之后约 700 ms，检测器记录 `api-a SUSPECTED`。用 Ctrl-Z 暂停同一发送进程，再用 `fg` 恢复：新序号的 heartbeat 到达时记录 `api-a ALIVE`。由于序号从 1 重新开始，检测器重启前，新启动的发送进程须使用新的 instance id。测试还注入乱序和重复序号，并展示慢实例被怀疑后恢复。`SUSPECTED` 只表示未及时收到包；无法区分进程停止、网络延迟或丢包。

在 Linux 的另一个终端运行 `sudo tcpdump -i lo -n -X udp port 9999` 查看 UDP datagram；macOS 用 `-i lo0`。预期看到发往 9999 端口的 UDP 包，包含版本字节、带长度的 UTF-8 instance id、64 位序号和 64 位时间戳。抓包需要相应权限。

## HTTP 端点

在仓库根目录启动使用 `ServerSocket` 的原始实现：

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='raw 18080'
```

如需使用 JDK `HttpServer`，把命令中的 `raw` 改为 `jdk`。每次启动时，本地库存有两个可售座位；以下操作请使用新启动的服务。在第二个终端运行这些准确命令：

```sh
curl -v http://localhost:18080/events/concert-1/seats
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://localhost:18080/orders
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://localhost:18080/orders
```

预期 `curl -v` 响应状态行依次为 `< HTTP/1.1 200 OK`、`< HTTP/1.1 201 Created` 和 `< HTTP/1.1 409 Conflict`。第一个响应体为 `[{"id":"A1","eventId":"concert-1","available":true},{"id":"A2","eventId":"concert-1","available":true}]`。第二个响应体包含 `"seatId":"A1"`、生成的 `id`、`createdAt` 和 `eventId`；字段顺序、ID 和时间戳会变化。第三个响应体为 `{"error":"seat unavailable"}`。两种实现使用相同的 transport-independent 路由。原始解析器展示 HTTP/1.1 framing 和有界请求处理；日常使用可由 JDK server 负责协议实现。测试涵盖错误请求行、没有 Content-Length 的 POST、分两次写入的 body 和同一 keep-alive 连接上的两个请求。

## 阻塞负载下的 server executor

原始 HTTP server 为每条连接分配一个 worker。`THREAD_PER_REQUEST` 在此表示每条连接一个平台线程，`FIXED_POOL` 使用有界 worker pool，`VIRTUAL` 为每条连接使用一个虚拟线程。JDK HTTP server 也接受这些选项，但它的 executor 分派 HTTP exchange 而不是 socket 连接。最后一个参数为座位查询增加阻塞毫秒数，模拟慢数据库调用。在仓库根目录每次运行一个 server：

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='raw 8080 FIXED_POOL 4 100'
./mvnw -q -pl load-generator exec:java -Dexec.mainClass=com.elsworne.stubline.load.LoadGenerator -Dexec.args='http://localhost:8080/events/concert-1/seats 32 10'
```

负载生成器输出每秒成功请求数及 p50/p95/p99 延迟。将 `FIXED_POOL` 换成另两种策略作比较。k6 运行方式见 `loadtest/`。仅在回环管理端口（`STUBLINE_ADMIN_PORT`，默认 `8081`）提供诊断路由 `GET /diagnostics/thread` 对虚拟 worker 返回 `{"virtual":true}`，对平台 worker 返回 `false`。

## 一个座位只允许一个赢家

`UnsafeInventory` 演示 check-then-act 竞态：两个买家都可能看到可用座位。`InMemoryInventory.reserve` 同步检查与更新，因此单进程只允许一个买家成功。`PostgresInventory` 用两种可选策略跨进程保持该约束：`ROW_LOCK` 在事务内使用 `SELECT ... FOR UPDATE`；`OPTIMISTIC` 读取版本，再执行 `UPDATE ... WHERE version = ? AND available = TRUE`，冲突时重试。Flyway 创建 `seats` 表，HikariCP 限制 JDBC 连接数。

```sh
./mvnw -q clean verify
./mvnw -q -Pdocker clean verify
docker compose up -d postgres
```

默认验证运行受控的内存竞态测试，跳过标记为 `docker` 的 PostgreSQL 测试。在可用 Docker 的环境中，`-Pdocker` 启动 Testcontainers PostgreSQL，分别让 32 个买家运行两种数据库策略，断言恰好一个成功并输出 optimistic retry 次数。`compose.yaml` 提供固定版本的本地 PostgreSQL，供手动探索。在已安装并启动 Docker 的主机上运行 Docker profile 和 Compose 命令。

运行 `docker compose up -d postgres` 后，用 PostgreSQL 提供 order 服务：

```sh
STUBLINE_JDBC_URL=jdbc:postgresql://localhost:5432/stubline STUBLINE_DB_USER=stubline STUBLINE_DB_PASSWORD=stubline STUBLINE_DB_STRATEGY=ROW_LOCK ./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='jdk 8080'
```

使用 `STUBLINE_DB_STRATEGY=OPTIMISTIC` 比较版本更新。Flyway 在启动时执行迁移；示例座位可重复插入。未设置 `STUBLINE_JDBC_URL` 时，server 使用同步的内存 inventory。

使用完毕后停止本地数据库：

```sh
docker compose down
```

原始 server 的有界线程池把 worker 分配给**连接**。HTTP keep-alive 会使四个 worker 长时间留在四条连接上，其他客户端饥饿；成功请求的 p95 看不出这一点。Java 生成器现输出每个客户端的完成数、超时数以及零完成客户端数；k6 在 2 秒后使请求超时并检查失败率。JDK `HttpServer` 的 executor 分派每个 HTTP exchange，worker 可在请求之间回到线程池。

`versionConflicts` 是因版本不匹配而失败的更新数；`retries` 是其后的重试次数。即使 32 个买家同时开始，多数也可能在座位售出后才读到状态并直接返回，因此不一定有 31 次冲突。

## 支付服务故障

独立启动 provider：`PROVIDER_PORT=8082 PROVIDER_LATENCY_MS=100 PROVIDER_ERROR_RATE=0.25 PROVIDER_OUTAGE=false ./mvnw -q -pl payment-provider-stub exec:java -Dexec.mainClass=com.elsworne.stubline.provider.PaymentProviderMain`。启动 `ticketing-api` 时设置 `STUBLINE_PAYMENT_PROVIDER_URL=http://localhost:8082`。`POST /payments/charge` 只调用一次；`GET /payments/charge-status` 可用指数退避和 full jitter 再试两次。二者共用两秒请求 deadline，每次调用只使用剩余预算。客户端记录 circuit breaker 状态变化；四次失败使其打开，此后请求快速失败，三秒后成功的试探请求使其关闭。收费响应不确定时，收费可能已经完成，因此不能盲目重试；安全重试需要后续改动加入的 idempotency key。运行 `./scripts/payment-breaker-demo.sh` 可看到 503、快速拒绝及恢复后的 200。

## 容器中的进程

运行 `docker compose up --build -d --wait` 构建并启动三个服务，运行 `docker compose down` 停止。两个 Java 镜像采用多阶段构建和固定版本的 Eclipse Temurin 21 JRE，以 UID 10001 运行，并设置 `-XX:MaxRAMPercentage=60`。Compose 设置 CPU 和内存限制并检查 `/health`。使用 `docker stats`、`docker compose exec -T ticketing-api wget -qO- http://127.0.0.1:8081/diagnostics/runtime` 和 `docker compose exec ticketing-api cat /sys/fs/cgroup/memory.max` 查看限制。端点输出 `availableProcessors`、`maxHeapBytes` 和 `containerMemoryLimitBytes`（cgroup 文件不可用或无限制时为 `-1`）。 BuildKit 在 `/root/.m2` 缓存 Maven wrapper 和依赖；先复制 POM 再复制源码，让 Java 代码变更时仍可复用依赖解析层。这能加快重复构建、减少下载并降低 CI 成本。

## 订单事件格式

`OrderPlaced` 的共同 schema 在 `ticketing-api/src/main/protobuf/order_placed.proto`，Java 模型通过 `EventCodec` 支持两种编码。字段包括订单、活动和座位 ID，买家，最小货币单位的金额，币种，时间戳，以及可选的买家备注。运行 `./mvnw -q -pl ticketing-api -am clean package`，然后运行 `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.orders.events.EventSizeMain`，可打印两种大小、Protobuf 十六进制字节和字段 tag。固定样例的第一行是 `json=197 bytes protobuf=82 bytes`。`0a`、`12`、`1a`、`22`、`28`、`32`、`3a` 对应字段 1–7；出现可选字段 8 时为 `42`。已删除的字段 9 和旧名称 `legacy_source` 被 reserved，不能重用。

当团队需要直接查看消息，且能接受较大的文本及字段名耦合时，JSON 合适。Jackson 易于调试，无需生成代码；但改名会破坏旧消费者，除非提供别名或迁移。我们明确关闭 `FAIL_ON_UNKNOWN_PROPERTIES`，让旧消费者忽略新字段；缺少原有必需字段仍会被拒绝。高吞吐或跨语言消息适合 Protobuf：字节更少，数字字段 ID 稳定，兼容规则明确。团队需要维护固定版本的 `protoc` 构建步骤、生成代码、schema 审查，并绝不重用已删除的字段号。生产者和消费者从同一份提交的 `.proto` 合约构建。

## 带 key 的支付

设置 `STUBLINE_PAYMENT_PROVIDER_URL` 为 provider 地址，向 `POST /payments` 发送 `Idempotency-Key` 和 JSON，例如 `{"orderId":"order-1","amountCents":2500}`。首次返回 201；同一个 key 和完全相同的请求体重放已保存的响应，不会再扣款；请求体不同返回 422；首次请求进行中时重试返回 409。provider 在资金移动前明确拒绝时，释放 key 供后续尝试使用。结果不明时保留 in-flight 记录，以便核对。完成的记录 24 小时后过期；进行中的记录不会自动过期。支付服务独立的 Flyway 迁移保存唯一 key、SHA-256 请求体哈希、状态、过期时间和响应。PostgreSQL 模式使用专用 Hikari 连接池；否则使用内存存储。

同一个 key 会传给 provider 的 `POST /payments`。其独立的扣款映射对重复 key 返回原扣款，对不同金额拒绝。最初的 `/payments/charge` 仍只调用 provider **一次**，不会自动重试：超时后的结果可能未知。现在客户端使用**同一个 idempotency key** 发起新尝试是安全的，因为两层都去重。无 key 扣款不可自动重试的理由见 [超时及 circuit breaker 说明](#支付服务故障)。

运行 `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.payments.LostReplyClient`。预期包含 `First reply timed out`、`Retry status: 201`、`Provider charges: 1`；两次尝试之间 API 以空的本地存储重启。HTTP 集成测试还会针对支付方的真实 keyed endpoint 验证这一情形。PostgreSQL 存储合约运行 `./mvnw -q -Pdocker clean verify`。

库存拆为独立服务后，在全新检出的仓库运行单模块 `./mvnw -q -pl ... exec:java` 命令之前，先在仓库根目录运行 `./mvnw -q -DskipTests install`。这会把 `inventory-api` 等兄弟模块安装进本地 Maven 仓库；仅运行 `verify` 或 `package` 不会安装它们。

## 远程库存

`inventory-api` 定义 `.proto` 契约并生成 Java stub；`inventory-service` 管理 PostgreSQL 座位并在 9090 端口提供 gRPC。`ticketing-api` 的每次查询和预订都使用两秒 deadline。stub 就是远程调用所依赖的代理：本地方法调用被编码并发往远程进程，回复或状态变为 Java 结果或异常。`NOT_FOUND` 映射为 HTTP 404，已占座的 `FAILED_PRECONDITION` 为 409，`DEADLINE_EXCEEDED` 为 504，`UNAVAILABLE` 为 503。相较于普通 socket，契约可生成客户端和服务端代码，deadline 限制等待时间，状态码能区分故障。504 **不能**证明座位未被预订：服务端可能已经修改状态，只是回复丢失或超时。重试非幂等操作前应先核对。

先运行 `./scripts/dev-ca.sh`、`docker compose up --build -d --wait`，再设置 `TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')`。接着运行 `curl --cacert certs/dev/public-ca.crt https://localhost:8080/events/concert-1/seats` 和 `curl --cacert certs/dev/public-ca.crt -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' https://localhost:8080/orders`。预期依次为 200、201；重复下单为 409。用 `docker compose ps inventory-service` 检查标准 gRPC health 服务。`./mvnw -q -Pdocker clean verify` 还通过 Testcontainers 验证真实 gRPC 传输和 PostgreSQL。本地 HTTP 演示现在需要库存服务（`STUBLINE_INVENTORY_TARGET=localhost:9090`）；内存库存仅供独立测试。

## 持久的订单事件

在 PostgreSQL 模式下，`POST /orders` 在同一个事务中写入订单和 `order_outbox` 中的 Protobuf `OrderPlaced` 负载。relay 用 `FOR UPDATE SKIP LOCKED` 选取待发送行，以**活动 ID 作为分区键**发往 Kafka `order-placed` topic，再标记已发布。若订单提交后才直接发布，进程可能在两步之间崩溃，留下有订单却无事件的状态。outbox 消除了这个窗口。Kafka 接收事件后、relay 标记前的崩溃可能导致重复发布，所以这里是**至少一次**交付，而非恰好一次。Kafka 保证单分区内顺序；同一活动的订单使用同一键，因此在那里保持顺序。不同活动没有全局顺序。

`notification-service` 属于 `stubline-notifications` consumer group，在同一数据库事务中写入已处理消息 ID 和通知行，仅对新 ID 记录 `Sending e-ticket ...` 日志，并在处理后提交 Kafka offset。提交 offset 前崩溃会导致重新投递，唯一的消息 ID 会丢弃重复事件。Docker 测试会注入这个崩溃并重启 consumer。日志发送仅用于演示；接入真实邮件供应商时，需要供应商幂等键或另一个 outbox 来处理外部副作用的间隙。

运行 `docker compose up --build -d --wait`，用“远程库存”一节的下单 `curl` 命令下单，再运行 `docker compose logs notification-service`，预期看到一条 `Sending e-ticket ...`。用 `docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group stubline-notifications` 查看各分区的 `CURRENT-OFFSET`、`LOG-END-OFFSET` 和 `LAG`。Compose 在生产者和消费者启动前明确创建含三个分区的 `order-placed` topic，并关闭 broker 自动创建。先运行 `docker compose down -v` 获得全新 stack，再运行 `./scripts/kafka-partitions-demo.sh`。Inventory 启动时预置 `concert-1` 的 A1/A2 和 `concert-2` 的 B1/B2，因此四笔示例订单都有对应座位。预期四条记录的 key 为 `concert-1` 和 `concert-2`；同一 key 的两条记录打印相同分区（两个 key 可能碰巧落在同一分区）。consumer group 输出包含 0、1、2 三个分区，各自 `LAG` 为 0。运行 `./mvnw -q -Pdocker clean verify` 验证 relay、consumer 和崩溃重投递。


## outbox 集成后的数据库所有权

Compose 创建三个 PostgreSQL 数据库：`inventory`、`ticketing` 和 `notifications`，分别由不同账号持有。inventory 管理座位行，ticketing 管理支付决策和订单 outbox，notifications 管理通知和去重行。服务通过 gRPC 调用和 Kafka 事件交互，不直接读取其他服务的表。这样迁移、权限和故障都局限在服务边界内。ticketing 的两组迁移共用一张 Flyway 历史表，避免在已有表而没有对应历史的 schema 上迁移失败。

在同一 checkout 切换 tag 后，构建时始终使用 `clean`：旧 Flyway migration 的 class 文件可能残留在 `target/classes`，与改名后的 migration 版本冲突。

运行 `./scripts/verify-tags.sh`，在隔离的 worktree 中对所有发布标签执行 `./mvnw -q clean verify`。尚无发布标签时，脚本输出 `No release tags found` 并以状态 1 退出。Docker 测试单独运行：`./mvnw -q -Pdocker clean verify`。若需要全新的 Compose 数据库，先运行 `docker compose down -v`，再运行 `docker compose up --build -d --wait`；PostgreSQL 初始化脚本仅在数据目录首次创建时执行。

## 电子票对象

`POST /orders` 预留座位后，使用该订单 ID 和 `Idempotency-Key` 调用 `POST /payments` 扣款。成功后，API 生成包含订单 ID、活动、座位和确定性 QR-like 图案的单页 PDF，上传至 `stubline-tickets` bucket 的 `tickets/<order-id>.pdf`，持有订单行锁并条件更新 object key。并发签发者会等待，看到已有 key 后跳过上传。如果上传后、事务提交前崩溃，确定性 key 对应的对象可能暂时孤立；后续重试覆盖同一对象并记录 key。`GET /orders/{id}/ticket` 对已付款订单返回 `{"url":"..."}`，未知或未付款订单返回 404。presigned GET URL 有效五分钟：持有 URL 的人可在期限内读取这个对象，无需获得 S3 凭据。

先运行 `./scripts/dev-ca.sh`、`docker compose up --build -d --wait`，并设置 `TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')`；按“持久订单事件”一节的下单命令创建订单并复制返回的 `id`。接着运行 `curl --cacert certs/dev/public-ca.crt -i -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: ticket-demo-1' -H 'Content-Type: application/json' -d '{"orderId":"<id>","amountCents":2500}' https://localhost:8080/payments`，再运行 `curl --cacert certs/dev/public-ca.crt -s -H "Authorization: Bearer $TOKEN" https://localhost:8080/orders/<id>/ticket`。预期先收到 201，再收到含 `url` 的 JSON；URL 指向 `http://localhost:8333/stubline-tickets/tickets/<id>.pdf`，其中 `X-Amz-Expires=300`。下载后 PDF 以 `%PDF-1.4` 开头。未知 ID 返回 404。`./mvnw -q -Pdocker clean verify` 检查真实 SeaweedFS presigned PUT 上传、签名下载和 URL 过期拒绝。

本地 presigned ticket URL 使用 HTTP，仅因本地 SeaweedFS endpoint 是 HTTP；生产 object store 通过 HTTPS 访问。票据文件的生命周期和读取方式与订单行不同。对象存储承载文件字节、访问授权和扩容，避免撑大事务数据库，也不要求每个 API 副本挂载同一目录。PostgreSQL 保留权威订单和 object key。代码只调用 S3 API：使用 AWS S3 时设置 `STUBLINE_S3_BUCKET`、`STUBLINE_S3_REGION` 和标准 AWS 凭据，不设置 endpoint override；使用 SeaweedFS 时设置 `STUBLINE_S3_ENDPOINT`、`STUBLINE_S3_PUBLIC_ENDPOINT` 和 `STUBLINE_S3_PATH_STYLE=true`。公开 endpoint 必须能从买家浏览器解析，因为 host 参与签名。

NFS 这类分布式文件系统提供文件路径和客户端缓存，常有 close-to-open 与属性缓存的刷新规则。S3 API 提供整个对象的 PUT、GET 和按前缀 listing，而不是 POSIX 的 open/write/close。把成功的 PUT 作为新票据 key 的发布点。已发票据使用不可变 key，避免覆盖：缓存和 gateway 可能保留旧版本，不同 S3 兼容实现的 listing 可见性和覆盖行为也可能不同。根据数据库记录的精确 key 获取对象，不把 listing 当作权威来源。

## 命名与发现

Compose 的内部网络为每个服务提供 DNS 名称。ticketing 通过名称访问 `inventory-service:9090`、`payment-provider-stub:8081`、`kafka:9092`、`postgres:5432` 和 `seaweedfs:8333`。每个地址都能通过环境变量配置（`STUBLINE_INVENTORY_HOST` 与 `STUBLINE_INVENTORY_PORT`、`STUBLINE_PAYMENT_PROVIDER_URL`、`STUBLINE_KAFKA_BOOTSTRAP`、`STUBLINE_JDBC_URL`、`STUBLINE_S3_ENDPOINT`）。容器内的 `localhost` 只指该容器，因此仅供本地 health probe 使用。inventory 不发布固定 host port，支持 `docker compose up --scale inventory-service=2`。

在已运行或全新 stack 上运行 `./scripts/dns-discovery-demo.sh`。脚本先启动一个 inventory 副本，不重启 `ticketing-api` 地扩为两个；扩容后再次运行固定解析客户端，等待 25 秒让连接退役并刷新 DNS，然后发送 24 次请求，打印每个副本实际处理数。实测第二次 `/diagnostics/dns?name=inventory-service` 返回 `addresses:["172.22.0.9","172.22.0.6"]` 和 `effectiveTtl:"5"`。实际地址由 Docker 分配，每次不同。固定解析客户端在扩容前后都只打印一个选中的地址并通过它完成请求，即使扩容后 Compose DNS 已返回两个地址。一次运行中，两个容器分别处理 11 和 13 个请求，总数 24；实际地址和分布每次可能不同。

ticketing JVM entrypoint 上的 `-Dsun.net.inetaddr.ttl=5` 限制 ticketing JVM 的正向 DNS 缓存时间。`GET /diagnostics/dns?name=<host>` 报告 JVM 查询结果和 `networkaddress.cache.ttl`（或 JVM 的 `sun.net.inetaddr.ttl` fallback）的有效值。客户端若只解析一次名称并以所得地址建立 channel，即使 DNS 后来变化也仍固定在该地址。普通 gRPC channel 使用目标名称和 `round_robin`，让 DNS resolver 刷新地址集合并分发调用。单靠 DNS TTL 不会重新分配已建立的连接。

5 秒 JVM DNS TTL 避免旧地址无限期留在缓存，但仅有 TTL 不够：gRPC 在连接事件发生时才重新解析，不会仅因 TTL 到期而刷新。inventory server 设定 15 秒 `maxConnectionAge` 与 5 秒 grace，让命名的 `round_robin` channel 重新连接并发现新增副本。演示值刻意较短；生产环境通常采用更长的值，例如 30 分钟，并结合部署及连接排空要求调整。连接退役增加 TLS 握手、连接建立和 DNS 查询开销，也会短暂重新分布负载；grace 让进行中的 RPC 完成。

## 传输安全与买家 token

先运行 `./scripts/dev-ca.sh`，再启动 Compose。脚本重用有效证书，只续期七天内将过期的证书；`--force` 才强制轮换全部证书。脚本在忽略的 `certs/dev/` 下创建两个短期开发 CA：对外 API CA 与独立的内部 gRPC CA。server 证书的 SAN 含 Compose DNS 名和 `localhost`。脚本导出 PKCS#12 keystore；每个服务只读挂载所需证书与密钥。挂载的私钥和 keystore 权限为 600，属主为服务 UID 10001。真实团队从受信 CA 取得证书，在 secrets manager 中保护私钥、到期前轮换，绝不提交密钥。

JWT 签名演示使用当前 HTTPS issuer 的 JWKS。启动 Compose 后，在仓库根目录运行：

```sh
TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')
curl --cacert certs/dev/public-ca.crt -i -X POST -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A2"}' https://localhost:8080/orders
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.JwtAnatomy -Dexec.args="$TOKEN https://localhost:8443/.well-known/jwks.json certs/dev/public-ca.crt"
```

未携带 token 的 `POST /orders` 预期返回 401，并包含 `WWW-Authenticate: Bearer realm="stubline"`。`JwtAnatomy` 的预期输出包含 `signature valid: true`。


本地运行 `./mvnw -q verify` 可执行 CI 检查，并生成 `target/bom.json`。CI 扫描文件系统、SBOM 和本地构建的 Compose 镜像，对有修复版本的 HIGH、CRITICAL 漏洞失败（使用 `.trivyignore`）。安装 Renovate app 后，它才会按 Maven、Docker 镜像和 GitHub Actions 分组提出更新。

座位列表支持 `limit`（1–100，默认 20）和不透明 `cursor`；响应包含 `seats` 与 `next`。本地 PostgreSQL 配置见 `.env.example`，复制为 `.env`；启用数据库时必须提供用户和密码。

容器使用 `/health/ready` 探针；`/health/live` 表示进程存活。就绪状态只检查本地 PostgreSQL，不级联检查支付服务。容器输出 JSON 日志。收到 SIGTERM 后，ticketing 先停止公开监听并最多等待十秒让请求完成，再关闭管理监听；然后停止 outbox worker，最多等待 15 秒让当前批次提交或回滚，超时则中断批次，最后关闭 Kafka producer、票据存储、数据库连接池和远程客户端。每一步都会记录日志。ticketing 容器的停止宽限期为 40 秒；中断后仍未发布的行会在重启后重试。

库存拆为独立服务后，Compose 为 `inventory` 和 `ticketing` 建立独立数据库和用户，服务不共享表或 Flyway 历史。outbox 集成时另建 `notifications` 数据库。迁移现有数据时先停止写入，把座位复制到 `inventory`、支付幂等记录复制到 `ticketing`，核对后再切换流量；示例部署从空数据开始。

## 买家活动与订单查询

`GET /events` 列出开发用活动目录，支持 `limit`（1–100，默认 20）与不透明 `cursor`，响应包含 `events` 和 `next`。示例目录包含 `concert-1`；库存服务会初始化其座位。`GET /orders/{id}` 要求买家 token，返回订单的 `id`、`eventId`、`seatId` 和 `createdAt`。订单不存在或属于其他买家时，返回相同的 404 Problem Details。

在仓库根目录运行 `./scripts/dev-ca.sh` 和 `docker compose up --build -d --wait` 后，执行：

```sh
curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8080/events?limit=20'
TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-a')
ORDER_ID=$(curl --cacert certs/dev/public-ca.crt -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' https://localhost:8080/orders | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
curl --cacert certs/dev/public-ca.crt -i -H "Authorization: Bearer $TOKEN" "https://localhost:8080/orders/$ORDER_ID"
OTHER_TOKEN=$(curl --cacert certs/dev/public-ca.crt -fsS 'https://localhost:8443/token?sub=buyer-b')
curl --cacert certs/dev/public-ca.crt -i -H "Authorization: Bearer $OTHER_TOKEN" "https://localhost:8080/orders/$ORDER_ID"
```

预期依次看到活动页 200、相同 ID 的订单 200，以及 `application/problem+json` 的 404。如果 `A1` 已售，请使用全新的演示数据库或选择可用座位。

### 已知局限

`POST /payments` 接受客户端提供的 `amountCents`；此演示没有根据服务器端订单总额计算或核对价格。

## 许可证

本仓库采用 [MIT License](LICENSE)。

## 关于本仓库

Elsworne、Stubline 和团队成员均为虚构。

## 1.1.0 release build

在已配置 `.env` 且 Docker 可用的机器上，从 `1.1.0` release commit 运行：

```sh
./scripts/dev-ca.sh
./scripts/build-release.sh 1.1.0
set -a; source .env.release; set +a
docker compose -f compose.yaml -f compose.release.yaml up -d --no-build --wait --scale inventory-service=2
./scripts/deployed.sh
```

预期构建五个服务镜像，部署状态检查通过。

本仓库的代码与文档借助 AI 工具编写；记录的输出都来自实际运行。
