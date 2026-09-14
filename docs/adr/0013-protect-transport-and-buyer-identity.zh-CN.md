[English](0013-protect-transport-and-buyer-identity.md) | 简体中文

# ADR 0013：保护传输和买家身份

## 状态（Status）

Stubline 上线审查草案。

## 背景（Context）

库存服务发现实现中的服务发现让 `ticketing-api` 找到 `inventory-service`；DNS 本身不能认证对方。公开 API 接收买家请求并返回票据下载 URL。买家不能取走别人的票。传输安全实现中的开发发行者允许调用者指定 `sub` 来领 token，因此只作本地测试，不是生产身份提供者。

## 决定（Decision）

公开 API 使用 HTTPS，库存 gRPC 使用要求客户端证书的 mTLS；买家 token 必须验签、验时间、验发行者和受众，付款和取票前按 `sub` 检查订单归属。

公开 JDK API 使用 HTTPS，服务器证书的 SAN 必须匹配访问名字。库存 gRPC 使用 mTLS：API 依据内部 CA 验证库存服务，库存服务要求同一 CA 签发的客户端证书。公开与内部开发 CA 分开。

建单、付款、取票要求 buyer bearer token。固定 RS256；经验证 HTTPS 从发行者 JWKS 按 `kid` 选公钥；检查签名、`exp`、允许 30 秒时钟偏差的 `nbf`、`iss`、`aud=stubline-api` 和非空 `sub`。订单保存 `sub`；付款前检查归属，然后才调用 provider，取票前再次检查归属，然后才给下载 URL。若没有所有权存储，受保护的付款请求返回 503。缺失或无效 token 返回 RFC 6750 Bearer challenge。

轮换签名钥匙时，新 token 使用新 `kid`，旧公钥保留最长 300 秒 token 有效期。公开上线前替换无认证的开发发行者。独立 Keycloak 小项目评估买家的 S256 PKCE 授权码流程和服务的 client credentials；Spring Security resource server 仍需明确配置 audience 与授权规则。

## 备选方案（Alternatives considered）

- 普通 HTTP 与明文 gRPC 运行简单，但无法在不可信网络中保护流量或认证对端。
- 只有服务端 TLS 能阻止 API 连到假库存服务，却不能让库存服务确认调用方身份。
- 只检查签名而不查发行者、受众、时间和所有权，会允许跨服务错用、过期凭据、替别人的订单付款或访问别人的票据。
- Kerberos 有 KDC 签发的服务票据和 authenticator，但其密钥分发与客户端环境不同于这里的公开浏览器和 OAuth2 环境。

## 后果（Consequences）

证书签发、私钥存放、续期、信任分发和握手失败诊断成为运维工作。`dev-ca.sh` 把挂载私钥及 keystore 设为 `600`，属主为容器服务 UID 10001；正式环境仍需独立的密钥管理。被盗 bearer token 在过期前可被重放，因此仍要使用 TLS 并妥善保存 token。开发证书和无认证发行者仅限本地。传输安全实现中的 mTLS、JWT 与付款归属测试覆盖接受和拒绝；Keycloak 小项目的 Docker 测试覆盖角色与 audience 映射。当前产品仍使用开发发行者。
