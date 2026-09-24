# API reference

For dependencies, providers and backend selection, follow the [Porter integration guide](https://porter.darken.eu/developers). This page is the Kotlin surface of the `sdk` artifact. The upstream Shizuku changelog below is kept for apps migrating from that SDK and retains the original API names.

### The connection

`Porter.connection` is a `StateFlow<PorterConnection?>`: null before a binder arrives and after it dies, a new `PorterConnection` whenever a new binder attaches. Between two live servers it goes from the old connection straight to the new one and never through null. A binder arrives again whenever the user restarts the manager while your app is running, so collect rather than read once.

```kotlin
lifecycleScope.launch {
    Porter.connection.collect { connection ->
        if (connection == null) showNotRunning() else onConnected(connection)
    }
}
```

`Porter.availability(context)` says how far away the manager is when nothing is connected: `NotInstalled`, `InstalledUnrecognized`, `InstalledNotConnected`, `Incompatible` or `Connected`. Every case but `NotInstalled` carries `packageName`, the app that declares the backend's permission, which is the app to show or launch; it is looked up by the permission, so a renamed fork or Shizuku+ is found too, and it does not prove that app served the binder. `Connected` and `Incompatible` name none when no app declares the permission any more, as when the manager was uninstalled while its server kept running. `InstalledNotConnected`, `InstalledUnrecognized` and `Connected` also carry the `backend`; `Incompatible` has it in its `incompatibility`. `Incompatible` means a service answered and the two sides share no protocol version; its `incompatibility` carries the version pair, on the scale of its `backend`, with `serverTooOld` (update the manager) and `clientTooOld` (update this app's SDK). Versions are cumulative, so a newer peer on either side is never a reason by itself.

Every call on a `PorterConnection` that reaches the server suspends and is safe on the main thread. It goes to the server the connection was attached to, whichever connection `Porter.connection` holds by then. A failed call throws a `PorterException`: `PorterSecurityException` when the server refused it, usually because this app has no grant, and `PorterRemoteException` when the Binder call failed, with a `DeadObjectException` cause once the server has died. Cancelling a call ends the wait at once, so a timeout around it works against a server that stopped answering; a call still queued is then never made, and one already sent still takes effect. A connection that was replaced while its server is still running keeps serving calls to that server; what ends with the replacement is `requestPermission()`, which fails with `PorterConnectionLostException`, and its user service flows, which complete.

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

### Differences of the privilege between ADB and ROOT

Porter can be started with ADB or ROOT, so the privilege could be either. `connection.uid` is `0` for ROOT and `2000` for ADB.

What ADB can do is significantly different from ROOT:

* In the Android world, the privilege is determined by Android permissions. See [AndroidManifest of Shell](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/packages/Shell/AndroidManifest.xml), all the permissions granted to Shell (ADB) are listed there. Be aware, the permissions change under different Android versions.

* In the Linux world, the privilege is determined by Shell's uid, capabilities, SELinux context, etc. For example, Shell (ADB) cannot access other apps' data files `/data/user/0/<package>`.

### Remote binder call

This is the simpler way, but what you can do is limited to Binder calls, so it suits simple applications. `connection.wrap(binder)` returns an `IBinder` whose every transaction is forwarded through the server. Those transactions block like any Binder call, so make them off the main thread. A refusal reaches the interface's own proxy as the platform exception it reads from the reply, such as `SecurityException`, not as a `PorterException`:

```kotlin
val binder = PorterSystemServices.getSystemService("package") ?: return
val pm = IPackageManager.Stub.asInterface(connection.wrap(binder))
```

### User service

A user service is like a [bound service](https://developer.android.com/guide/components/bound-services) that runs in a different process, as the identity (Linux UID) of root or shell. There are no restrictions on non-SDK APIs there. The process is not a valid Android application process: a `Context` obtained there cannot register receivers or reach a content resolver.

Be aware that, to let the service use the latest code, "Run/Debug configurations" - "Always install with package manager" in Android Studio should be checked.

* Start it: `connection.userService(args)` is a cold `Flow<IBinder>`. Collecting binds the service and starts it; the service binder is emitted once the server reports it connected, and the flow completes when the server reports the service died, or when the connection it was collected on is replaced or dies. A service the app keeps is bound again after either; the README shows one way. `UserServiceArgs` is to it what `Intent` is to a bound service:

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

* Stop it: cancelling the collection drops this collector, and when the last collector of the same service identity (`tag`, else class name) on the same connection goes, the server is asked to drop the binding. The process is **not** killed by that. A service ends when the app process that bound it dies, unless `UserServiceArgs.daemon` is true, and a daemon runs until it is stopped. Implement a "destroy" method under transaction code `UserServiceArgs.TRANSACTION_DESTROY` (`16777115`, or `16777114` in aidl) that cleans up and calls `System.exit()`. That transaction is how the server stops a service: `connection.stopUserService(args)` sends it, and Porter's server kills a host process still running three seconds after the service was removed.

* Per user: the identity is scoped to the calling Android user. A work profile's copy of the app is served by its own process, started with that profile's uid.

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
    withContext(Dispatchers.IO) { recording.destroy() }
}
```

The first call binds the service, and later calls on the same connection reuse that binding until the service or the connection dies. The binding follows the permission like any user service, so a call without a grant throws `PorterSecurityException`. A command that is not found exits with 127, as in a shell. A working directory that does not exist, output that cannot be read, or a service that stops answering throws `PorterShellException`; the `Process` methods that wait throw it too. An empty command throws `IllegalArgumentException`.

Cancelling `exec` returns at once and sends SIGKILL to the command's process group, which holds everything it started unless a process made a session of its own; on a device without `/system/bin/setsid`, SIGKILL reaches the command alone. `destroy()` does the same, and so does the death of the app process that started the command. A local `Process.destroy()` sends SIGTERM instead. Cancelling `startProcess` kills the command only while the start is pending: once it returns, the command is the caller's to stop. `waitFor()` ends with `InterruptedException` when its thread is interrupted, so `runInterruptible(Dispatchers.IO) { process.waitFor() }` lets a timeout end the wait, though not the command. SIGKILL gives nothing a chance to clean up, so stop a command that has to with `signal` and wait for it.

### The use of non-SDK interfaces

For "Remote binder call", as the APIs are accessed from the app's process, you may need [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) or another way to bypass restrictions on non-SDK interfaces.

[HiddenApiRefinePlugin](https://github.com/RikkaApps/HiddenApiRefinePlugin) helps with programming against hidden APIs conveniently.

## Upstream Shizuku-API history

Kept for apps migrating from `dev.rikka.shizuku:api`; the names below are that SDK's.

### 13.1.5

- Fix `ShizukuProvider#requestBinderForNonProviderProcess` crash on Android 14 (for apps targeting Android 14)

### 13.1.4

- Ask the server to remove `ShizukuServiceConnection` if the server is new enough

### 13.1.3

- Fix the problem that `Shizuku#unbindUserService(remove=false)` does not actually remove the callback

### 13.1.2

- Avoid the use of `CopyOnWriteArrayList#removeIf`, as using it with `coreLibraryDesugaring` enabled will crash on Android 8+

### 13.1.1

- Fix `Shizuku#removeXXXListener` will crash on Android 7.1 and earlier versions

  This is caused by `CopyOnWriteArrayList#removeIf` is not supported (throw an `UnsupportedOperationException`) before Android 8.0. Please note, using `coreLibraryDesugaring` will NOT fix this issue at least in version `2.0.3`.

- Prepare to remove `Shizuku#newProcess`, developers should have to use `UserService` instead

  First, this is already announced two years ago.

  For those who don't understand, `UserService` gives the developer the ability to run their own codes in a different process with root or shell privilege. This is much more powerful than just executing a command. `UserService` can replace `newProcess` in all cases.

  Also, `newProcess` uses texts to communicate , which is not efficient and unreliable. If there are apps that only uses `newProcess` to implement its functions, it loses most of the advantage of using Shizuku.

  Finally, `newProcess` lacks tty support, it is not possible to implement an interactive shell with it. And we already has `rish` that allows users to run an interactive shell with privilege in any terminal app they like.

### 13.1.0

- Breaking change: [desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) is required if min API of your app is 23
- Listeners now has an optional `Handler` parameter that determines which thread will the listener be called from

### 13.0.0

- The constructor of `UserService` can have a `Context` parameter which value is the `Context` used to create the instance of `UserService`

### 12.2.0

- Fix `onServiceDisconnected` is not called if the UserService is stopped by `Shizuku#unbindUserService`

### 12.1.0

- Automatically initialize Sui if you are using Shizuku

  You can opt-out this behavior by calling `ShizukuProvider#disableAutomaticSuiInitialization()` before `ShizukuProvider#onCreate()` is called

- Added a lot more detailed document for most APIs
- Drop pre-v11 support

  You don't need to worry about this problem, just show a "not supported" message if the user really uses pre-v11.

  - Sui was born after API v11, Sui users are not affected at all.
  - For Shizuku, according to Google Play statistics, more than 95% of users are on v11+. Shizuku drops Android 5 support from v5, many of the remaining 5% are such people who are stuck at super old versions.
  - A useful API, UserService, is added from v11 and stable on v12. I believe that many Shizuku apps already have a "version > 11" check.
  - I really want to drop pre-v11 support since [a possible system issue that may cause system soft reboot (system server crash) on uninstalling Shizuku](https://github.com/RikkaApps/Shizuku/issues/83).

### 12.0.0

- Add `Shizuku#peekUserService` that allows you to check if a specific user service is running
- Add `Shizuku.UserServiceArgs#daemon` that allows you to control if the user service should be run in the "Daemon mode"

## Migration guide for existing applications use Shizuku pre-v11
<details>
  <summary>Click to expand</summary>

### Changes

- Dependency changed (see Guide below)
- Self-implemented permission is used from v11, the API is the same to runtime permission (see the demo, and existing runtime permission still works)
- Package name was renamed to `rikka.shizuku` (replace all `moe.shizuku.api.` to `rikka.shizuku.`)
- `ShizukuService` class is renamed to `Shizuku`
- Methods in `Shizuku` class now throw `RuntimeException` on failure rather than `RemoteException` like other Android APIs
- Listeners are moved from `ShizukuProvider` class to `Shizuku` class

### Add support for Sui

- Call `Sui#init()`
- It's better to use check Sui with `Sui#isSui` before using Shizuku only methods in `ShizukuProvider`

</details>
