English | [简体中文](2026-10-31-dashboard-not-provisioned.zh-CN.md)

# Post-mortem: Stubline dashboard absent after release

## Summary

During the first failure drill after the 1.1.0 release, the on-call engineer could not find **Stubline service health** in Grafana. The stack was healthy, but the dashboard provisioning file was mounted at a path this LGTM image does not read. The on-call engineer wrote queries by hand in Explore; locating the fault took longer. The incident concerns observability during the drill, not a loss of buyer data.

## Impact

The service dashboard and its six panels were unavailable during the drill. The on-call engineer used Explore to reconstruct the signals, delaying fault isolation. The record does not establish a precise delay or any buyer impact.

## Timeline

- 1.1.0 release: Compose mounted the Stubline dashboard JSON and provisioning YAML. Compose validation and service health checks passed.
- First post-release failure drill: on-call found only the image's JVM and RED dashboards in Grafana search.
- Investigation: the provisioning mount targeted `/etc/grafana/provisioning/dashboards`, while `grafana/otel-lgtm:0.38.1` reads `/otel-lgtm/grafana/conf/provisioning/dashboards`.
- Fix-forward: the team mounted one provider file at the image path, selected its built-in Prometheus datasource explicitly, and added an operator-view check.

## Root cause and trigger

The Compose mount used Grafana's conventional provisioning path rather than this image's configured path. The files existed inside the container but Grafana did not load the provider. No provisioning error was emitted because Grafana never read that directory. This is a configuration and verification gap, not an individual failure.

## Why existing checks stayed green

Compose validated the mounts, containers were healthy, Maven tests covered application behavior, and telemetry reached LGTM. None of those checks asked Grafana whether the Stubline dashboard was loaded or whether each panel returned a series. Grafana's own dashboards remained visible, making a general Grafana health check insufficient.

## Resolution

The provider is now mounted as one file in the image's active dashboards provisioning directory, leaving its built-in providers and datasources in place. The dashboard selects the image's `prometheus` datasource. A release check queries the dashboard UID and all six panel expressions after sample traffic; a zero value counts as a series, while an empty result fails.

## What went well

The first drill exposed the missing operator view before relying on the dashboard during a real failure. Explore still allowed manual investigation.

## What went wrong

Release verification checked service and telemetry health but not the dashboard the on-call engineer expected to use.

## Where we got lucky

The failure occurred in a drill. The record does not claim that the same delay occurred in a production incident.

## Action items

| Owner | Action | Verification |
|---|---|---|
| Wang Hao | Mount the provider at the LGTM provisioning path without replacing image defaults. | Grafana API lists `stubline-service-health` alongside built-in dashboards. |
| Liu Jia | Run `scripts/check-dashboards.sh` in the deployment check. | Each of six panels returns a series; missing dashboard or series exits non-zero. |
| Mei Lin | Add “verify what the operator sees” to the release checklist. | Release instructions include the dashboard check and operator review. |

## Follow-up

After the dashboard loaded, on-call opened it and found that the JDK HTTP server labeled business requests with its `/` context. The follow-up fix supplies bounded route templates to the server instrumentation, excludes probe and admin routes from the HTTP panels, and requires a business-route series in the dashboard check.
