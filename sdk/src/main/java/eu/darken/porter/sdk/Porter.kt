package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX
import androidx.annotation.VisibleForTesting
import eu.darken.porter.protocol.PorterProtocol
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app-facing entry point: the connection a server delivered to this process, if any. */
public object Porter {

    private const val TAG = "Porter"

    /**
     * Guards [current], [latest], [selection] and every connection's death link. Taken outside a
     * connection's permission lock. Never held across attach, which is the one call here that
     * blocks in the server.
     */
    internal val lock: Any = Any()

    /** The connection Porter answers for. Guarded by [lock]. */
    private var current: PorterConnection? = null

    /**
     * The newest connection that has not been given up on: [current], or the one attaching to
     * replace it. A connection that is no longer this one has been superseded and stays silent.
     */
    private var latest: PorterConnection? = null

    /** Never reset, so a connection of this process is never mistaken for a later one. */
    private var connections = 0

    /**
     * The wire this process takes a server delivery on. Kept out of [PorterBackend], which
     * [PorterServerInfo.backend] publishes and which describes a connection that exists.
     */
    internal enum class Selection {
        PORTER,
        SHIZUKU,

        /** Neither manager is reachable, so no delivery is taken at all. */
        NONE,
    }

    /** The answer a live connection was selected on, or the last one resolved. Guarded by [lock]. */
    private var selection: Selection? = null

    /** What a test pins the answer to. Guarded by [lock]. */
    private var selectionForTest: Selection? = null

    /**
     * The last delivered binder, where its attach was refused over versions, and why. Cleared by
     * the next delivery that publishes or drops a connection. Guarded by [lock].
     */
    private var rejected: RejectedDelivery? = null

    private class RejectedDelivery(val binder: IBinder, val why: PorterIncompatibility)

    private val _connection = MutableStateFlow<PorterConnection?>(null)

    /**
     * The connection this process holds: null before a binder arrives and after it dies, and a new
     * instance whenever a new binder attaches. A replacement is published only once its attach
     * completed, and the connection it replaces stays published until then, so a collector never
     * sees null between two live servers and never sees a connection that cannot answer.
     *
     * A binder arrives again whenever the user restarts the manager while the app is running.
     */
    public val connection: StateFlow<PorterConnection?> = _connection.asStateFlow()

    /**
     * What the SDK itself does once a death has been published, which is where the SDK's own
     * reaction to a death belongs: behind every collector that sees the null. Guarded by [lock].
     */
    private val postDeadHooks = ArrayList<Runnable>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Where every suspending call that blocks on a server runs. A test pins it; [resetForTest] restores it. */
    @Volatile
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val defaultDeliveryExecutor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "porter-delivery").apply { isDaemon = true }
    }

    /**
     * Runs the attaches this process starts on its own, one at a time: a fetch from the provider
     * process blocks for the attach, and what asks for one runs on the main thread. A test pins it;
     * [resetForTest] restores it.
     */
    @Volatile
    internal var deliveryExecutor: Executor = defaultDeliveryExecutor

    // --------------------- delivery ----------------------

    /** Announces a binder that speaks Porter's own wire, for a process that received it outside the provider. */
    @RestrictTo(LIBRARY_GROUP_PREFIX)
    public fun onBinderReceived(newBinder: IBinder?, packageName: String) {
        // No Context to select with, and nothing to select: this wire is Porter's by construction.
        onBinderReceived(newBinder, packageName, PorterBackend.PORTER, selected = null)
    }

    /**
     * A delivery from a server, which this process takes only on the backend it selected. Arrival
     * order therefore never decides which server an app talks to, and neither does a death.
     */
    internal fun onBinderReceived(context: Context, newBinder: IBinder?, packageName: String, backend: PorterBackend): Boolean {
        // Resolving asks the package manager, so it happens before the lock; the answer decides
        // nothing until it is checked inside, against the connection that is published then.
        val selected = if (newBinder == null) null else selectBackend(context)
        return onBinderReceived(newBinder, packageName, backend, selected)
    }

    /** As the delivery above, for a binder the caller already knows this process is entitled to take. */
    internal fun onBinderReceived(newBinder: IBinder?, packageName: String, backend: PorterBackend): Boolean =
        onBinderReceived(newBinder, packageName, backend, selected = null)

    /**
     * Blocks for the attach, so it runs on a binder thread or on [deliveryExecutor], never on the
     * main thread.
     *
     * @param selected the backend this process resolved for this delivery, or null where the caller
     * delivers a binder it already knows this process is entitled to take.
     * @return whether this call published [newBinder] as the connection
     */
    private fun onBinderReceived(newBinder: IBinder?, packageName: String, backend: PorterBackend, selected: Selection?): Boolean {
        if (newBinder == null) {
            dropCurrent()
            return false
        }

        val session: PorterConnection
        synchronized(lock) {
            // The same binder delivered twice is the same connection. Attaching again would ask the
            // server for a second grant for it, link a second death recipient, and supersede a
            // replacement that is attaching, so the published connection counts as well as latest.
            if (current?.binder === newBinder) return false
            if (latest?.binder === newBinder) return false

            // A live connection is the answer, whoever delivers and however the delivery got here:
            // no path replaces the server an app is talking to with one on the other backend.
            val published = current
            if (published != null && published.backend != backend) {
                Log.i(TAG, "ignoring a $backend binder, connected on ${published.backend}")
                return false
            }
            if (selected != null && selected != selectionOf(backend)) {
                Log.i(TAG, "ignoring a $backend binder, this process selected $selected")
                return false
            }

            session = PorterConnection(++connections, newBinder, backend)
            latest = session
            session.link()
        }

        try {
            val pushes = session.permissionPushes()
            val reply = session.wire.attach(packageName)
            val incompatibility = session.wire.incompatibility(reply)
            if (incompatibility != null) {
                // Nothing to publish: a connection whose server cannot be spoken to would answer
                // no call. The binder is remembered so availability can say why, and only until
                // the next delivery says something else.
                Log.w(TAG, "refusing binder ${session.generation}: $incompatibility")
                synchronized(lock) {
                    if (latest === session) rejected = RejectedDelivery(newBinder, incompatibility)
                }
                abandon(session)
                return false
            }
            session.apply(checkNotNull(reply), pushes)

            val superseded: Boolean
            var previous: PorterConnection? = null
            synchronized(lock) {
                superseded = latest !== session
                if (!superseded) {
                    rejected = null
                    // The connection that is handing over stays watched until this one takes over,
                    // and both steps happen under the one lock: a death callback never sees a
                    // connection nobody watches, and an unlink that throws publishes nothing.
                    previous = current?.takeIf { it !== session }
                    previous?.unlink()
                    current = session
                    // A published connection names this process's selection rather than only being
                    // constrained by it, so whatever was resolved before it published cannot leave
                    // the process answering for a backend it is not connected on.
                    selection = selectionOf(session.backend)
                    // Published under the lock: a death arriving between the two steps would
                    // otherwise publish a connection that had already been torn down.
                    _connection.value = session
                }
            }
            if (superseded) {
                // A newer binder arrived while this one was attaching, and owns the connection now.
                session.unlink()
                return false
            }
            // Outside the lock: giving up the previous connection can call its server.
            previous?.markLost()

            Log.i(TAG, "attached, connection ${session.generation}")
            return true
        } catch (e: RemoteException) {
            Log.w(TAG, Log.getStackTraceString(e))
            abandon(session)
        } catch (e: RuntimeException) {
            Log.w(TAG, Log.getStackTraceString(e))
            abandon(session)
        }
        return false
    }

    /** Gives up on [session] without disturbing the connection that has replaced it. */
    private fun abandon(session: PorterConnection) {
        synchronized(lock) {
            if (current === session) current = null
            // Fall back to the published connection rather than to nothing: a newcomer that failed
            // must not silence the one that is still serving calls.
            if (latest === session) latest = current
        }
        session.unlink()
    }

    private fun dropCurrent() {
        val dropped: PorterConnection?
        synchronized(lock) {
            dropped = current
            current = null
            // An attach still in flight is superseded too: the caller says there is no binder.
            latest = null
            rejected = null
            dropped?.unlink()
            if (dropped != null) _connection.value = null
        }
        if (dropped != null) {
            dropped.markLost()
            runPostDeadHooks()
        }
    }

    /**
     * The notification names no binder, so only the connection it was linked for may act on it.
     * One for a binder that has already been replaced tears nothing down.
     *
     * @return whether [session] was the published connection
     */
    internal fun connectionDied(session: PorterConnection): Boolean {
        val wasCurrent: Boolean
        synchronized(lock) {
            wasCurrent = current === session
            if (wasCurrent) {
                current = null
                _connection.value = null
            }
            // A connection that died while it was still attaching has nothing to publish any more,
            // and falls back to the connection that is serving, which may be one that outlives it.
            if (latest === session) latest = current
        }
        if (wasCurrent) runPostDeadHooks()
        return wasCurrent
    }

    /** Runs on the main thread after a death has been published. */
    internal fun addPostBinderDeadHook(hook: Runnable) {
        synchronized(lock) {
            postDeadHooks.add(hook)
        }
    }

    private fun runPostDeadHooks() {
        val hooks = synchronized(lock) { postDeadHooks.toList() }
        // On the main queue rather than on the dispatching stack: a collector on the main
        // dispatcher sees the death ahead of whatever the SDK does about it.
        for (hook in hooks) mainHandler.post(hook)
    }

    // --------------------- lookups ----------------------

    internal fun isCurrent(session: PorterConnection): Boolean = synchronized(lock) { current === session }

    /** The caller holds [lock]. */
    internal fun isCurrentOrLatest(session: PorterConnection): Boolean =
        current === session || latest === session

    /** The published binder, but only if that connection speaks [backend]. */
    internal fun binderFor(backend: PorterBackend): IBinder? {
        val session = synchronized(lock) { current } ?: return null
        if (session.backend != backend) return null
        return session.binder.takeIf { it.pingBinder() }
    }

    private fun pingCurrent(): Boolean = synchronized(lock) { current }?.binder?.pingBinder() == true

    /**
     * Whether the manager of the backend this process selected is installed, which is not whether
     * its service is running: only [PorterAvailability.Connected] and
     * [PorterAvailability.Incompatible] say a binder answered, and the second that its server and
     * this SDK share no protocol version; its [PorterIncompatibility] says which side has to move.
     *
     * The backend is Porter whenever a package declares Porter's permission, and Shizuku when one
     * declares Shizuku's and the optional `shizuku-compat` artifact is on the classpath. An app
     * without that artifact can receive no Shizuku binder at all, so a Shizuku-only device reads
     * [PorterAvailability.NotInstalled] rather than promising a connection it cannot make.
     *
     * [PorterAvailability.InstalledUnrecognized] means a package owns the selected backend's
     * permission and is not the manager this SDK knows. Do not name or launch it without your own
     * verification.
     *
     * The permission lookup behind this is not filtered by package visibility, so a manager is
     * found whatever package it is published under. One that owns the permission without being
     * the manager this SDK knows reads [PorterAvailability.InstalledUnrecognized], not
     * [PorterAvailability.NotInstalled].
     */
    public suspend fun availability(context: Context): PorterAvailability = withContext(ioDispatcher) {
        if (pingCurrent()) return@withContext PorterAvailability.Connected
        incompatibility()?.let { return@withContext PorterAvailability.Incompatible(it) }

        when (selectBackend(context)) {
            Selection.PORTER -> availabilityOf(context, PorterProtocol.PERMISSION, PorterProtocol.MANAGER_APPLICATION_ID)
            Selection.SHIZUKU -> availabilityOf(context, ShizukuProtocol.PERMISSION, ShizukuProtocol.MANAGER_APPLICATION_ID)
            Selection.NONE -> PorterAvailability.NotInstalled
        }
    }

    /**
     * Why the last delivered binder was refused, while no connection is held and that binder
     * still answers: the server it came from is running and cannot be spoken to. Null otherwise.
     */
    internal fun incompatibility(): PorterIncompatibility? {
        val refused = synchronized(lock) { if (current == null) rejected else null } ?: return null
        return refused.why.takeIf { refused.binder.pingBinder() }
    }

    private fun availabilityOf(context: Context, permission: String, manager: String): PorterAvailability {
        val owner = permissionOwner(context, permission) ?: return PorterAvailability.NotInstalled
        return if (manager == owner) PorterAvailability.InstalledNotConnected else PorterAvailability.InstalledUnrecognized
    }

    // --------------------- backend selection ----------------------

    /**
     * Which backend this process accepts a delivery on: Porter whenever any visible package claims
     * Porter's permission, otherwise Shizuku when one claims Shizuku's and the optional
     * `shizuku-compat` artifact is on the classpath, otherwise nothing at all.
     *
     * Held constant while a connection is live, and resolved again whenever there is none: a
     * running connection never changes backend, and a process that has none sees an install that
     * happened after it last asked.
     */
    internal fun selectBackend(context: Context): Selection {
        val pinned: Selection?
        synchronized(lock) {
            selectionForTest?.let { return it }
            pinned = if (current != null) selection else null
        }
        if (pinned != null) return pinned

        // Resolved outside the lock, because it asks the package manager, which is a binder call.
        val resolved = resolve(context)
        synchronized(lock) {
            // A connection that published while this was resolving keeps what it was selected on,
            // and names the answer itself where nothing was recorded: the resolved value lost the
            // race, and writing it would leave this process answering for the other backend.
            val published = current
            if (published != null) {
                if (selection == null) selection = selectionOf(published.backend)
                return selection!!
            }
            selection = resolved
            return resolved
        }
    }

    private fun resolve(context: Context): Selection {
        if (permissionOwner(context, PorterProtocol.PERMISSION) != null) return Selection.PORTER
        // Without the compat artifact no Shizuku binder can be unwrapped, so a Shizuku server this
        // app cannot receive from is not a backend to wait for.
        if (ShizukuCompat.isPresent() && permissionOwner(context, ShizukuProtocol.PERMISSION) != null) {
            return Selection.SHIZUKU
        }
        return Selection.NONE
    }

    /**
     * Takes [backend] as this process's selection, for a binder another process of this app
     * already selected and is using. Whoever asks next reads that instead of deciding again, unless
     * this process has a connection of its own, which answers for itself.
     */
    internal fun adoptBackend(backend: PorterBackend) {
        synchronized(lock) {
            // A connection of this process is already the answer; another process's is not a reason
            // to move the selection off the backend this one is connected on.
            if (current != null) return
            selection = selectionOf(backend)
        }
    }

    private fun selectionOf(backend: PorterBackend): Selection =
        if (backend == PorterBackend.SHIZUKU) Selection.SHIZUKU else Selection.PORTER

    /** The package declaring [permission], or null where no package this app can see does. */
    internal fun permissionOwner(context: Context, permission: String): String? = try {
        context.packageManager.getPermissionInfo(permission, 0).packageName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    // --------------------- tests ----------------------

    /** Pins the selection a test runs against; [resetForTest] clears it. */
    @VisibleForTesting
    internal fun selectBackendForTest(pinned: Selection?) {
        synchronized(lock) {
            selectionForTest = pinned
        }
    }

    /** The published connection, whether or not its binder still answers. */
    @VisibleForTesting
    internal fun currentForTest(): PorterConnection? = synchronized(lock) { current }

    /** Drops the connection and everything registered, so one test cannot see another's state. */
    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(lock) {
            current = null
            latest = null
            selection = null
            selectionForTest = null
            rejected = null
            postDeadHooks.clear()
            _connection.value = null
        }
        ioDispatcher = Dispatchers.IO
        deliveryExecutor = defaultDeliveryExecutor
        ShizukuCompat.setPresentForTest(null)
        PorterApiProvider.resetForTest()
    }
}
