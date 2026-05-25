English | [简体中文](container-memory-exit.zh-CN.md)

# Runbook: Investigate a container memory exit

## When to use

A Stubline container exits or restarts, especially when the Java log has no stack trace.

## First checks

1. Preserve the previous container's logs and inspect state before removing it. Record exit code, `OOMKilled`, restart time, and the configured memory limit.
2. If a container is still running, use `docker stats` for current usage and, for `ticketing-api`, `docker compose exec ticketing-api cat /sys/fs/cgroup/memory.max` for its cgroup limit. Query `/diagnostics/runtime` from inside the API container; the admin listener binds only container loopback.
3. Correlate application log lines with the exit state. `Java heap space` plus exit 1 and `OOMKilled=false` points to a JVM heap failure. Exit 137 plus `OOMKilled=true` points to a kernel memory kill. Exit 137 alone does not establish OOM.
4. Inspect heap settings, direct and other native allocation, cgroup limit, and recent restarts before changing any limit. Check whether readiness failed separately from process termination.

## Experiment reference

The `stubline-spike-cgroup-memory` `done` tag uses 256 MiB containers. The recorded heap case reached step 7 (56 MiB) with `Java heap space`, `OOMKilled=false ExitCode=1`; the percentage case reached step 17 (136 MiB) with the same failure class and state. The direct case last printed `step=88 allocatedMiB=704`, then `OOMKilled=true ExitCode=137`. Steps vary by run. These are experiment outputs, not a Stubline production incident.
