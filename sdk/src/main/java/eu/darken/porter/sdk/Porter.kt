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
import java.util.EnumMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app-facing entry point: the connection a server delivered to this process, if any. */
public object Porter {

    private const val TAG = "Porter"

    /**
     * Guards [current], [latest], [selection], [kept], [rejected] and every death link. Taken
     * outside a connection's permission lock. Never held across attach, which is the one call here
     * that blocks in the server.
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
     * the next delivery that publishes or drops a connection, and by that binder's death. Guarded
     * by [lock]; set only through [setRejected].
     */
    private var rejected: RejectedDelivery? = null

    private class RejectedDelivery(val binder: IBinder, val why: PorterIncompatibility) {
        val recipient = IBinder.DeathRecipient { Porter.rejectedDied(this) }
    }

    /**
     * Per backend, the binder a delivery on it brought while this process was connected on, or had
     * selected, the other one. A server delivers once per process, so this is the only way back to
     * that server once the connection that refused it is lost. Guarded by [lock].
     */
    private val kept = EnumMap<PorterBackend, KeptBinder>(PorterBackend::class.java)

    private class KeptBinder(
        val backend: PorterBackend,
        val binder: IBinder,
        val context: Context,
        val packageName: String,
    ) {
        val recipient = IBinder.DeathRecipient { Porter.keptBinderDied(this) }
    }

    private val _connection = MutableStateFlow<PorterConnection?>(null)

    /**
     * The connection this process holds: null before a binder arrives and after it dies, and a new
     * instance whenever a new binder attaches. A replacement is published only once its attach
     * completed, and the connection it replaces stays published until then, so a collector never
     * sees null between two live servers and never sees a connection that cannot answer.
     *
     * A binder arrives again whenever the user restarts the manager while the app is running. When
     * the connection's server dies, the backend is selected again. If that is the other backend,
     * whose server delivered a binder while this connection was selected and still runs, that
     * binder is taken; otherwise the process waits for the next delivery.
     */
    public val connection: StateFlow<PorterConnection?> = _connection.asStateFlow()

    private val _state = MutableStateFlow<PorterConnectionState>(PorterConnectionState.Disconnected)

    /**
     * What this process holds: [PorterConnectionState.Connected] with the connection [connection]
     * publishes, [PorterConnectionState.Incompatible] while a delivery refused over versions came
     * from a server that still runs, [PorterConnectionState.Disconnected] otherwise. A connection
     * that serves wins over a refusal. A refusal lasts until a newer refusal replaces it, a
     * connection is published, or the refused server stops; a later delivery that fails for another
     * reason, or is ignored, leaves it in place. Each value is one snapshot, so collect this instead
     * of combining [connection] with something else.
     *
     * A refusal is not forwarded to the app's other processes: one that fetches its connection from
     * this process stays [PorterConnectionState.Disconnected] while this one holds only a refusal.
     */
    public val state: StateFlow<PorterConnectionState> = _state.asStateFlow()

    /**
     * What the SDK itself does once a death has been published, which is where the SDK's own
     * reaction to a death belongs: behind every collector that sees the null. Guarded by [lock].
     */
    private val postDeadHooks = ArrayList<Runnable>()

    /** Lazy, so that reading [connection] needs no main looper, as in a plain JVM test. */
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The SDK's own threads, at most 16: a server that stops answering holds these, never the
     * threads the app's own IO work runs on. An executor rather than a view of [Dispatchers.IO],
     * because porsh runs this SDK on the older kotlinx-coroutines its loader bundles; the porsh case
     * of the Porter app's emulator smoke test is what exercises that.
     */
    private val defaultIoDispatcher: CoroutineDispatcher =
        ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, "porter-server-call").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }.asCoroutineDispatcher()

    /** Where every call that blocks on a server runs. A test pins it; [resetForTest] restores it. */
    @Volatile
    internal var ioDispatcher: CoroutineDispatcher = defaultIoDispatcher

    /** Outlives every caller, so a call its caller stopped waiting for still runs to its end. */
    private val detachedCalls = CoroutineScope(SupervisorJob())

    /**
     * Runs [block], which blocks on a server, on [ioDispatcher] and outside the caller's job.
     * Cancelling the caller ends the wait at once. A call still queued then never runs; one already
     * running cannot be interrupted, runs to its end, and what it does on the server still happens.
     */
    internal suspend fun <T> serverCall(block: () -> T): T {
        val call = detachedCall(block)
        try {
            return call.await()
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    /** As [serverCall], for a caller that waits on the result itself or not at all. */
    internal fun <T> detachedCall(block: () -> T): Deferred<T> = detachedCalls.async(ioDispatcher) { block() }

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

    private val defaultUserServiceExecutor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "porter-user-service").apply { isDaemon = true }
    }

    /**
     * Hands user service events to their collectors one at a time, in the order they were queued,
     * on a thread of the SDK's own: a main thread blocked on a call that waits for a bind cannot
     * hold that bind up. A test pins it; [resetForTest] restores it.
     */
    @Volatile
    internal var userServiceExecutor: Executor = defaultUserServiceExecutor

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
        return onBinderReceived(newBinder, packageName, backend, selected, context)
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
     * @param context where a server delivered [newBinder], which keeps it if it is refused for the
     * other backend; null where nothing is kept.
     * @param adopting whether [newBinder] is a kept one, taken only while nothing else is.
     * @return whether this call published [newBinder] as the connection
     */
    private fun onBinderReceived(
        newBinder: IBinder?,
        packageName: String,
        backend: PorterBackend,
        selected: Selection?,
        context: Context? = null,
        adopting: Boolean = false,
    ): Boolean {
        if (newBinder == null) {
            dropCurrent()
            return false
        }

        val session: PorterConnection
        synchronized(lock) {
            // A kept binder stands in for a delivery that never came, so one that did come first,
            // published or still attaching, is the answer.
            if (adopting && (current != null || latest != null)) return false

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
                // Kept in the same step as the refusal, so the death of the published connection
                // cannot come between them and find nothing to take.
                if (context != null) keep(backend, newBinder, context, packageName)
                return false
            }
            if (selected != null && selected != selectionOf(backend)) {
                Log.i(TAG, "ignoring a $backend binder, this process selected $selected")
                // Selecting nothing leaves no connection to lose, and losing one is when a kept
                // binder is taken.
                if (context != null && selected != Selection.NONE) keep(backend, newBinder, context, packageName)
                return false
            }

            if (adopting) Log.i(TAG, "adopting a kept $backend binder")
            session = PorterConnection(++connections, newBinder, backend, packageName)
            latest = session
            // The connection watches this binder from here on, and one watch is all it gets.
            releaseKept(backend, newBinder)
            session.link()
        }

        try {
            val pushes = session.permissionPushes()
            val reply = session.wire.attach(packageName)
            val incompatibility = session.wire.incompatibility(reply)
            if (incompatibility != null) {
                // Nothing to publish as a connection: one whose server cannot be spoken to would
                // answer no call. The binder is remembered so state and availability can say why,
                // until the next delivery says something else or the server stops.
                Log.w(TAG, "refusing binder ${session.generation}: $incompatibility")
                synchronized(lock) {
                    if (latest === session) {
                        setRejected(RejectedDelivery(newBinder, incompatibility))
                        publishState()
                    }
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
                    setRejected(null)
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
                    publishState()
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
            setRejected(null)
            dropped?.unlink()
            publishState()
        }
        if (dropped != null) {
            dropped.markLost()
            runPostDeadHooks()
            adoptAfterLoss()
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
                publishState()
            }
            // A connection that died while it was still attaching has nothing to publish any more,
            // and falls back to the connection that is serving, which may be one that outlives it.
            if (latest === session) latest = current
        }
        if (wasCurrent) runPostDeadHooks()
        return wasCurrent
    }

    /** The caller holds [lock]. Publishes [current] and [rejected], a serving connection first. */
    private fun publishState() {
        _connection.value = current
        // Read after the write above: a collector it resumed inline may have published again.
        val published = current
        val refused = rejected
        _state.value = when {
            published != null -> PorterConnectionState.Connected(published)
            refused != null -> PorterConnectionState.Incompatible(refused.why)
            else -> PorterConnectionState.Disconnected
        }
    }

    /** The caller holds [lock]. A binder that is already dead is not remembered. */
    private fun setRejected(record: RejectedDelivery?) {
        rejected?.let { it.binder.unlinkToDeath(it.recipient, 0) }
        rejected = null
        if (record == null) return
        try {
            record.binder.linkToDeath(record.recipient, 0)
        } catch (e: RemoteException) {
            return
        }
        rejected = record
    }

    private fun rejectedDied(record: RejectedDelivery) {
        synchronized(lock) {
            if (rejected !== record) return
            setRejected(null)
            publishState()
        }
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

    // --------------------- kept binders ----------------------

    /** The caller holds [lock]. A binder that is already dead is not kept. */
    private fun keep(backend: PorterBackend, binder: IBinder, context: Context, packageName: String) {
        if (kept[backend]?.binder === binder) return
        val record = KeptBinder(backend, binder, context.applicationContext ?: context, packageName)
        try {
            binder.linkToDeath(record.recipient, 0)
        } catch (e: RemoteException) {
            return
        }
        kept.put(backend, record)?.let { it.binder.unlinkToDeath(it.recipient, 0) }
        Log.i(TAG, "keeping a $backend binder")
    }

    /** The caller holds [lock]. */
    private fun releaseKept(backend: PorterBackend, binder: IBinder) {
        val record = kept[backend]?.takeIf { it.binder === binder } ?: return
        kept.remove(backend)
        record.binder.unlinkToDeath(record.recipient, 0)
    }

    private fun keptBinderDied(record: KeptBinder) {
        synchronized(lock) {
            if (kept[record.backend] !== record) return
            kept.remove(record.backend)
            record.binder.unlinkToDeath(record.recipient, 0)
        }
    }

    /**
     * After a published connection was lost and marked so, takes the binder kept for whichever
     * backend this process selects now. Queued behind the post-death hooks, so the app sees the
     * death first, and attached off the main thread.
     */
    internal fun adoptAfterLoss() {
        if (synchronized(lock) { kept.isEmpty() }) return
        mainHandler.post { deliveryExecutor.execute { adoptKept() } }
    }

    private fun adoptKept() {
        val context = synchronized(lock) { kept.values.firstOrNull()?.context } ?: return
        val selected = selectBackend(context)
        val backend = when (selected) {
            Selection.PORTER -> PorterBackend.PORTER
            Selection.SHIZUKU -> PorterBackend.SHIZUKU
            Selection.NONE -> return
        }
        val record = synchronized(lock) { kept[backend] } ?: return
        if (!record.binder.pingBinder()) return
        if (onBinderReceived(record.binder, record.packageName, backend, selected, adopting = true)) {
            PorterApiProvider.announceBinder(record.context)
        }
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

    /**
     * Whether the manager of the backend this process selected is installed, which is not whether
     * its service is running: only [PorterAvailability.Connected] and
     * [PorterAvailability.Incompatible] say a binder answered, and the second that its server and
     * this SDK share no protocol version; its [PorterIncompatibility] says which side has to move.
     *
     * The backend is Porter whenever a package declares Porter's permission, and Shizuku when one
     * declares Shizuku's, or Shizuku+'s own, and the optional `shizuku-compat` artifact is on the
     * classpath. An app without that artifact can receive no Shizuku binder at all, so a
     * Shizuku-only device reads [PorterAvailability.NotInstalled] rather than promising a connection
     * it cannot make.
     *
     * [PorterAvailability.InstalledUnrecognized] means a package owns the selected backend's
     * permission and is not the manager this SDK knows for it.
     */
    public suspend fun availability(context: Context): PorterAvailability = serverCall {
        val live = synchronized(lock) { current }?.takeIf { it.binder.pingBinder() }
        if (live != null) return@serverCall PorterAvailability.Connected(live.backend, owner(context, live.backend)?.packageName)
        incompatibility()?.let { return@serverCall PorterAvailability.Incompatible(it, owner(context, it.backend)?.packageName) }

        val backend = when (selectBackend(context)) {
            Selection.PORTER -> PorterBackend.PORTER
            Selection.SHIZUKU -> PorterBackend.SHIZUKU
            Selection.NONE -> return@serverCall PorterAvailability.NotInstalled
        }
        val owner = owner(context, backend) ?: return@serverCall PorterAvailability.NotInstalled
        if (owner.recognized) {
            PorterAvailability.InstalledNotConnected(backend, owner.packageName)
        } else {
            PorterAvailability.InstalledUnrecognized(backend, owner.packageName)
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

    /** A package declaring a backend's permission, and whether it is the manager this SDK knows for that permission. */
    private class Owner(val packageName: String, val recognized: Boolean)

    /**
     * The first package declaring one of [backend]'s permissions. The stock Shizuku permission is
     * asked first, so a device carrying both it and Shizuku+'s answers with the stock owner.
     */
    private fun owner(context: Context, backend: PorterBackend): Owner? {
        val managers = when (backend) {
            PorterBackend.PORTER -> listOf(PorterProtocol.PERMISSION to PorterProtocol.MANAGER_APPLICATION_ID)
            PorterBackend.SHIZUKU -> listOf(
                ShizukuProtocol.PERMISSION to ShizukuProtocol.MANAGER_APPLICATION_ID,
                ShizukuProtocol.PLUS_PERMISSION to ShizukuProtocol.PLUS_MANAGER_APPLICATION_ID,
            )
        }
        return managers.firstNotNullOfOrNull { (permission, manager) ->
            permissionOwner(context, permission)?.let { Owner(it, recognized = it == manager) }
        }
    }

    // --------------------- backend selection ----------------------

    /**
     * Which backend this process accepts a delivery on: Porter whenever any visible package claims
     * Porter's permission, otherwise Shizuku when one claims Shizuku's or Shizuku+'s and the
     * optional `shizuku-compat` artifact is on the classpath, otherwise nothing at all.
     *
     * Held constant while a connection is live, and resolved again whenever there is none: a
     * running connection never changes backend, and a process that has none sees an install that
     * happened after it last asked. Once a connection's server dies, a binder the other backend
     * delivered while that connection was selected is taken if this resolves to that backend and
     * the binder's server still runs; otherwise the process waits for the next delivery.
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
        if (owner(context, PorterBackend.PORTER) != null) return Selection.PORTER
        // A Shizuku server this app cannot receive from is not a backend to wait for.
        if (ShizukuCompat.canReceive(context) && owner(context, PorterBackend.SHIZUKU) != null) {
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

    /** The binder kept for [backend], if any. */
    @VisibleForTesting
    internal fun keptForTest(backend: PorterBackend): IBinder? = synchronized(lock) { kept[backend]?.binder }

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
            setRejected(null)
            for (record in kept.values) record.binder.unlinkToDeath(record.recipient, 0)
            kept.clear()
            postDeadHooks.clear()
            publishState()
        }
        ioDispatcher = defaultIoDispatcher
        deliveryExecutor = defaultDeliveryExecutor
        userServiceExecutor = defaultUserServiceExecutor
        ShizukuCompat.resetForTest()
        PorterApiProvider.resetForTest()
    }
}
