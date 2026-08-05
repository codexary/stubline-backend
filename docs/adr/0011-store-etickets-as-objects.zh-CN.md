[English](0011-store-etickets-as-objects.md) | 简体中文

# ADR 0011：把电子票存为对象

## 状态（Status）

2026-08-06 采纳。

## 背景（Context）

付款后，买家需要从任何地方下载 PDF 电子票。API 不应自己传输文件。PostgreSQL 已保存订单，可以保存对象键。同事提出共享 NFS 挂载。NFS 缓存实验表明，两个客户端可能在属性重新检查或重新打开前读到不同内容，具体取决于挂载选项。若将票据字节放入数据库列，交易数据库及其备份也要承载文件。

## 决定（Decision）

`ticketing-api` 渲染 PDF，通过 S3 API 上传到 `stubline-tickets` bucket 的 `tickets/<order-id>.pdf`，随后在订单行锁下通过条件更新记录对象键。并发签发者等待，看到键已登记后跳过上传。`GET /orders/{id}/ticket` 查询键并返回有效五分钟的 presigned GET URL。客户端从对象存储下载。Compose 本地环境使用 SeaweedFS 的 S3 gateway，并配置 endpoint override 和 path-style 寻址；AWS S3 不设置 override。票据键视为不可变，按订单记录的精确键查询，不依赖前缀 listing。

## 考虑过的替代方案（Alternatives considered）

- PostgreSQL 字节列：事务内查询较简单，但文件会扩大数据库和备份；API 传输文件的问题仍需解决。
- 共享 NFS 卷：保留文件路径接口，但每个 API 副本都要挂同一个 export，并接受文件缓存的新鲜度和写入策略。电子票只需整对象上传和下载，不需共同修改打开的文件。
- 从 API 实例本地磁盘传输：下载绑定到某个 API 实例，文件流量也占用 API 容量。

## 后果（Consequences）

URL 在过期前是单个对象的持有者凭据，不能随意写入日志。买家可访问的 endpoint 的主机名必须与签名请求一致。对象服务须独立于 API 可达。上传与数据库记录键并非同一事务：记录失败可留下无引用对象，付款后上传失败需要恢复。订单行锁串行化并发签发：键已登记后，第二个签发者跳过上传。若上传成功、数据库提交前失败，重试仍可能对同一个确定性键再次 PUT。当前票据路由没有买家授权；向不可信买家开放前必须补齐身份认证和授权。
