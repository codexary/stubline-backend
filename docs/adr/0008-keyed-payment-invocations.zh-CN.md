[English](0008-keyed-payment-invocations.md) | 简体中文

# ADR 0008：在 API 和支付方两端识别同一次支付调用

## 状态（Status）

2026-06-24 采纳。

## 背景（Context）

最初支付方客户端的扣款路由只发送一次，因为超时不能证明支付方没有扣款。但买家再次按下支付，或网关重发请求，仍可能产生第二笔扣款。请求和回复可以分别丢失。API 与支付方也各自保存记录。

[webhook receiver spike](https://github.com/codexary/stubline-spike-webhook-receiver/blob/main/README.zh-CN.md) 展示了支付通知可能重复送达：接收端对时间戳和原始正文验 HMAC，在五分钟窗口内接收，并按事件 ID 去重。它不能让扣款请求本身变成幂等。Stubline 尚未接入支付方 webhook。

## 决定（Decision）

`POST /payments` 必须带 `Idempotency-Key`。同一 key 与完全相同的正文原始字节属于同一次调用。调用支付方前先占有 key；完成后保存状态码、原因短语和正文，重试时原样返回。同一调用仍在进行时返回 409，同一 key 换正文返回 422。只有支付方明确在扣款前拒绝，才释放 key。结果不明时保持 `IN_FLIGHT`，等待核对。

把同一个 key 传给支付方的 `POST /payments`。部署时 API 使用 PostgreSQL 存储；本地演示可用内存存储。完成记录 24 小时后过期，进行中记录不自动过期。人工释放未知结果之前须先核对。

带键支付实现中的支付方客户端用 Jackson 构造转发的 JSON，因此 `orderId` 中的特殊字符会被转义。它把支付方的 422 key 冲突与 402 及其他 4xx 拒绝分开；stub 把后两者视为确定的扣款前失败。真正接入支付方时，须按其合同确认这一分类。5xx 或 I/O 故障会保留占有。

## 考虑过的方案（Alternatives considered）

- 永远不重试扣款：减少自动重复，却不能阻止买家和网关再次提交，也不能解开超时留下的疑问。
- 不带 key 重试：第一次回复丢失时可能再次扣款。
- 只在 API 去重：支付方已扣款而 API 记录丢失时无法保护重试。
- 只丢弃重复请求、不保存回复：可以挡住本地第二次调用，却不能把第一次结果交还给重试者。

## 后果（Consequences）

key 和正文哈希组成有期限的请求历史。PostgreSQL 唯一键使并发占有只能成功一次，保存的回复能跨 API 重启。支付方收到相同 key 后，即使 API 历史丢失，也能返回原扣款。PostgreSQL 与支付方之间没有原子事务。演示支付方的历史只在内存里，API 的 24 小时过期也限制了保护范围。运维必须对照支付方扣款、API 记录和订单，才能处理卡在 `IN_FLIGHT` 的请求。

webhook spike 为以后接入支付方回调提供依据。它的内存事件 ID 和尚未实现的持久化业务效果不足以直接上线。
