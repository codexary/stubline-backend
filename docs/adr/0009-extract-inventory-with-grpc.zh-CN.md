[English](0009-extract-inventory-with-grpc.md) | 简体中文

# ADR 0009：用 gRPC 拆出库存

## 状态（Status）

2026-07-13 采纳。

## 背景（Context）

此前 `ticketing-api` 和座位库存在同一个进程。库存需要单独发布和扩容。Tomás Ribeiro 提醒：权威座位数据也必须随组件移动，不能让两个服务各自持有一份权威副本。预订会改变状态，回复丢失后结果便不确定。

## 决定（Decision）

`inventory-api` 保存 Protobuf 服务契约和生成的 Java 类。Compose 为 `inventory-service` 和 `ticketing-api` 建立独立的 `inventory`、`ticketing` 数据库及用户；库存服务拥有座位表，在 9090 端口实现 gRPC `ListSeats` 和 `Reserve`。`ticketing-api` 每次调用生成的 blocking stub 都设置两秒 deadline，并在 HTTP 边界转换 `NOT_FOUND`、`FAILED_PRECONDITION`、`DEADLINE_EXCEEDED` 和 `UNAVAILABLE`。`InMemoryInventory` 留给独立测试。Compose 探针使用标准 gRPC health 服务。API 客户端还以最多 500 毫秒的 deadline 查询 gRPC 总体健康状态，并将异常视为未就绪。

`Reserve` 超时后，不推断座位仍可售，也不自动再发一笔没有请求键的订单。先查权威座位状态，核对订单结果。以后若为预订加请求键并保存结果，可以依照 ADR 0008 的支付设计安全重放。

## 考虑过的方案（Alternatives considered）

- 库存继续留在 API 进程：少了网络故障路径，但无法独立发布和扩容。
- Java RMI：接口直观，但 Java 耦合和 registry、export 模型不如共享 schema 适合这里的服务边界。
- REST 与 JSON：便于检查，适合公开 HTTP 边界；这个内部调用更需要生成的消息和服务类型。

## 后果（Consequences）

API 现在依赖远端服务，须区分业务拒绝和传输失败。deadline 只限制等待，不能证明服务端没有在稍后生效。库存数据及其迁移归 `inventory-service`。库存服务拆分时的代码尚无预订幂等键，结果不明时仍需核对。

API 就绪客户端查询空名的 gRPC 总体健康状态，`InventoryMain` 则依据数据库就绪状态更新具名的 `stubline.inventory.v1.Inventory`。当前测试验证总体状态，尚未证明数据库故障会传到 API 就绪结果。依赖这条传播路径前，需要对齐两处服务名。
