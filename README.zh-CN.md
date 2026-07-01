[English](README.md) | 简体中文

# Stubline

Stubline 是 Elsworne 的活动票务平台，涵盖活动、座位、订单、支付、通知和电子票。当前版本包含 `ticketing-api` 和由 PostgreSQL 支持的 gRPC `inventory-service`。通知暂时仍留在 API 进程中。

## 构建与测试

安装 Java 21。随仓库提供的 Maven wrapper 会下载 Maven 3.9.16。在仓库根目录运行：

```sh
./mvnw -q verify
```

预期退出码为 0；`-q` 隐藏正常构建输出。构建会运行 JUnit 测试和 Spotless 检查。运行 `./mvnw spotless:apply` 可自动格式化。

参见[项目团队](docs/team.zh-CN.md)。

## UDP heartbeat 与故障检测

检测器放在 `ticketing-api` 的 `health` 包中，因为它直接观察这些 API 实例；目前无需单独部署运维服务。在仓库根目录的不同终端运行：

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='detector 9999 700'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-a 127.0.0.1 9999 200 3'
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.health.HeartbeatMain -Dexec.args='sender api-b 127.0.0.1 9999 200'
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
curl -v http://127.0.0.1:18080/events/concert-1/seats
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://127.0.0.1:18080/orders
curl -v -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://127.0.0.1:18080/orders
```

预期 `curl -v` 响应状态行依次为 `< HTTP/1.1 200 OK`、`< HTTP/1.1 201 Created` 和 `< HTTP/1.1 409 Conflict`。第一个响应体为 `[{"id":"A1","eventId":"concert-1","available":true},{"id":"A2","eventId":"concert-1","available":true}]`。第二个响应体包含 `"seatId":"A1"`、生成的 `id`、`createdAt` 和 `eventId`；字段顺序、ID 和时间戳会变化。第三个响应体为 `{"error":"seat unavailable"}`。两种实现使用相同的 transport-independent 路由。原始解析器展示 HTTP/1.1 framing 和有界请求处理；日常使用可由 JDK server 负责协议实现。测试涵盖错误请求行、没有 Content-Length 的 POST、分两次写入的 body 和同一 keep-alive 连接上的两个请求。

## 阻塞负载下的 server executor

原始 HTTP server 为每条连接分配一个 worker。`THREAD_PER_REQUEST` 在此表示每条连接一个平台线程，`FIXED_POOL` 使用有界 worker pool，`VIRTUAL` 为每条连接使用一个虚拟线程。JDK HTTP server 也接受这些选项，但它的 executor 分派 HTTP exchange 而不是 socket 连接。最后一个参数为座位查询增加阻塞毫秒数，模拟慢数据库调用。在仓库根目录每次运行一个 server：

```sh
./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='raw 8080 FIXED_POOL 4 100'
./mvnw -q -pl load-generator exec:java -Dexec.mainClass=com.elsworne.stubline.load.LoadGenerator -Dexec.args='http://127.0.0.1:8080/events/concert-1/seats 32 10'
```

负载生成器输出每秒成功请求数及 p50/p95/p99 延迟。将 `FIXED_POOL` 换成另两种策略作比较。k6 运行方式见 `loadtest/`。仅在回环管理端口（`STUBLINE_ADMIN_PORT`，默认 `8081`）提供诊断路由 `GET /diagnostics/thread` 对虚拟 worker 返回 `{"virtual":true}`，对平台 worker 返回 `false`。

## 一个座位只允许一个赢家

`UnsafeInventory` 演示 check-then-act 竞态：两个买家都可能看到可用座位。`InMemoryInventory.reserve` 同步检查与更新，因此单进程只允许一个买家成功。`PostgresInventory` 用两种可选策略跨进程保持该约束：`ROW_LOCK` 在事务内使用 `SELECT ... FOR UPDATE`；`OPTIMISTIC` 读取版本，再执行 `UPDATE ... WHERE version = ? AND available = TRUE`，冲突时重试。Flyway 创建 `seats` 表，HikariCP 限制 JDBC 连接数。

```sh
./mvnw -q verify
./mvnw -q -Pdocker verify
docker compose up --build -d --wait inventory-service
```

默认验证运行受控的内存竞态测试，跳过标记为 `docker` 的 PostgreSQL 测试。在可用 Docker 的环境中，`-Pdocker` 启动 Testcontainers PostgreSQL，分别让 32 个买家运行两种数据库策略，断言恰好一个成功并输出 optimistic retry 次数。`compose.yaml` 提供固定版本的本地 PostgreSQL，供手动探索。在已安装并启动 Docker 的主机上运行 Docker profile 和 Compose 命令。

先按上述命令启动 `inventory-service`，再让 API 连接自己的 PostgreSQL 数据库和 inventory gRPC 服务：

```sh
STUBLINE_JDBC_URL=jdbc:postgresql://localhost:5432/ticketing STUBLINE_DB_USER=ticketing STUBLINE_DB_PASSWORD=stubline STUBLINE_INVENTORY_TARGET=localhost:9090 ./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.http.TicketingMain -Dexec.args='jdk 8080'
```

库存拆为独立服务后，先启动 `inventory-service`：座位记录和预订策略由它管理。以上命令连接 ticketing 自己的支付数据库，并通过 gRPC 调用 inventory。比较 `ROW_LOCK` 和 `OPTIMISTIC` 时，在 `inventory-service` 上设置 `STUBLINE_DB_STRATEGY`。

使用完毕后停止本地数据库：

```sh
docker compose down
```

原始 server 的有界线程池把 worker 分配给**连接**。HTTP keep-alive 会使四个 worker 长时间留在四条连接上，其他客户端饥饿；成功请求的 p95 看不出这一点。Java 生成器现输出每个客户端的完成数、超时数以及零完成客户端数；k6 在 2 秒后使请求超时并检查失败率。JDK `HttpServer` 的 executor 分派每个 HTTP exchange，worker 可在请求之间回到线程池。

`versionConflicts` 是因版本不匹配而失败的更新数；`retries` 是其后的重试次数。即使 32 个买家同时开始，多数也可能在座位售出后才读到状态并直接返回，因此不一定有 31 次冲突。

## 支付服务故障

独立启动 provider：`PROVIDER_LATENCY_MS=100 PROVIDER_ERROR_RATE=0.25 PROVIDER_OUTAGE=false ./mvnw -q -pl payment-provider-stub exec:java -Dexec.mainClass=com.elsworne.stubline.provider.PaymentProviderMain`。启动 `ticketing-api` 时设置 `STUBLINE_PAYMENT_PROVIDER_URL=http://127.0.0.1:8081`。`POST /payments/charge` 只调用一次；`GET /payments/charge-status` 可用指数退避和 full jitter 再试两次。二者共用两秒请求 deadline，每次调用只使用剩余预算。客户端记录 circuit breaker 状态变化；四次失败使其打开，此后请求快速失败，三秒后成功的试探请求使其关闭。收费响应不确定时，收费可能已经完成，因此不能盲目重试；安全重试需要后续改动加入的 idempotency key。运行 `./scripts/payment-breaker-demo.sh` 可看到 503、快速拒绝及恢复后的 200。

## 容器中的进程

运行 `docker compose up --build -d --wait` 构建并启动三个服务，运行 `docker compose down` 停止。两个 Java 镜像采用多阶段构建和固定版本的 Eclipse Temurin 21 JRE，以 UID 10001 运行，并设置 `-XX:MaxRAMPercentage=60`。Compose 设置 CPU 和内存限制并检查 `/health`。使用 `docker stats`、`docker compose exec -T ticketing-api wget -qO- http://127.0.0.1:8081/diagnostics/runtime` 和 `docker compose exec ticketing-api cat /sys/fs/cgroup/memory.max` 查看限制。端点输出 `availableProcessors`、`maxHeapBytes` 和 `containerMemoryLimitBytes`（cgroup 文件不可用或无限制时为 `-1`）。 BuildKit 在 `/root/.m2` 缓存 Maven wrapper 和依赖；先复制 POM 再复制源码，让 Java 代码变更时仍可复用依赖解析层。这能加快重复构建、减少下载并降低 CI 成本。

## 订单事件格式

`OrderPlaced` 的共同 schema 在 `ticketing-api/src/main/protobuf/order_placed.proto`，Java 模型通过 `EventCodec` 支持两种编码。字段包括订单、活动和座位 ID，买家，最小货币单位的金额，币种，时间戳，以及可选的买家备注。运行 `./mvnw -q -pl ticketing-api -am package`，然后运行 `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.orders.events.EventSizeMain`，可打印两种大小、Protobuf 十六进制字节和字段 tag。固定样例的第一行是 `json=175 bytes protobuf=73 bytes`。`0a`、`12`、`1a`、`22`、`28`、`32`、`3a` 对应字段 1–7；出现可选字段 8 时为 `42`。已删除的字段 9 和旧名称 `legacy_source` 被 reserved，不能重用。

当团队需要直接查看消息，且能接受较大的文本及字段名耦合时，JSON 合适。Jackson 易于调试，无需生成代码；但改名会破坏旧消费者，除非提供别名或迁移。我们明确关闭 `FAIL_ON_UNKNOWN_PROPERTIES`，让旧消费者忽略新字段；缺少原有必需字段仍会被拒绝。高吞吐或跨语言消息适合 Protobuf：字节更少，数字字段 ID 稳定，兼容规则明确。团队需要维护固定版本的 `protoc` 构建步骤、生成代码、schema 审查，并绝不重用已删除的字段号。生产者和消费者从同一份提交的 `.proto` 合约构建。

## 带 key 的支付

设置 `STUBLINE_PAYMENT_PROVIDER_URL` 为 provider 地址，向 `POST /payments` 发送 `Idempotency-Key` 和 JSON，例如 `{"orderId":"order-1","amountCents":2500}`。首次返回 201；同一个 key 和完全相同的请求体重放已保存的响应，不会再扣款；请求体不同返回 422；首次请求进行中时重试返回 409。provider 在资金移动前明确拒绝时，释放 key 供后续尝试使用。结果不明时保留 in-flight 记录，以便核对。完成的记录 24 小时后过期；进行中的记录不会自动过期。Flyway `V2` 迁移保存唯一 key、SHA-256 请求体哈希、状态、过期时间和响应。PostgreSQL 模式复用 现有的 Hikari 连接池；否则使用内存存储。

同一个 key 会传给 provider 的 `POST /payments`。其独立的扣款映射对重复 key 返回原扣款，对不同金额拒绝。最初的 `/payments/charge` 仍只调用 provider **一次**，不会自动重试：超时后的结果可能未知。现在客户端使用**同一个 idempotency key** 发起新尝试是安全的，因为两层都去重。无 key 扣款不可自动重试的理由见 [超时及 circuit breaker 说明](#支付服务故障)。

运行 `./mvnw -q -pl ticketing-api exec:java -Dexec.mainClass=com.elsworne.stubline.payments.LostReplyClient`。预期包含 `First reply timed out`、`Retry status: 201`、`Provider charges: 1`；两次尝试之间 API 以空的本地存储重启。HTTP 集成测试还会针对 支付方 的真实 keyed endpoint 验证这一情形。PostgreSQL 存储合约运行 `./mvnw -q -Pdocker verify`。

库存拆为独立服务后，在全新检出的仓库运行单模块 `./mvnw -q -pl ... exec:java` 命令之前，先在仓库根目录运行 `./mvnw -q -DskipTests install`。这会把 `inventory-api` 等兄弟模块安装进本地 Maven 仓库；仅运行 `verify` 或 `package` 不会安装它们。

## 远程库存

`inventory-api` 定义 `.proto` 契约并生成 Java stub；`inventory-service` 管理 PostgreSQL 座位并在 9090 端口提供 gRPC。`ticketing-api` 的每次查询和预订都使用两秒 deadline。stub 就是远程调用所依赖的代理：本地方法调用被编码并发往远程进程，回复或状态变为 Java 结果或异常。`NOT_FOUND` 映射为 HTTP 404，已占座的 `FAILED_PRECONDITION` 为 409，`DEADLINE_EXCEEDED` 为 504，`UNAVAILABLE` 为 503。相较于普通 socket，契约可生成客户端和服务端代码，deadline 限制等待时间，状态码能区分故障。504 **不能**证明座位未被预订：服务端可能已经修改状态，只是回复丢失或超时。重试非幂等操作前应先核对。

运行 `docker compose up --build -d --wait`，再运行 `curl http://localhost:8080/events/concert-1/seats` 和 `curl -H 'Content-Type: application/json' -d '{"eventId":"concert-1","seatId":"A1"}' http://localhost:8080/orders`。预期依次为 200、201；重复下单为 409。用 `docker compose ps inventory-service` 检查标准 gRPC health 服务。`./mvnw -q -Pdocker verify` 还通过 Testcontainers 验证真实 gRPC 传输和 PostgreSQL。本地 HTTP 演示现在需要库存服务（`STUBLINE_INVENTORY_TARGET=localhost:9090`）；内存库存仅供独立测试。

本地运行 `./mvnw -q verify` 可执行 CI 检查，并生成 `target/bom.json`。CI 扫描文件系统、SBOM 和本地构建的 Compose 镜像，对有修复版本的 HIGH、CRITICAL 漏洞失败（使用 `.trivyignore`）。安装 Renovate app 后，它才会按 Maven、Docker 镜像和 GitHub Actions 分组提出更新。

座位列表支持 `limit`（1–100，默认 20）和不透明 `cursor`；响应包含 `seats` 与 `next`。本地 PostgreSQL 配置见 `.env.example`，复制为 `.env`；启用数据库时必须提供用户和密码。

容器使用 `/health/ready` 探针；`/health/live` 表示进程存活。就绪状态只检查本地 PostgreSQL，不级联检查支付服务。容器输出 JSON 日志，SIGTERM 最多等待十秒完成请求。

库存拆为独立服务后，Compose 为 `inventory` 和 `ticketing` 建立独立数据库和用户。两个服务不共享表或 Flyway 历史，避免迁移版本互相冲突。示例部署从空数据开始，由库存服务加入样例座位。迁移现有数据时先停止写入，把 `seats` 复制到 `inventory`，把 `payment_idempotency` 复制到 `ticketing`，核对行数与键后再切换流量。

## 许可证

本仓库采用 [MIT License](LICENSE)。

## 关于本仓库

Elsworne、Stubline 和团队成员均为虚构。

本仓库的代码与文档借助 AI 工具编写；记录的输出都来自实际运行。
