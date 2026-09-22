# Porter API

The Android SDK for [Porter](https://github.com/d4rken-org/porter), a minimal, maintained Shizuku fork that gives apps ADB access, with optional root support. It builds on [Shizuku-API](https://github.com/thedjchi/Shizuku-API) and keeps the Shizuku Binder protocol on the wire, so an app can add Porter support and keep its Shizuku support.

## Add to your app

Requires Android 7.0 (API 24) or newer. The SDK is coroutines and `Flow` throughout; use from Java is not supported.

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

Then in your module's `build.gradle.kts`:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk:+")
```

Pinning a version from the [releases page](https://github.com/d4rken-org/porter-api/releases) is recommended.

Nothing to add to your manifest: the SDK brings its own permission, package visibility entry and provider.

## Connect

`Porter.connection` is null until Porter delivers a Binder, and carries a new one whenever the user restarts Porter. Collect it rather than reading it once.

```kotlin
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Porter.connection.collect { connection ->
                    if (connection != null) onPorterReady(connection)
                }
            }
        }
    }
}
```

## Ask for permission

A connection is not access. `requestPermission()` shows Porter's dialog and suspends until the user answers.

```kotlin
suspend fun onPorterReady(connection: PorterConnection) {
    var state = connection.checkPermission()
    if (state is PermissionState.Denied && !state.permanentlyDenied) {
        state = try {
            connection.requestPermission()
        } catch (e: PorterConnectionLostException) {
            return // Porter restarted while the dialog was up; the next connection arrives on the flow
        }
    }
    if (state is PermissionState.Granted) doPrivilegedWork(connection)
}
```

`permanentlyDenied` is "deny and don't ask again"; asking again is refused without a prompt.

Every call that reaches Porter suspends and is safe on the main thread. A failed call throws a `PorterException`: `PorterSecurityException` when Porter refuses it, usually because access was not granted, and `PorterRemoteException` when the Binder call itself failed.

## Run your own code as shell or root

Porter runs a class of yours in its own process, at its own identity.

```aidl
// IMyService.aidl
interface IMyService {
    void destroy() = 16777114; // Porter sends this to stop the service
    String readFile(String path);
}
```

```kotlin
class MyService : IMyService.Stub() {
    override fun destroy() = exitProcess(0)
    override fun readFile(path: String): String = File(path).readText()
}
```

```kotlin
val args = UserServiceArgs(
    componentName = ComponentName(this, MyService::class.java),
    processNameSuffix = "service",
    tag = "my-service", // stable across obfuscation; the class name is used otherwise
    version = 1,        // bump when the service code changes
)

connection.userService(args).collect { binder ->
    val service = IMyService.Stub.asInterface(binder)
    service.readFile("/proc/net/tcp") // readable as shell, not from your app's own process
}
```

`connection.uid` is `2000` for ADB and `0` for root. The service stops when your app's process dies; set `daemon = true` to keep it running until you call `connection.stopUserService(args)`, which sends it `destroy`.

## Call a system service

`connection.wrap(binder)` re-issues every transaction on a system service Binder at Porter's identity. The lookup needs `sdk-extras`, which brings `sdk` with it:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk-extras:+")
```

```kotlin
val binder = PorterSystemServices.getSystemService("package") ?: return
val pm = IPackageManager.Stub.asInterface(connection.wrap(binder))
pm.getInstalledPackages(0, 0)
```

Platform AIDL like `IPackageManager` is not in the public SDK, so this route needs compile-time stubs and a way past the non-SDK interface restrictions. The user service above needs neither.

## Say why nothing happened

```kotlin
when (val availability = Porter.availability(this)) {
    PorterAvailability.Connected -> Unit
    PorterAvailability.InstalledNotConnected -> tell("Open Porter and start the service")
    PorterAvailability.NotInstalled -> tell("Install Porter")
    PorterAvailability.InstalledUnrecognized -> tell("Another app owns Porter's permission")
    is PorterAvailability.Incompatible -> if (availability.incompatibility.serverTooOld) {
        tell("Update Porter")
    } else {
        tell("This app needs an update to work with this Porter")
    }
}
```

Porter installed is not Porter running, so `InstalledNotConnected` is the normal state before the user starts it.

## More

- [Integration guide](https://porter.darken.eu/developers): the Shizuku backend, multi-process apps, versioning.
- [API reference](docs/api-reference.md): the full Kotlin surface.
- [User setup guide](https://porter.darken.eu/setup) to link your users to.

## License

Upstream API code is [MIT](LICENSE) with RikkaW attribution; `shizuku-compat` is a Kotlin port of one upstream class under [the same license](shizuku-compat/LICENSE), with the class name and parcel layout unchanged. The Porter SDK (`protocol`, `sdk`, `sdk-extras`) is [Apache 2.0](sdk/LICENSE), with portions of `sdk` and `sdk-extras` derived from the MIT code.
