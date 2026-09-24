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

Every call that reaches Porter suspends and is safe on the main thread. A failed call throws a `PorterException`: `PorterSecurityException` when Porter refuses it, usually because access was not granted, and `PorterRemoteException` when the Binder call itself failed. Cancelling a call returns at once, so `withTimeout` works even against a server that stopped answering; a call already sent still takes effect.

## Run a shell command

`sdk-extras` runs commands at Porter's identity, shell or root. It brings `sdk` with it:

```kotlin
implementation("com.github.d4rken-org.porter-api:sdk-extras:+")
```

```kotlin
val result = connection.exec("sh", "-c", "pm list packages -3")
if (result.exitCode == 0) show(result.output) else log(result.errors)
```

The command runs in a user service that `sdk-extras` ships, started on the first call and shared by the calls after it, so there is nothing to declare. A command that is not found exits with 127, as in a shell; a service that cannot start one at all, or stops while it runs, throws `PorterShellException`.

A command that needs input, writes binary output or keeps running takes `startProcess`, which returns a `PorterShellProcess`, a `java.lang.Process` whose streams are pipes to the command:

```kotlin
val recording = connection.startProcess("screenrecord", "/sdcard/Movies/demo.mp4")
try {
    // ... later, once it is recording
    recording.signal(OsConstants.SIGINT) // screenrecord finishes the file on SIGINT
    withContext(Dispatchers.IO) { recording.waitFor() }
} finally {
    withContext(Dispatchers.IO) { recording.destroy() }
}
```

Cancelling `exec` kills the command and everything it started, so `withTimeout` bounds one that hangs. `startProcess` hands the command to you once it returns: stop it with `destroy()`, which sends SIGKILL the same way. A killed command cleans nothing up, so send a `signal` first to one that has to finish something, once it has started up; a signal it has no handler for yet ends it instead.

## Run your own code as shell or root

Porter runs a class of yours in its own process, at its own identity.

```aidl
// IMyService.aidl
interface IMyService {
    void destroy() = 16777114; // Porter sends this to stop the service
    String readFile(String path) = 1;
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

The flow completes when the service dies or Porter restarts. To keep one service for the whole app, bind again after either:

```kotlin
val myService: StateFlow<IMyService?> = Porter.connection
    .flatMapLatest { connection ->
        if (connection == null) return@flatMapLatest flowOf(null)
        connection.permission.flatMapLatest { permission ->
            if (permission !is PermissionState.Granted) return@flatMapLatest flowOf(null)
            flow {
                while (true) {
                    emitAll(connection.userService(args).map { IMyService.Stub.asInterface(it) })
                    emit(null) // the service died on a Porter that still runs
                    delay(1_000)
                }
            }
        }
    }
    .stateIn(appScope, SharingStarted.WhileSubscribed(30_000), null)
```

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
lifecycleScope.launch {
    when (val availability = Porter.availability(this@MainActivity)) {
        is PorterAvailability.Connected -> Unit
        is PorterAvailability.InstalledNotConnected -> offerToOpen(availability.packageName, "Start the service")
        PorterAvailability.NotInstalled -> tell("Install Porter")
        is PorterAvailability.InstalledUnrecognized -> tell("${availability.packageName} owns Porter's permission")
        is PorterAvailability.Incompatible -> if (availability.incompatibility.serverTooOld) {
            tell("Update Porter")
        } else {
            tell("This app needs an update to work with this Porter")
        }
    }
}
```

Porter installed is not Porter running, so `InstalledNotConnected` is the normal state before the user starts it. `packageName` is the app that declares the permission, found by the permission rather than by name, so a renamed Shizuku fork or Shizuku+ is found as well.

## More

- [Integration guide](https://porter.darken.eu/developers): the Shizuku backend, multi-process apps, versioning.
- [API reference](docs/api-reference.md): the full Kotlin surface.
- [User setup guide](https://porter.darken.eu/setup) to link your users to.

## License

Upstream API code is [MIT](LICENSE) with RikkaW attribution; `shizuku-compat` is a Kotlin port of one upstream class under [the same license](shizuku-compat/LICENSE), with the class name and parcel layout unchanged. The Porter SDK (`protocol`, `sdk`, `sdk-extras`) is [Apache 2.0](sdk/LICENSE), with portions of `sdk` and `sdk-extras` derived from the MIT code.
