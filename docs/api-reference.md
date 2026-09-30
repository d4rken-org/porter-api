# API reference

For dependencies, providers and backend selection, follow the [Porter integration guide](https://porter.darken.eu/developers). This page is the Kotlin surface of the `sdk`, `sdk-extras` and `shizuku-bridge` artifacts.

### The connection

`Porter.connection` is a `StateFlow<PorterConnection?>`: null before a binder arrives and after it dies, a new `PorterConnection` whenever a new binder attaches. Between two live servers it goes from the old connection straight to the new one and never through null. A binder arrives again whenever the user restarts the manager while your app is running, so collect rather than read once.

```kotlin
lifecycleScope.launch {
    Porter.connection.collect { connection ->
        if (connection == null) showNotRunning() else onConnected(connection)
    }
}
```

`Porter.state` is a `StateFlow<PorterConnectionState>` that also says when a delivered service was refused: `Connected` with the connection `Porter.connection` holds, `Incompatible` while a service refused for sharing no protocol version with this SDK is still running, or `Disconnected`. A serving connection wins over a refusal. A refusal lasts until a newer refusal replaces it, a connection is published, or the refused service stops; a later delivery that fails for another reason, or is ignored, leaves it in place. `Incompatible` carries the same `incompatibility` as `Porter.availability(context)`. Collect `state` rather than combining `Porter.connection` with something else, so each value is one consistent answer.

A refusal is not forwarded to the app's other processes: one that fetches its connection from the process that received the delivery stays `Disconnected` while that process holds only a refusal.

```kotlin
lifecycleScope.launch {
    Porter.state.collect { state ->
        when (state) {
            is PorterConnectionState.Connected -> onConnected(state.connection)
            is PorterConnectionState.Incompatible -> showIncompatible(state.incompatibility)
            PorterConnectionState.Disconnected -> showNotRunning()
        }
    }
}
```

`Porter.availability(context)` says how far away the manager is when nothing is connected: `NotInstalled`, `InstalledUnrecognized`, `InstalledNotConnected`, `Incompatible` or `Connected`. Every case but `NotInstalled` carries `packageName`, the app that declares the backend's permission, which is the app to show or launch; it is looked up by the permission, so a renamed fork or Shizuku+ is found too, and it does not prove that app served the binder. `Connected` and `Incompatible` name none when no app declares the permission any more, as when the manager was uninstalled while its server kept running. `InstalledNotConnected`, `InstalledUnrecognized` and `Connected` also carry the `backend`; `Incompatible` has it in its `incompatibility`. `Incompatible` means a service answered and the two sides share no protocol version; its `incompatibility` carries the version pair, on the scale of its `backend`, with `serverTooOld` (update the manager) and `clientTooOld` (update this app's SDK). Versions are cumulative, so a newer peer on either side is never a reason by itself.

Every call on a `PorterConnection` that reaches the server suspends and is safe on the main thread. It goes to the server the connection was attached to, whichever connection `Porter.connection` holds by then. A failed call throws a `PorterException`: `PorterSecurityException` when the server refused it, usually because this app has no grant, and `PorterRemoteException` when the Binder call failed, with a `DeadObjectException` cause once the server has died. Other exceptions reported by the server, apart from `SecurityException`, also produce `PorterRemoteException`, with the reported exception as the cause. Cancelling a call ends the wait at once, so a timeout around it works against a server that stopped answering; a call still queued is then never made, and one already sent still takes effect. A connection that was replaced while its server is still running keeps serving calls to that server; what ends with the replacement is `requestPermission()`, which fails with `PorterConnectionLostException`, and its user service flows, which complete.

`connection.uid` is `2000` when Porter runs through ADB and `0` when it runs as root.

### Request permission

`connection.permission` is a `StateFlow<PermissionState>`, `Granted` or `Denied(permanentlyDenied)`, holding what the attach reply said and every state the server pushed since. `checkPermission()` answers a held grant from that state and asks the server only about a denial, putting the answer there, so a revocation arrives when the server pushes it. A Shizuku server that never re-sends its attach state leaves a grant in place until its binder dies. `requestPermission()` asks the manager to prompt the user and suspends until the user answers:

```kotlin
suspend fun ensureAccess(connection: PorterConnection): Boolean {
    when (val state = connection.checkPermission()) {
        PermissionState.Granted -> return true
        is PermissionState.Denied -> if (state.permanentlyDenied) {
            // The user chose "deny and don't ask again"; a request would be refused silently.
            return false
        }
    }
    return connection.requestPermission() is PermissionState.Granted
}
```

A prompt already shown is not withdrawn by cancelling the call; its late answer is dropped. If the connection is replaced or dies before the user answers, the call fails with `PorterConnectionLostException`.

### Remote binder call

`connection.wrap(binder)` returns an `IBinder` whose every transaction is forwarded through the server. Those transactions block like any Binder call, so make them off the main thread. A refusal reaches the interface's own proxy as the platform exception it reads from the reply, such as `SecurityException`, not as a `PorterException`:

```kotlin
val binder = PorterSystemServices.getSystemService("package") ?: return
val pm = IPackageManager.Stub.asInterface(connection.wrap(binder))
```

Platform interfaces such as `IPackageManager` are not in the public SDK, and these calls are made from the app's process: [HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin) helps compile against them, and [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) gets past the non-SDK interface restrictions at runtime.

### User service

A user service is like a [bound service](https://developer.android.com/guide/components/bound-services) that runs in a different process, as the identity (Linux UID) of root or shell. There are no restrictions on non-SDK APIs there. The process is not a valid Android application process: a `Context` obtained there cannot register receivers or reach a content resolver.

Be aware that, to let the service use the latest code, "Run/Debug configurations" - "Always install with package manager" in Android Studio should be checked.

* Start it: `connection.userService(args)` is a cold `Flow<IBinder>`. Collecting binds the service and starts it; the service binder is emitted once the server reports it connected, and the flow completes when the server reports the service died, or when the connection it was collected on is replaced or dies. A service the app keeps is bound again after either; the [integration guide](https://porter.darken.eu/developers#your-own-service-at-porters-identity) shows one way. `UserServiceArgs` is to it what `Intent` is to a bound service:

  ```kotlin
  val args = UserServiceArgs(
      componentName = ComponentName(this, MyService::class.java),
      processNameSuffix = "service",
      tag = "my-service",   // stable across obfuscation; the class name is used otherwise
      version = 1,          // bump when the service code changes, so the server recreates it
  )
  scope.launch {
      connection.userService(args).collect { binder ->
          val service = IMyService.Stub.asInterface(binder)
          // ...
      }
  }
  ```

  The service class must implement `IBinder`; the usual shape is `class MyService : IMyService.Stub()`. It must be public, with a public no-argument constructor or a public one taking a `Context`; the `Context` one is tried first. The SDK's consumer R8 rules keep both on every `IBinder` class that survives shrinking, which a `MyService::class.java` reference in the app's code ensures; a class named only in a string needs its own `-keep` rule. `userService(args, start = false)` binds only if the service is already running and completes without emitting otherwise. `peekUserService(args)` answers the running service's version code, or null, without binding.

* Stop it: cancelling the collection drops this collector, and when the last collector of the same service identity (`tag`, else class name) on the same connection goes, the server is asked to drop the binding. The process is **not** killed by that. A service that is not a daemon ends when the last app process still collecting it dies. Once every collection is cancelled, nothing ties it to the app's process: it runs until it is stopped, until the app's permission is revoked, until the app is uninstalled from every user, or until Porter stops, and collecting it again ties it to the collecting process again. A Shizuku server below 13.4 is never asked to drop the binding, so there it still ends with the last process that collected it. A daemon (`UserServiceArgs.daemon`) is not tied to the app's process at all; the other ends above apply to it too. Implement a "destroy" method under transaction code `UserServiceArgs.TRANSACTION_DESTROY` (`16777115`, or `16777114` in aidl) that cleans up and calls `System.exit()`. That transaction is how the server stops a service: `connection.stopUserService(args)` sends it, and Porter's server kills a host process still running three seconds after the service was removed.

* Per user: the identity is scoped to the calling Android user. A work profile's copy of the app is served by its own process, which runs as the server's uid (`connection.uid`) like every other user service, not as that profile's uid.

### Shell commands

`sdk-extras` runs a command at the server's identity through a user service it ships, `com.example:porter_shell`, on either backend. `connection.exec(vararg command, dir)` returns a `PorterShellResult` with `exitCode`, `output` and `errors`, the last two read as UTF-8, once the command exits; the command's input is closed. `connection.startProcess(vararg command, dir)` returns a `PorterShellProcess`, a `java.lang.Process` whose streams are pipes to the running command, for input, binary output or a command that runs until it is stopped. Read its output as it arrives, or the command blocks once a pipe is full. Besides the `Process` methods it has `pid`, null where the service could not read it, and `signal(signal)`, which sends an `OsConstants.SIG*` value to the command alone, suspends, and is safe on the main thread. `signal` leaves a command that already exited alone, throws `IllegalArgumentException` for a value that is not a signal, and throws `PorterShellException` where `pid` is null or the signal cannot be delivered. A command that has not installed its handler yet dies from the signal instead of handling it, so send it once the command is running.

```kotlin
val result = connection.exec("sh", "-c", "ls -l /data/local/tmp")

// Output as it comes
val logcat = connection.startProcess("logcat", "-v", "brief")
withContext(Dispatchers.IO) {
    try {
        logcat.inputStream.bufferedReader().useLines { lines -> lines.take(100).forEach(::show) }
    } finally {
        logcat.destroy()
    }
}

// Input
val writer = connection.startProcess("sh", "-c", "cat > /data/local/tmp/main.obb")
withContext(Dispatchers.IO) {
    try {
        writer.outputStream.use { source.copyTo(it) }
        check(writer.waitFor() == 0)
    } finally {
        writer.destroy()
    }
}

// A command that has to finish something
val recording = connection.startProcess("screenrecord", "/sdcard/Movies/demo.mp4")
try {
    // ... later, once it is recording
    recording.signal(OsConstants.SIGINT)
    withContext(Dispatchers.IO) { recording.waitFor() }
} finally {
    withContext(NonCancellable + Dispatchers.IO) { recording.destroy() }
}
```

The first call binds the service, and later calls on the same connection reuse that binding until the service or the connection dies. Binding starts the service's process, so the first call, and the first after the service died, also waits for that start; an app that cannot keep a thread waiting that long can run a short command such as `exec("true")` earlier, elsewhere. The binding follows the permission like any user service, so a call without a grant throws `PorterSecurityException`. A command that is not found exits with 127, as in a shell. A working directory that does not exist, output that cannot be read, or a service that stops answering throws `PorterShellException`; the `Process` methods that wait throw it too. An empty command throws `IllegalArgumentException`.

Cancelling `exec` returns at once and sends SIGKILL to the command's process group, which holds everything it started unless a process made a session of its own; on a device without `/system/bin/setsid`, SIGKILL reaches the command alone. `destroy()` does the same, and so does the death of the app process that started the command. All three apply only while the command runs: once it has exited, none of them reaches what it left running in its group, because the SDK cannot tell that group from a later one that got the same id. Stop those yourself, or have the command wait for them. A local `Process.destroy()` sends SIGTERM instead. Cancelling `startProcess` kills the command only while the start is pending: once it returns, the command is the caller's to stop. `waitFor()` ends with `InterruptedException` when its thread is interrupted, so `runInterruptible(Dispatchers.IO) { process.waitFor() }` lets a timeout end the wait, though not the command. SIGKILL gives nothing a chance to clean up, so stop a command that has to with `signal` and wait for it.

### Shizuku-API bridge

`shizuku-bridge` lets code written against upstream's `dev.rikka.shizuku:api`, including libraries built on it, run on Porter unchanged. It brings `sdk`, `sdk-extras` and `dev.rikka.shizuku:api`. Call `PorterShizukuBridge.start(scope)` once per process, with a scope that lives as long as the process, for example from `Application.onCreate`. From then on every Porter connection the process receives is handed to `rikka.shizuku.Shizuku` as its server binder, and a connection that dies or is replaced reaches it as that binder's death. Calling `start` again while its scope runs does nothing; after the scope ended, it starts again.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PorterShizukuBridge.start(appScope)
    }
}
```

A Porter connection takes the place of a Shizuku server's binder that `rikka.shizuku.Shizuku` already holds, and nothing is handed back to it when Porter goes away: until Porter or the app restarts, or upstream receives another server's binder, the app has no server. When upstream reports a Shizuku server's attach reply while a Porter connection lives, the bridge asynchronously hands upstream the connection again, or its attach reply where upstream still holds it. Until then upstream uses that server, or that server's attach reply with the connection's binder. A server that never replies does not set this off. A server older than Shizuku API 11 switches upstream to its old parcel layout for good; from then on the bridge no longer hands upstream a connection. While upstream uses an older layout, a `ShizukuBinderWrapper` transaction that reaches the bridge throws `IllegalStateException`; the connection's other calls keep working. Reconciliation supports servers of Shizuku API 13: with an older server running next to Porter, a `ShizukuBinderWrapper` transaction built while upstream held that server's reply and delivered just after the bridge restored its own can be misread. An app that keeps upstream's `ShizukuProvider` still reaches a Shizuku server on a device without Porter. An app that wants the Shizuku server instead does not call `start`.

What the bridge answers:

- The attach reply, `getVersion()` (13), `getUid()`, `getSELinuxContext()`, `checkSelfPermission()` and `shouldShowRequestPermissionRationale()`, from the connection.
- `requestPermission(code)`, through Porter's dialog; the result reaches `OnRequestPermissionResultListener` with the same code.
- `ShizukuSystemProperties`, `checkRemotePermission`, and every `ShizukuBinderWrapper` transaction, which Porter forwards at its identity.
- `newProcess`, through the shell service `sdk-extras` ships. A command that is not found exits with 127 instead of failing to start. Closing the process's output stream ends the command's input. Upstream keeps every process it hands out, and until `destroy()` a process keeps stderr open, stdout until the app first asks for it, and stdin until the app asks for it or sees the command exit through `waitFor`, `exitValue` or `alive`. Streams the app got stay open until it closes them, also after `destroy()`: close them, then call `destroy()`.
- An environment array for `newProcess` replaces the inherited environment, as with `Runtime.exec`. Entries that are not `NAME=VALUE`, or that start with `-`, are dropped. With an environment, a command whose first element starts with `-` or contains `=` is refused with `IllegalArgumentException`.

User services (`bindUserService`, `peekUserService`, `unbindUserService`) and the manager-only calls throw `UnsupportedOperationException`.

A call that asks Porter blocks its caller until Porter answers, as it does against a Shizuku server. What upstream cached from the attach reply is answered without asking, and `requestPermission` returns at once and answers through the listener. The first `newProcess`, and the first after the shell service died, also waits for that service's process to start. A refused call throws `SecurityException`, while a refused `requestPermission` arrives as a denied result. A call to a server that died throws `DeadObjectException`, and a server's own failure arrives as the exception it threw. Upstream's `Shizuku` methods that do not declare `RemoteException` wrap a `DeadObjectException` in a `RuntimeException`, as they do with a Shizuku server. In an app with several processes, each process calls `start` and `PorterApiProvider.requestBinderForNonProviderProcess(context)`; leave `ShizukuProvider.enableMultiProcessSupport` off.

The bridge's service binder answers with this app's grant, so it serves only its own process. A transaction that reaches it from another process, which upstream's `ShizukuProvider` can hand the binder to, throws `SecurityException`. That includes a `ShizukuBinderWrapper` transaction the app makes while it is itself handling a binder call from another process. After checking that caller, make it with the calling identity cleared:

```kotlin
val token = Binder.clearCallingIdentity()
try {
    wrapped.transact(code, data, reply, 0)
} finally {
    Binder.restoreCallingIdentity(token)
}
```

A `ShizukuRemoteProcess` is `Parcelable`: sending it to another process gives that process access to the command. A running command stays tied to the app process that started it.
