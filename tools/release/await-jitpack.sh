#!/usr/bin/env bash
# Starts JitPack's build of a release tag and waits until JitPack serves it.
#
#   await-jitpack.sh 0.10.0 <commit the tag points at>
#
# Passes only when JitPack built that commit and published every module the tag's jitpack.yml
# installs.
set -euo pipefail

readonly TAG="${1:?Usage: await-jitpack.sh <tag> <commit>}"
readonly COMMIT="${2:?Usage: await-jitpack.sh <tag> <commit>}"
readonly GROUP="com.github.d4rken-org"
readonly REPO="porter-api"
readonly ATTEMPTS="${JITPACK_POLL_ATTEMPTS:-60}"
readonly INTERVAL="${JITPACK_POLL_INTERVAL_SECONDS:-30}"

die() {
  echo "::error::$*" >&2
  exit 1
}

jitpack_yml="$(git show "${TAG}:jitpack.yml")" || die "No jitpack.yml at ${TAG}"
expected_modules="$(grep -oE ':[A-Za-z0-9_-]+:publishReleasePublication' <<<"$jitpack_yml" | cut -d: -f2 | sort -u)" ||
  die "jitpack.yml at ${TAG} names no published modules"
probe_module="$(head -n 1 <<<"$expected_modules")"
group_path="${GROUP//.//}"

# JitPack builds a tag on the first request for one of its artifacts. The request blocks while
# the build runs and may time out; the build carries on regardless.
request_build() {
  curl -s -o /dev/null -m 60 \
    "https://jitpack.io/${group_path}/${REPO}/${probe_module}/${TAG}/${probe_module}-${TAG}.pom" || true
}

shopt -s nocasematch
request_build
status="none"
for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
  if json="$(curl -sf -m 30 "https://jitpack.io/api/builds/${GROUP}/${REPO}/${TAG}")"; then
    status="$(jq -r '.status' <<<"$json")"
  else
    status="unreachable"
  fi

  case "$status" in
    ok)
      built_commit="$(jq -r '.commit' <<<"$json")"
      [[ "$built_commit" == "$COMMIT" ]] ||
        die "JitPack built $TAG from $built_commit, but the tag points at $COMMIT"
      built_modules="$(jq -r '.modules[]' <<<"$json" | sort -u)"
      missing="$(comm -23 <(echo "$expected_modules") <(echo "$built_modules"))"
      [[ -z "$missing" ]] || die "JitPack's build of $TAG lacks: $(paste -sd " " <<<"$missing")"
      echo "JitPack built $TAG from $COMMIT with modules: $(paste -sd " " <<<"$built_modules")"
      exit 0
      ;;
    *error* | *fail*)
      die "JitPack's build of $TAG ended with status '$status': https://jitpack.io/${group_path}/${REPO}/${TAG}/build.log"
      ;;
    none)
      request_build
      ;;
  esac

  echo "JitPack status for $TAG: $status (attempt $attempt/$ATTEMPTS)"
  sleep "$INTERVAL"
done

die "JitPack's build of $TAG did not finish; last status '$status'"
