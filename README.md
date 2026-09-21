# Porter API

The Android SDK for [Porter](https://github.com/d4rken-org/porter), a minimal, maintained Shizuku fork that gives apps ADB access, with optional root support.

This repository contains the client SDK and the shared API source used by the Porter app. The SDK is Kotlin-first: one `StateFlow` for the connection, `suspend` for the permission prompt, a `Flow` for a user service. It builds on [Shizuku-API](https://github.com/thedjchi/Shizuku-API) and preserves the Shizuku Binder protocol on the wire, so apps can support Porter directly without requiring Porter Compatibility and can retain support for Shizuku.

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

The SDK is written for Kotlin callers and depends on `kotlinx-coroutines`; it makes no promises about use from Java. It speaks Porter's own protocol and contains only `eu.darken.porter.*` classes. It does not include or conflict with the upstream `dev.rikka.shizuku` SDK, which an app can keep alongside it for original Shizuku support.

The separate `shizuku-compat` artifact supplies the same `moe.shizuku.api.BinderContainer` class that `dev.rikka.shizuku:provider` ships. Two copies of that class name on one classpath do not dex, so the two artifacts are mutually exclusive, including when one of them arrives transitively through another library. An app that already uses the upstream provider must not add `shizuku-compat`. Adding it on its own does not connect your app to Shizuku either. The SDK declares nothing at the Shizuku authority, so an app that wants that backend also declares the `PorterShizukuApiProvider` block itself, with the `moe.shizuku.manager.permission.API_V23` permission and the `moe.shizuku.client.V3_SUPPORT` meta-data a server requires; the class documents the block. `shizuku-compat` is required for that backend, and where both managers are installed the SDK connects to Porter.

The optional `sdk-extras` artifact carries two conveniences that the SDK itself leaves out. Typed system property getters (`getSystemPropertyInt`, `getSystemPropertyLong`, `getSystemPropertyBoolean`) are extension functions on a connection and reach the server. `PorterSystemServices` looks up a system service binder inside your own process, with no round trip to the server. It depends on `sdk` and brings it transitively, so an app adds this instead of both:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk-extras:0.2.0")
```

`PorterSystemServices.getSystemService` answers null only when no service goes by the name it was given, and throws `IllegalStateException` when the lookup itself cannot be performed.

Follow the [integration guide](https://porter.darken.eu/developers) to request access from your app. Adding the dependency alone does not connect your app to Porter.

- [API reference and upstream history](docs/api-reference.md)
- [User setup guide](https://porter.darken.eu/setup)

## License

Upstream API code is [MIT](LICENSE) with RikkaW attribution; `shizuku-compat` is a Kotlin port of one upstream class under [the same license](shizuku-compat/LICENSE), with the class name and parcel layout unchanged. The Porter SDK (`protocol`, `sdk`, `sdk-extras`) is [Apache 2.0](sdk/LICENSE), with portions of `sdk` and `sdk-extras` derived from the MIT code.
