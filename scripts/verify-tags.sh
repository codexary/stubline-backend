#!/usr/bin/env bash
set -u
root=$(git rev-parse --show-toplevel)
worktree=$(mktemp -d)
cleanup() {
  git -C "$root" worktree remove --force "$worktree" >/dev/null 2>&1 || true
  rmdir "$worktree" >/dev/null 2>&1 || true
}
trap cleanup EXIT
failed=0
checked=0
tags=$(git -C "$root" tag --list 'v*' --sort=version:refname)
if [[ -z $tags ]]; then
  printf 'No release tags found\n' >&2
  exit 1
fi
while IFS= read -r tag; do
  if ! git -C "$root" worktree add --quiet --detach "$worktree" "$tag"; then
    printf '%s: worktree checkout failed\n' "$tag"
    failed=1
    continue
  fi
  if grep -q MavenWrapperMain "$worktree/mvnw"; then
    # Early tags used a jar wrapper whose jar was intentionally never committed.
    cp "$root/mvnw" "$worktree/mvnw"
    cp "$root/.mvn/wrapper/maven-wrapper.properties" "$worktree/.mvn/wrapper/maven-wrapper.properties"
    printf '%s: using the current script-only wrapper for the legacy tag\n' "$tag"
  fi
  printf '%s\n' "-Dmaven.repo.local=$root/.mvn/repository" > "$worktree/.mvn/maven.config"
  if (cd "$worktree" && ./mvnw -q verify); then
    printf '%s: PASS\n' "$tag"
  else
    printf '%s: FAIL\n' "$tag"
    failed=1
  fi
  git -C "$root" worktree remove --force "$worktree" >/dev/null
  mkdir -p "$worktree"
  checked=$((checked + 1))
done < <(printf '%s\n' "$tags")
printf 'Checked %d release tags\n' "$checked"
exit "$failed"
