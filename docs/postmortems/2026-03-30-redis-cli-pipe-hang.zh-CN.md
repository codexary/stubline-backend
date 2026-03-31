[English](2026-03-30-redis-cli-pipe-hang.md) | 简体中文

# 事后复盘：redis-cli pipe 等不到最后一个回复

## 摘要（Summary）

RESP 小项目自己的流水线测试通过了。真正的 `redis-cli --pipe` 却等不到最后一个回复：它在二进制 ECHO 标记前插入一个空行，旧解析器在空行进入 inline 分支，又没有正确处理已经读到的回车字节，把下一条 RESP 命令的标记当成 inline 文字，返回错误而没有回送二进制标记。

## 影响（Impact）

小项目无法完成这次真实客户端互操作检查。它是实验服务器，没有承载正式售票流量。

## 时间线（Timeline）

- 初版 RESP server 已能处理数组、分段写入和流水线命令。
- 二进制安全修复让 key、value 和 ECHO 回复保留原始字节。
- 真正的 `redis-cli --pipe` 暴露了剩余的等待问题。
- `scripts/capture-pipe-bytes.sh` 录到最后 ECHO 前的空 CRLF。
- 解析器改为跳过空行，并为每条命令重新选择 RESP 或 inline；回归测试加入这一序列。

## 根因与触发条件（Root cause and trigger）

客户端在 SET 流水线与最终 ECHO 之间插入空行。旧版读行函数没先判断已读到的回车，因此吞入后面的 RESP 标记并将其当成 inline 文字，返回错误。内部测试直接生成 ECHO，省略了这个触发条件。

## 解决（Resolution）

正确处理行的首字节，跳过空 inline 行，再读取新的命令标记。bulk payload 始终按字节保存。socket 回归测试检查最后的二进制 ECHO；真正的客户端随后能完成 pipe 交换。

## 做得好的地方（What went well）

真实客户端和字节捕获指出了两边不一致的位置。原有测试已经覆盖分段写入及单连接多条命令。

## 做得不好的地方（What went wrong）

测试构造的是团队预期的协议输入，没有包括真实客户端额外发送的空行。

## 幸运之处（Where we got lucky）

问题在小项目阶段发现，票务服务尚未依赖这份实现。

## 行动项（Action items）

- 保留空行及二进制 payload 的回归测试。
- 采用协议实现时，用真正的客户端做互操作检查。
- 票务缓存若要用 Redis，采用维护中的 Redis 服务；不把这个小项目当作生产服务器。
