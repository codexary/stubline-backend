[English](0007-order-event-encoding.md) | 简体中文

# ADR 0007：订单事件编码与 schema 演进

拟放入：`docs/adr/0007-order-event-encoding.md`

## 状态（Status）

草案。当前已实现并验证两种 codec，尚未把 `OrderPlaced` 发布到 broker。

## 背景（Context）

未来的消费者可能保存订单事件多年，也会在不同时间升级。`ticketing-api` 定义了 `OrderPlaced`、Jackson JSON codec，以及基于 `order_placed.proto` 的 Protobuf codec。固定样例编码后是 JSON 175 字节、Protobuf 73 字节。兼容性测试覆盖新增可选 Protobuf 字段、为已删除字段保留编号和名称、有意忽略未知 JSON 字段，以及使旧消费者失败的 JSON 字段改名。解码测试还验证缺少金额或其他必需字段会被拒绝，明确写出的零金额可来回编码。

## 决策（Decision）

将 `.proto` 作为未来长期、高消息量订单事件流的共享契约；连接事件流时采用 Protobuf 线格式。保留 JSON codec，供人工检查和明确选择 JSON 的集成使用。每条事件流要记录格式和 schema 版本。删除 Protobuf 字段后不重用其编号或名称。新字段分配新编号，发布前用新生产者的字节测试旧消费者。JSON 字段名也是线上身份；改名须分阶段迁移消费者。

## 备选方案（Alternatives considered）

- 长期事件流使用 JSON：不用生成代码就能读，便于新消费者接入；但固定样例较大，每条消息都重复字段名。向前兼容还取决于每个消费者对未知字段的处理。
- 所有集成都使用 Protobuf：紧凑且字段号稳定；但简单的检查路径也需要 schema 和生成代码。

## 后果（Consequences）

团队要维护固定版本的 `protoc` 构建、分发 `.proto` 契约、审查 schema 变更并保留兼容性测试。两个 codec 已检查缺失的必需字段；金额的业务范围和币种有效性仍需按业务规则验证。事件编解码实现的代码不说明 broker 的投递或重放行为。
