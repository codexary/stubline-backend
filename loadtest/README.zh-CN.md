[English](README.md) | 简体中文

先在主机 8080 端口启动 ticketing API，再从仓库根目录运行 k6：

```sh
docker run --rm --add-host=host.docker.internal:host-gateway -v "$PWD/loadtest:/scripts:ro" grafana/k6:2.3.0 run /scripts/seats.js
```

脚本使用 32 个虚拟用户，持续 20 秒。对比不同 executor 的 `http_reqs` 和延迟分位数。镜像是官方 `grafana/k6`。命令会输出所选 executor 的请求数和延迟分位数。

## 关于本仓库

本仓库的代码与文档借助 AI 工具编写；记录的输出都来自实际运行。
