[English](container-memory-exit.md) | 简体中文

# Runbook：排查容器内存退出

## 适用情形（When to use）

Stubline 容器退出或重启，尤其是 Java 日志中没有堆栈时。

## 首先检查（First checks）

先保存退出前日志和容器状态。`OOMKilled=true` 与 137 同时出现时，检查 cgroup 限制及堆外用量；出现 `Java heap space` 且 `OOMKilled=false` 时，先查 JVM 堆配置与分配路径。

1. 移除旧容器之前保存退出前日志与容器状态。记录退出码、`OOMKilled`、重启时间和配置的内存限额。
2. 容器仍在运行时，用 `docker stats` 看当前用量。对 `ticketing-api`，用 `docker compose exec ticketing-api cat /sys/fs/cgroup/memory.max` 读 cgroup 限额；从 API 容器内部请求 `/diagnostics/runtime`，因为管理监听器只绑定该容器的回环地址。
3. 将应用日志与退出状态对齐。`Java heap space`、exit 1 且 `OOMKilled=false` 指向 JVM 堆失败。exit 137 且 `OOMKilled=true` 指向内核按内存限制终止进程。只有 137 不足以证明 OOM。
4. 更改限额前，先查堆配置、直接及其他 native 内存、cgroup 限额和最近重启。另行确认是否只是就绪检查失败，而非进程退出。

## 实验参照（Experiment reference）

`stubline-spike-cgroup-memory` 的 `done` tag 使用 256 MiB 容器。记录中，heap 模式第 7 步（56 MiB）出现 `Java heap space`，`OOMKilled=false ExitCode=1`；percentage 模式第 17 步（136 MiB）出现同类失败与状态。direct 模式最后打印 `step=88 allocatedMiB=704`，随后为 `OOMKilled=true ExitCode=137`。具体步数因运行而变。这些是实验输出，不是 Stubline 生产事故。
