[English](0012-discover-inventory-replicas.md) | 简体中文

# ADR 0012：通过名字发现库存副本

## 状态（Status）

2026-08-31 采纳。

## 背景（Context）

开售前把 `inventory-service` 从一个副本扩为两个。Compose DNS 在扩容后返回两个地址，但只解析一次并用第一个数字地址建立 channel 的客户端仍固定在一个副本上。gRPC 的长期健康连接也不因 JVM DNS 缓存 TTL 到期自动重连。演示记录中，扩容前诊断返回一个地址，扩容后返回两个；连接年龄等待后，24 次请求分别由两个副本处理 11 次和 13 次。

## 决定（Decision）

保留 `inventory-service:9090` 作为 channel 目标；使用 gRPC DNS name resolver 和 `round_robin`。在 `ticketing-api` JVM 设置 `-Dsun.net.inetaddr.ttl=5`，并通过内部 `/diagnostics/dns?name=` 报告 JVM 所见地址和缓存配置。给库存服务的连接设置十五秒最大年龄和五秒宽限期，使长期健康的连接也会退役，客户端有机会发起刷新；gRPC 仅在距上次解析至少三十秒时才执行重新解析。演示中从 API 首次解析到刷新已超过三十秒。五秒 JVM 正向缓存只限制本层旧答案，不承担连接刷新。十五秒是演示值，生产值需按部署频率、连接数和排空要求另定。

## 考虑过的替代方案（Alternatives considered）

- 固定单个容器地址：配置简单，但扩容或容器重建后必须人工更新。
- 每次 RPC 前用 JDK 解析：可获得较新的地址集合，但增加查询和连接管理成本；已有 channel 仍需另行刷新。
- 独立代理或服务网格：可集中处理发现和分发，但引入新的部署、监控和故障点；本地 Compose 阶段暂不需要。

## 后果（Consequences）

连接退役增加 DNS 查询、TCP 重连及未来 TLS 握手开销，可能让 RPC 在切换时短暂重新分布。grace 给进行中的 RPC 留时间，但超过它的调用仍可能失败。`round_robin` 不保证有限样本严格各半，且健康地址也不保证业务调用成功。诊断监听器仅在容器 loopback 上监听；业务入口对 `/diagnostics/` 返回 404，管理入口才处理诊断请求。观察地址集合还需结合各副本日志判断实际流量。DNS 缓存、gRPC 连接和健康检查是不同层次，需要分别排查。
