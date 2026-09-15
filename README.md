# Porter API

The Android SDK for [Porter](https://github.com/d4rken-org/porter), a minimal, maintained Shizuku fork that gives apps ADB access, with optional root support.

This repository contains the client SDK and the shared API source used by the Porter app. It builds on [Shizuku-API](https://github.com/thedjchi/Shizuku-API), preserving the Shizuku Java API and Binder protocol. Apps can support Porter directly without requiring Porter Compatibility, and can retain support for Shizuku.

## Add to your app

Requires Android 7.0 (API 24) or newer. Add JitPack in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroup("com.github.d4rken-org.porter-api") }
        }
    }
}
```

Then add:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk:0.2.0")
```

The SDK speaks Porter's own protocol and contains only `eu.darken.porter.*` classes. It does not include or conflict with the upstream `dev.rikka.shizuku` SDK, which an app can keep alongside it for original Shizuku support.

Follow the [integration guide](https://porter.darken.eu/developers) to request access from your app. Adding the dependency alone does not connect your app to Porter.

- [API method reference and upstream history](docs/api-reference.md)
- [User setup guide](https://porter.darken.eu/setup)

## License

Upstream API code is [MIT](LICENSE) with RikkaW attribution. The Porter SDK (`protocol`, `sdk`) is [Apache 2.0](sdk/LICENSE), with portions of `sdk` derived from the MIT code.
