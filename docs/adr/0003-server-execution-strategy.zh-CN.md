[English](0003-server-execution-strategy.md) | 简体中文

# ADR 0003：开售负载下的服务器执行策略

## 状态（Status）

提议中

## 背景（Context）

Grace Kim 宣布开售后，票务接口要同时服务许多买家。阻塞负载实验所用的原始 HTTP server 的一项 executor 任务覆盖整条 keep-alive 连接。固定池的四条 worker 可能都在等原有四位客户端的下一份请求，让后来连接继续排队。记录中的 Java 负载运行，FIXED_POOL 在三秒内完成 112 份成功请求，THREAD_PER_REQUEST 为 896 份，VIRTUAL 为 864 份。逐客户端报告显示 FIXED_POOL 的 32 位客户端中有 28 位一次也没完成。FIXED_POOL 成功请求的 p95 约 109 ms，没有显示排队的客户端。阻塞负载实验中加入两秒请求超时和失败阈值的 k6 脚本，记录 FIXED_POOL 的 1043 次检查中 280 次失败（26.84%）、exit 99；另外两种策略在那次记录中检查失败率为 0%。

## 决策（Decision）

当前实现的原始 server 对连接任务使用 VIRTUAL。产品 HTTP 实现继续使用 JDK HTTP server；它按 exchange 分配 executor 任务。现有数字只证明这份模拟阻塞负载的表现。承诺开售容量前，必须用真实数据库调用、有界数据库连接池和代表性客户端重测。

在原始 HTTP server 上，固定池的 worker 被 keep-alive 连接占用；这次开售负载不选该配置。虚拟线程可承载等待，但数据库连接数和请求排队仍须单独限制。

## 备选方案（Alternatives considered）

- THREAD_PER_REQUEST：通过了记录中的负载，但原始 server 的每条长期连接都占一条平台线程；连接继续增长会增加资源成本。
- FIXED_POOL：限制正在运行的平台 worker，但当前原始 server 把 worker 交给 keep-alive 连接，记录中出现饥饿；这里的工作队列也没有容量上限。
- VIRTUAL：许多阻塞连接不必各占一条平台线程，但它不限制数据库并发，不缩短调用等待，也不能消除所有 carrier pinning。

## 后果（Consequences）

在这次负载下，原始 server 可服务更多等待中的客户端。运维要同时看请求失败率和成功请求延迟。团队仍需下游并发上限、超时与真实数据库压测。健康检查器小项目的期限设计将用于后续支付方调用。
