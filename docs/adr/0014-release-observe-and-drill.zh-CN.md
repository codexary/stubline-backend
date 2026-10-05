[English](0014-release-observe-and-drill.md) | 简体中文

# ADR 0014：标识发布、连接订单 trace 并演练故障

## 状态（Status）

2026-10-06 采纳。

## 背景（Context）

开售临近。团队要知道正在运行哪份代码，要沿一笔订单跨过同步和异步服务，还要说清依赖故障时买家实际看到什么。已有 outbox 将 HTTP 请求与 Kafka 发布隔开。若 relay 独自新建 trace，发布就失去与原下单请求的关系。

## 决定（Decision）

发布时用版本号和提交号标识镜像，并从运行容器反查标签。只从干净的 Git 工作树构建，先运行 `./mvnw -q verify`。应用镜像写入发布版本和 12 位提交 revision，`compose.release.yaml` 使用这组镜像。

一次下单 trace 跨过 outbox。每个 Java 服务用 OpenTelemetry Java agent 记录通用操作，手写 `order.place`、`outbox.publish`、`notification.handle` span 标出业务边界。把 W3C `traceparent` 与 outbox 行一起保存，发布时恢复。Collector 从 trace 中筛掉健康检查 span，再把遥测送入本地 Grafana LGTM。

演练分别注入库存副本退出、Kafka 停止、通知消费者在 offset commit 前崩溃、支付供应商故障。演练同时核对买家响应、积压指标和恢复后的持久状态。数据库备份恢复到独立临时库，比较选定表的指纹。独立 kind 实验比较 worker 突然消失与有计划 drain。

每条发出的 `OrderPlaced` 消息有自己的 ID，与订单 ID 和活动 ID 分开。通知按消息 ID 去重：重送会被丢弃，同一订单的另一条合法消息仍能处理。`POST /payments` 在调用供应商前检查订单归属。签发电子票时锁住订单行并使用固定对象 key，让并发调用者不会重复上传。

## 考虑过的方案（Alternatives considered）

- 只用日志：容易加入，但无法直接表示 HTTP、gRPC、outbox 和 Kafka 之间的父子关系。
- 从未提交的工作树构建：省一步，却无法从镜像的提交标签可靠重建源码。
- 演练只看 readiness 和副本数：它们不能说明买家是否失败，也不能证明持久通知没有重复。
- 把 kind 实验当作 Stubline 生产部署：小型无状态本地实验不足以支持这个结论。

## 后果（Consequences）

记录中的订单 trace 连上 API、库存、outbox 发布和通知处理。四个本地演练与备份恢复留下了本地发布与故障演练中的实测结果。trace check 覆盖下单到通知，不包括随后支付与下载票据。这个本地发布流程没有远端镜像仓库或部署后不可变 digest。演练会删除 Compose 数据卷，只能在可丢弃的环境中运行。kind 节点突然故障时买家仍看到失败请求；有计划 drain 的这次样本为零失败。

