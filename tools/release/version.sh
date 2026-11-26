#!/usr/bin/env bash
# SDK release versions are the git tags themselves: X.Y.Z, or X.Y.Z-suffix for a pre-release.
# JitPack turns the tag into the dependency version, so tags carry no "v" prefix.
#
#   version.sh next --bump-kind=minor                 0.9.0 -> 0.10.0
#   version.sh next --version-override=1.0.0-rc1      0.9.0 -> 1.0.0-rc1 (pre-release)
#   version.sh check-tag 1.0.0                        exits non-zero unless the tag is well formed
#   version.sh is-latest-stable 0.9.1                 false once 0.10.0 exists
#
# `next` prints current=, new= and prerelease= lines, ready for $GITHUB_OUTPUT.
set -euo pipefail

readonly NUM='(0|[1-9][0-9]*)'
readonly TAG_PATTERN="^${NUM}\.${NUM}\.${NUM}(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?\$"
readonly STABLE_PATTERN="^${NUM}\.${NUM}\.${NUM}\$"

die() {
  echo "::error::$*" >&2
  exit 1
}

check_tag() {
  [[ "$1" =~ $TAG_PATTERN ]] || die "'$1' is not a release tag (X.Y.Z or X.Y.Z-suffix, no leading zeros)"
  git check-ref-format "refs/tags/$1" || die "'$1' is not a valid git tag name"
}

# The newest X.Y.Z tag. Pre-release tags never serve as the base of a bump.
latest_stable() {
  git tag --list | { grep -E "$STABLE_PATTERN" || true; } | sort -V | tail -n 1
}

# True when core version $1 sorts strictly after core version $2.
newer_than() {
  [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" == "$1" ]]
}

remote_tag_absent() {
  local status=0
  git ls-remote --exit-code --tags origin "refs/tags/$1" >/dev/null || status=$?
  case "$status" in
    0) return 1 ;;
    2) return 0 ;;
    *) die "Could not query origin for tag $1 (git ls-remote exit $status)" ;;
  esac
}

next() {
  local bump_kind="" override="" expected=""
  for arg in "$@"; do
    case "$arg" in
      --bump-kind=*) bump_kind="${arg#*=}" ;;
      --version-override=*) override="${arg#*=}" ;;
      --expected-current=*) expected="${arg#*=}" ;;
      *) die "Unknown argument: $arg" ;;
    esac
  done

  local current
  current="$(latest_stable)"
  [[ -n "$current" ]] || die "No X.Y.Z tag found to bump from"
  if [[ -n "$expected" && "$expected" != "$current" ]]; then
    die "Latest release is $current, expected $expected"
  fi

  local new
  if [[ -n "$override" ]]; then
    check_tag "$override"
    newer_than "${override%%-*}" "$current" ||
      die "$override does not move past the latest release $current"
    new="$override"
  else
    local major minor patch
    IFS=. read -r major minor patch <<<"$current"
    case "$bump_kind" in
      major) new="$((major + 1)).0.0" ;;
      minor) new="$major.$((minor + 1)).0" ;;
      patch) new="$major.$minor.$((patch + 1))" ;;
      *) die "--bump-kind must be major, minor or patch, got '$bump_kind'" ;;
    esac
  fi

  if git rev-parse --verify --quiet "refs/tags/$new" >/dev/null; then
    die "Tag $new already exists locally"
  fi
  remote_tag_absent "$new" || die "Tag $new already exists on origin"

  local prerelease=false
  [[ "$new" == *-* ]] && prerelease=true

  echo "current=$current"
  echo "new=$new"
  echo "prerelease=$prerelease"
}

case "${1:-}" in
  next) shift; next "$@" ;;
  check-tag) [[ $# -eq 2 ]] || die "Usage: version.sh check-tag <tag>"; check_tag "$2" ;;
  is-latest-stable)
    [[ $# -eq 2 ]] || die "Usage: version.sh is-latest-stable <tag>"
    if [[ "$2" == "$(latest_stable)" ]]; then echo true; else echo false; fi
    ;;
  *) die "Usage: version.sh next|check-tag|is-latest-stable ..." ;;
esac
