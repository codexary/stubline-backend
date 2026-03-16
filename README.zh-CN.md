[English](README.md) | 简体中文

# Stubline

Stubline 是 Elsworne 的活动票务平台，涵盖活动、座位、订单、支付、通知和电子票。当前模块化单体的 `ticketing-api` 包含 `catalog`、`inventory`、`orders`、`http`、`health` 包。库存位于内存中，重启后会清空。后续章节才会加入其他功能。

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

检测器预期出现 `api-a ALIVE` 和 `api-b ALIVE`。发送方 `api-a` 会记录 `dropped api-a sequence=3`（每三个丢一个）；只要之后的包在 700 ms 内到达，检测器仍显示存活。用 Ctrl-C 停止发送方：最后一个有效包之后约 700 ms，检测器记录 `api-a SUSPECTED`。用 Ctrl-Z 暂停同一发送进程，再用 `fg` 恢复：新序号的 heartbeat 到达时记录 `api-a ALIVE`。由于序号从 1 重新开始，检测器重启前，新启动的发送进程须使用新的 instance id。测试还注入乱序和重复序号，并展示慢实例被怀疑后恢复。`SUSPECTED` 只表示未及时收到包；无法区分进程停止、网络延迟或丢包。

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

预期 `curl -v` 响应状态行依次为 `< HTTP/1.1 200 OK`、`< HTTP/1.1 201 Created` 和 `< HTTP/1.1 409 Conflict`。第一个响应体为 `[{"id":"A1","eventId":"concert-1","available":true},{"id":"A2","eventId":"concert-1","available":true}]`。第二个响应体包含 `"seatId":"A1"`、生成的 `id`、`createdAt` 和 `eventId`；字段顺序、ID 和时间戳会变化。第三个响应体为 状态为 409、包含 `seatId` A1 的 Problem Details 对象。两种实现使用相同的 transport-independent 路由。原始解析器展示 HTTP/1.1 framing 和有界请求处理；日常使用可由 JDK server 负责协议实现。测试涵盖错误请求行、没有 Content-Length 的 POST、分两次写入的 body 和同一 keep-alive 连接上的两个请求。

本地运行 `./mvnw -q verify` 可执行 CI 检查，并生成 `target/bom.json`。CI 扫描文件系统和 SBOM，对有修复版本的 HIGH、CRITICAL 漏洞失败。安装 Renovate app 后，它才会按 Maven、Docker 镜像和 GitHub Actions 分组提出更新。

公开契约是 `openapi.yaml`（OpenAPI 3.1）。用 `docker run --rm -v "$PWD:/spec" -w /spec redocly/cli:2.64.1@sha256:b9b4585a3bda40f77b58f5fa4535ad5ce7b7d1e4d6f8ed98ebc2c7694dbf3f5b lint openapi.yaml` 检查。默认 Maven 构建在进程内启动 raw 和 JDK 两种服务器，用 Atlassian OpenAPI Request Validator 3.0.0 验证实际的状态码、Content-Type 和 JSON 响应。这个持续维护的版本支持 OpenAPI 3.1 和 Java 21。无效请求返回 RFC 9457 `application/problem+json`，适用时说明字段名。

## 许可证

本仓库采用 [MIT License](LICENSE)。

## 关于本仓库

Elsworne、Stubline 和团队成员均为虚构。

本仓库的代码与文档借助 AI 工具编写；记录的输出都来自实际运行。
