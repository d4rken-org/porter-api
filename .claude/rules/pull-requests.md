# Pull request labels

Read the actual diff against the PR's base before choosing labels. Fetch the exact names with
`gh label list --repo d4rken-org/porter-api --limit 100 --json name` and use them verbatim. Never
invent a label. If the right one does not exist, omit it and say so rather than substituting a near
match.

## The one mandatory label

Every PR carries exactly one type label:

- `bug`: restores intended SDK or tooling behavior.
- `enhancement`: adds or improves the API surface, behavior or supported capabilities.
- `documentation`: changes explanatory content only, including the public API reference.
- `Chore`: refactors, tests, cleanup, and routine build or dependency maintenance.

Pick the principal outcome for mixed work. Supporting documentation does not add a second type.
Test-only work is `Chore`; a fix that ships with regression tests is `bug`. A broken CI job repaired
is `bug`, routine CI maintenance is `Chore`.

## Labels CI already applies

`.github/workflows/pr-labeler.yml` applies every label listed in `.github/labeler.yml` from the paths
a PR touches: the `c:` components and `Build process`. Do not apply those by hand and do not prune
them. A component label means the area is touched, including its tests and tooling.

`c: Protocol` is not a breaking-change marker. Editing `aidl/build.gradle.kts` legitimately produces
`c: Protocol` alongside `Build process`. An SDK release version bump is `Build process` alone, because
release versions are independent of the protocol version; a change to the constants in
`PorterProtocol` or `ShizukuApiConstants` is what makes a PR a protocol change.

To correct a durable mapping error, fix `labeler.yml`. Not every mismatch is a glob error: the
workflow reads its config from the base branch, so a mapping change only takes effect after it merges,
and because it never removes labels, one can survive after its matching file leaves the diff.

## Labels the agent never applies

`needs info/repro 🤔` and `Out of scope` are human issue triage. Do not copy labels wholesale from a
linked issue, and do not remove a label a human added.

## Relationship to the Porter app

This repository is consumed by `d4rken-org/porter` as a submodule. Label PRs here against this
repository's own components. `c: Setup`, `c: Apps`, `Root` and the `ROM:`/`api:` axes exist only in
the app repository and must not be used here.
