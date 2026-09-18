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

The separate `shizuku-compat` artifact supplies the same `moe.shizuku.api.BinderContainer` class that `dev.rikka.shizuku:provider` ships. Two copies of that class name on one classpath do not dex, so the two artifacts are mutually exclusive, including when one of them arrives transitively through another library. An app that already uses the upstream provider must not add `shizuku-compat`. Adding it on its own does not give your app Shizuku connectivity either; that comes later.

The optional `sdk-extras` artifact carries two convenience classes that the SDK itself leaves out. `PorterSystemProperties` reads and writes system properties over a connected session, with typed getters. `PorterSystemServices` looks up a system service binder inside your own process, with no round trip to the server. It depends on `sdk` and brings it transitively, so an app adds this instead of both:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk-extras:0.2.0")
```

Two contract points differ from the upstream classes an app may be migrating from. The property getters declare no checked exception, where upstream declares `throws RemoteException`. `PorterSystemServices.getSystemService` answers null only when no service goes by the name it was given, and throws `IllegalStateException` when the lookup itself cannot be performed.

Follow the [integration guide](https://porter.darken.eu/developers) to request access from your app. Adding the dependency alone does not connect your app to Porter.

- [API method reference and upstream history](docs/api-reference.md)
- [User setup guide](https://porter.darken.eu/setup)

## License

Upstream API code is [MIT](LICENSE) with RikkaW attribution, and `shizuku-compat` ships it unchanged under [the same license](shizuku-compat/LICENSE). The Porter SDK (`protocol`, `sdk`, `sdk-extras`) is [Apache 2.0](sdk/LICENSE), with portions of `sdk` and `sdk-extras` derived from the MIT code.
