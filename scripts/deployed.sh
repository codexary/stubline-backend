#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env.release
set +a
for container in $(docker compose -f compose.yaml -f compose.release.yaml ps -q); do
  docker inspect --format '{{.Name}} image={{.Config.Image}} version={{index .Config.Labels "org.opencontainers.image.version"}} commit={{index .Config.Labels "org.opencontainers.image.revision"}}' "$container"
done
