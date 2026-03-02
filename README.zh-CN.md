[English](README.md) | 简体中文

# Stubline

Stubline 是 Elsworne 的活动票务平台，涵盖活动、座位、订单、支付、通知和电子票。当前模块化单体的 `ticketing-api` 包含 `catalog`、`inventory`、`orders`、`health` 包。库存位于内存中，重启后会清空。后续章节才会加入其他功能。

## 构建与测试

安装 Java 21。随仓库提供的 Maven wrapper 会下载 Maven 3.9.16。在仓库根目录运行：

```sh
./mvnw -q verify
```

预期退出码为 0；`-q` 隐藏正常构建输出。构建会运行 JUnit 测试和 Spotless 检查。运行 `./mvnw spotless:apply` 可自动格式化。

参见[项目团队](docs/team.zh-CN.md)。

本地运行 `./mvnw -q verify` 可执行 CI 检查，并生成 `target/bom.json`。CI 扫描文件系统和 SBOM，对有修复版本的 HIGH、CRITICAL 漏洞失败。安装 Renovate app 后，它才会按 Maven、Docker 镜像和 GitHub Actions 分组提出更新。

## 许可证

本仓库采用 [MIT License](LICENSE)。

## 关于本仓库

Elsworne、Stubline 和团队成员均为虚构。

本仓库的代码与文档借助 AI 工具编写；记录的输出都来自实际运行。
