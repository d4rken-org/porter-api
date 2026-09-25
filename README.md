# Porter API

The Android SDK for [Porter](https://github.com/d4rken-org/porter), a minimal, maintained [Shizuku](https://github.com/RikkaApps/Shizuku) fork that gives apps ADB access, with optional root support. It builds on [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) and the maintenance work by [thedjchi and contributors](https://github.com/thedjchi/Shizuku-API). It keeps the Shizuku Binder protocol on the wire, so an app can add Porter support and keep its Shizuku support.

## Quick start

Add JitPack in `settings.gradle.kts`:

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

Then add the SDK, pinned to a version from the [releases page](https://github.com/d4rken-org/porter-api/releases). `sdk-extras` brings `sdk` and adds shell commands, `PorterSystemServices` and typed system property getters; depend on `sdk` alone if you need none of those.

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk-extras:<version>")
```

Wait for Porter, ask for access, run a command at its identity:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        Porter.connection.filterNotNull().collect { connection ->
            try {
                var state = connection.checkPermission()
                if (state is PermissionState.Denied && !state.permanentlyDenied) {
                    state = connection.requestPermission()
                }
                if (state is PermissionState.Granted) {
                    show(connection.exec("pm", "list", "packages", "-3").output)
                }
            } catch (e: PorterException) {
                // Porter refused or went away; the next connection arrives on the flow
            } catch (e: PorterShellException) {
                // The shell service stopped or could not run the command
            }
        }
    }
}
```

A shipping app also tells the user why no connection arrives, handles a permanent denial, and may run its own code or call system services as shell or root. The integration guide covers those.

## Documentation

- [Integration guide](https://porter.darken.eu/developers): adding Porter support, step by step, including Shizuku and multi-process apps.
- [API reference](docs/api-reference.md): what each call does exactly.
- [Setup guide](https://porter.darken.eu/setup): link your users here.
- [Releases](https://github.com/d4rken-org/porter-api/releases): versions and what changed.

## License

Upstream API code is [MIT](LICENSE) with RikkaW attribution; `shizuku-compat` is a Kotlin port of one upstream class under [the same license](shizuku-compat/LICENSE), with the class name and parcel layout unchanged. The Porter SDK (`protocol`, `sdk`, `sdk-extras`) is [Apache 2.0](sdk/LICENSE), with portions of `sdk` and `sdk-extras` derived from the MIT code.
