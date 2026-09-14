#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=${1:?Usage: build-release.sh VERSION}
if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  echo 'Refusing release build: working tree is dirty' >&2
  exit 1
fi
revision=$(git rev-parse --short=12 HEAD)
./mvnw -q verify
docker build -f observability/collector.Dockerfile -t stubline/otel-collector:0.164.0 .
for service in ticketing-api inventory-service notification-service payment-provider-stub dev-token-issuer; do
  image="stubline/$service"
  docker build -f "$service/Dockerfile" --build-arg VERSION="$version" --build-arg REVISION="$revision" -t "$image:$version-$revision" .
  docker tag "$image:$version-$revision" "$image:$version"
  docker tag "$image:$version-$revision" "$image:$revision"
done
printf 'RELEASE_VERSION=%s\nRELEASE_COMMIT=%s\n' "$version" "$revision" > .env.release
printf 'Built release %s from %s; source .env.release before Compose up --no-build.\n' "$version" "$revision"
