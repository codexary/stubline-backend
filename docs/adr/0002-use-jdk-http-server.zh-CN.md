[English](0002-use-jdk-http-server.md) | 简体中文

# ADR 0002：首个票务接口采用 JDK HTTP server

状态：2026-03-27 采纳。

## 背景（Context）

首批票务路由是 `GET /events/{eventId}/seats` 和 `POST /orders`。手写 `ServerSocket` 版本能看到 CRLF 行、空行、`Content-Length`、分段读取和持久连接。若继续维护这份 HTTP 解析器，团队就要负责超过这两个路由所需的协议细节。

## 决定（Decision）

保留 `RawHttpServer` 用来练习消息分帧；业务接口由 `JdkHttpServer` 提供。两者都调用 `TicketingApi.handle`，并用请求、响应和公开契约测试检查两种实现。

## 考虑过的方案（Alternatives considered）

- 继续以 `RawHttpServer` 提供接口：实验有用，但要长期维护请求解析和响应写入。
- 现在引入更大的 Web 框架：能提供更多应用功能，但当前实现还不需要这一层。

## 后果（Consequences）

`HttpServer` 解析 HTTP exchange 并管理网络连接。团队仍负责请求体上限、路由、应用错误和测试。内存库存只有两个示例座位，每次启动都会重置。
