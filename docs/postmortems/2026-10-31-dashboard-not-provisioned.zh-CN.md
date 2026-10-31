[English](2026-10-31-dashboard-not-provisioned.md) | 简体中文

# 事后复盘：发布后 Stubline 仪表盘未加载

## 摘要

1.1.0 发布后的第一次故障演练中，值班人员在 Grafana 找不到 **Stubline service health**。服务正常，但仪表盘 provisioning 文件挂载在此 LGTM 镜像不会读取的路径。值班人员只得在 Explore 手写查询，定位故障花费了更长时间。此次影响的是演练中的可观测性，没有证据表明买家数据丢失。

## 影响

演练时无法使用服务仪表盘及其六个面板。值班人员在 Explore 重建信号，故障定位因此延迟。记录没有可核实的延迟时长或买家影响。

## 时间线

- 1.1.0 发布：Compose 挂载仪表盘 JSON 与 provisioning YAML；配置校验和服务健康检查通过。
- 发布后第一次故障演练：Grafana 搜索只显示镜像自带的 JVM 与 RED 仪表盘。
- 排查：provisioning 挂载到 `/etc/grafana/provisioning/dashboards`，而 `grafana/otel-lgtm:0.38.1` 读取 `/otel-lgtm/grafana/conf/provisioning/dashboards`。
- 修复：团队在镜像实际路径挂载单个 provider 文件，显式选择内置 Prometheus 数据源，并加入值班视角的检查。

## 根因与触发条件

Compose 使用了 Grafana 常见的 provisioning 路径，而不是此镜像配置的路径。文件虽在容器内，Grafana 却没有读取 provider；因为该目录从未被读取，也没有报错。这是配置和验证流程的缺口，不归咎于个人。

## 现有检查为何全部通过

Compose 认可挂载，容器健康，Maven 测试覆盖应用行为，遥测也进入 LGTM。但没有检查向 Grafana 询问 Stubline 仪表盘是否加载、六个面板是否各自返回时间序列。镜像自带仪表盘仍可见，单纯的 Grafana 健康检查不足以发现问题。

## 解决办法

现在只把一个 provider 文件挂载到镜像实际使用的 dashboards provisioning 目录，保留自带 provider 和数据源。仪表盘显式选择镜像中的 `prometheus` 数据源。发布检查在示例流量后查询仪表盘 UID 和六个面板的表达式；数值为零仍算有时间序列，结果为空则失败。

## 做得好的方面

首次演练在真实故障依赖仪表盘之前暴露了值班视图缺失的问题。Explore 仍支持手动调查。

## 需要改进的方面

发布验证检查了服务与遥测健康，却没有检查值班人员预期使用的仪表盘。

## 幸运之处

问题发生在演练中。本记录不声称真实生产事故出现了同样的延迟。

## 行动项

| 负责人 | 行动 | 验证 |
|---|---|---|
| Wang Hao | 在 LGTM 的 provisioning 路径挂载 provider，保留镜像默认配置。 | Grafana API 同时列出 `stubline-service-health` 与内置仪表盘。 |
| Liu Jia | 在部署检查中运行 `scripts/check-dashboards.sh`。 | 六个面板都返回时间序列；仪表盘或序列缺失时退出码非零。 |
| Mei Lin | 将“验证值班人员实际看到的内容”加入发布清单。 | 发布说明包含仪表盘检查及值班视图确认。 |
