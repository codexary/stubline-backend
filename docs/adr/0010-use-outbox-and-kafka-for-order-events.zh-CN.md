[English](0010-use-outbox-and-kafka-for-order-events.md) | 简体中文

# ADR 0010：用 outbox 和 Kafka 传递订单事件

## 状态（Status）

2026-07-22 采纳。

## 背景（Context）

Grace Kim 需要在通知处理变慢时，买家仍能收到电子票通知。Sofia Marquez 需要确保 API 在发布前停止后，已提交的订单不会无处可查。若在买家的请求中同步调用通知服务，请求会等待通知服务。若先提交订单再直接发布，两套系统之间有崩溃窗口。

RabbitMQ 工作队列小项目适合逐项确认的渲染任务，展示了 prefetch 1、延迟队列和死信队列。Redis pub/sub 小项目把更新发给当前监听者，迟到者收不到旧座位数；Redis Streams 为 consumer group 留住历史。订单通知需要保留事件并记录独立的读取进度。

## 决策（Decision）

在 PostgreSQL 模式下，`ticketing-api` 在同一事务中把订单和 Protobuf `OrderPlaced` 负载写进 `order_outbox`。负载里的 `concertId` 是活动 ID，另有唯一的消息 `eventId`。relay 用 `FOR UPDATE SKIP LOCKED` 选择未发布行，以活动 ID 为键发往 Kafka 三分区 `order-placed` topic，得到 Kafka 确认后标记已发布。`notification-service` 作为 `stubline-notifications` group 读取，在一个数据库事务中记下 `handled_events` 的消息 ID 和通知行，然后提交 Kafka offset。同一消息重投递不会产生第二条通知行；指向同一订单的不同消息分别处理。

## 考虑过的方案（Alternatives considered）

- 在 `POST /orders` 中同步通知：买家的等待时间和成功与否受通知进程影响。
- 订单提交后直接发布：进程在两步之间停止时会丢事件。
- RabbitMQ 工作队列：适合独立渲染任务及逐项重试；订单事件还要作为可供独立 group 读取的历史。
- Redis pub/sub：适合可丢弃的实时座位数；迟到监听者会错过旧更新。

## 后果（Consequences）

Kafka 接受发送后、PostgreSQL 记下 `published_at` 前，relay 停止会导致重复发布。数据库处理后、offset 提交前，consumer 停止会导致重读。ID 账本让测试中的数据库通知效果每个消息 ID 只发生一次。日志行只是演示，不是已送达的邮件；外部邮件供应商需要自己的幂等键或另一层 outbox。Kafka 只保证单分区内顺序；活动 ID 把同一活动的订单放在一起，不提供活动之间的全局顺序。通知变慢时，值班人员检查 group lag 和 outbox。
