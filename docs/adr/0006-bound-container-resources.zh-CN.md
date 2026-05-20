[English](0006-bound-container-resources.md) | 简体中文

# ADR 0006：限制容器资源

## 状态（Status）

2026-05-21 提议。

## 背景（Context）

`ticketing-api` 与 `payment-provider-stub` 已是两个 Java 进程。Wang Hao 需要在平台团队的容器主机上给它们可重复的资源边界，并在故障时辨别 JVM 堆不足与内核 OOM kill。容器资源实验中的 Compose 栈还运行 PostgreSQL。

## 决策（Decision）

`ticketing-api` 与 PostgreSQL 各设 1 CPU、512 MiB；`payment-provider-stub` 设 0.5 CPU、256 MiB。Java 进程使用固定版本的 Temurin 21 JRE、UID 10001 和 `-XX:MaxRAMPercentage=60`。值班时把应用日志与容器退出状态一起读，不能用“没有 Java 堆栈”推断进程没有发生内存故障。

两个 Java 服务分别用 JDK 构建阶段与 JRE 运行阶段制作镜像。使用就绪健康检查。运行诊断留在容器回环管理监听器。值班时先合看退出码、`OOMKilled` 和 cgroup 限制，再更改堆或容器限额。

## 考虑过的方案（Alternatives considered）

- 直接在主机上运行 JAR：适合调试，但仍须由主机服务管理机制明确设置资源和重启规则。
- 不限制容器：配置简单，但一个进程可能占用邻居需要的内存。
- 让 `-Xmx` 等于容器内存上限：没有给直接缓冲区、线程栈、元数据与其他 native 内存留空间。

## 后果（Consequences）

本地栈有可重复的资源边界和明确的诊断路径。限额数值仍须在生产流量下测量。`-XX:MaxRAMPercentage=60` 限制 Java 堆，而非 JVM 全部内存。容器资源实验在 API 容器内记录 `availableProcessors=1`、`maxHeapBytes=312213504`、`containerMemoryLimitBytes=536870912`。独立内存小项目记录 Java 堆 OOM 为 exit 1、`OOMKilled=false`，内核终止为 exit 137、`OOMKilled=true`。
