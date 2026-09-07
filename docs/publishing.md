# Building and publishing

Use JDK 17 and Android SDK platform/build tools 36. The Gradle wrapper pins the Gradle version. The default build includes only the five published Java SDK modules and requires no NDK or CMake. Porter includes `rish` and `server-shared` directly in its own app build; the standalone upstream demos and native modules can be enabled with `-PincludeExtras=true` and the matching Android native toolchain.

## Local validation

```sh
./gradlew :client:testDebugUnitTest
./gradlew -Dmaven.repo.local=/tmp/porter-sdk-maven publishToMavenLocal
python3 tools/verify-publication.py /tmp/porter-sdk-maven 0.1.0
./gradlew -p tests/consumer -PsdkRepository=/tmp/porter-sdk-maven assembleDebug
```

The consumer is a separate build and resolves the published artifacts, not project dependencies. Its default SDK version is `0.1.0`; pass `-PsdkVersion=VERSION` to test another version. The publisher takes `-Pversion=VERSION`, then JitPack's `VERSION` environment variable, then the development default `0.1.0`.

## JitPack release

1. Publish this repository as `d4rken-org/porter-api`, preserving its upstream history. Publish the API before app commits whose submodule pins reference it.
2. Run local validation and commit the reviewed source. Create an immutable release tag such as `0.1.0`. Use the exact version shown in the SDK README and app integration guide, without a `v` prefix.
3. Push the commit and tag. Look up that exact tag on `https://jitpack.io/#d4rken-org/porter-api` and request its build before recommending the version to developers.
4. Confirm the build succeeds and exposes `client`, `api`, `provider`, `aidl` and `shared`. Check the POM and Gradle module metadata retain the Porter dependency coordinates and capabilities.
5. Build the consumer against `-PsdkRepository=https://jitpack.io -PsdkVersion=0.1.0` and verify it resolves no upstream Shizuku SDK artifacts.
6. Pin the reviewed API commit in Porter's submodule and update the version shown in the integration guide when necessary.

`jitpack.yml` invokes only release publication tasks. No signing keys, Central account or publishing credentials are needed for public JitPack builds. Do not use branch snapshots for released apps or move a published release tag. JitPack permits rebuilding public artifacts for the first seven days, so trigger and verify the build before distributing an SDK version.

The `dev.rikka.shizuku` capabilities in Gradle metadata make mixed upstream/Porter SDK graphs fail during resolution. They do not rename classes or automatically substitute dependencies. POM-only consumers must remove upstream artifacts themselves.

## Future Maven Central publication

The modules use ordinary `maven-publish` publications. Central would require namespace verification, signing, documentation artifacts and a publishing workflow. Keep the SDK's release version separate from `manifest.gradle` and the wire-protocol constants when adding another distribution channel.
