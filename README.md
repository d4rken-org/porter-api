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
implementation("com.github.d4rken-org.porter-api:client:0.1.0")
```

The client artifact includes the compatible API modules transitively. Remove upstream `dev.rikka.shizuku` SDK dependencies and any copied Porter adapter classes to avoid duplicate classes. Keep your existing `rikka.shizuku.*` imports.

Follow the [integration guide](https://porter.darken.eu/developers) to declare providers and permissions and select the backend. Adding the dependency alone does not connect your app to Porter.

- [API method reference and upstream history](docs/api-reference.md)
- [User setup guide](https://porter.darken.eu/setup)

## License

Upstream API code retains the [MIT license](LICENSE) and RikkaW attribution. The Porter client adapter uses [Apache 2.0](client/LICENSE); it originated in `d4rken-org/porter` at commit `2f94c05cdd3b367262c10cf5dc6685d7c304ecb7`.
