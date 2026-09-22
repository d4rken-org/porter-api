package eu.darken.porter.sdk

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_RESULT_NOT_RUNNING
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * One connection to one server binder: what its attach reply said, what the server has pushed since,
 * and everything reachable through it.
 *
 * A connection is never reused for another binder, and every call on it goes to the server it was
 * attached to, whichever connection [Porter.connection] holds by then. A death notification, a
 * permission push or an attach reply that arrives late therefore carries the connection it belongs
 * to, and can be told apart from the connection that is current now.
 *
 * The calls here that answer from the server are single binder transactions and block for one.
 * [requestPermission] and [userService] are the two that wait on another party, and suspend.
 */
public class PorterConnection internal constructor(
    /** Never reset, so a connection of this process is never mistaken for a later one. */
    internal val generation: Int,
    /** The server binder itself. Normal apps should not need it. */
    public val binder: IBinder,
    /** The wire this connection speaks. */
    public val backend: PorterBackend,
) {

    internal val wire: PorterWire = when (backend) {
        PorterBackend.SHIZUKU -> ShizukuProtocolWire(binder, SessionCallbacks())
        PorterBackend.PORTER -> PorterProtocolWire(binder, SessionCallbacks())
    }

    private var serverUid = -1
    private var serverProtocolVersion = 0
    private var serverPatchVersion: Int? = null
    private var serverContext: String? = null
    private var serverCapabilities = CAPABILITIES_NONE

    /**
     * Guards the permission state and [pendingRequests], which the server writes from a binder
     * thread. Never held across a binder call: acquire it to snapshot, release it for the call,
     * acquire it to apply.
     */
    private val permissionLock = Any()

    private var permissionGranted = false
    private var shouldShowRequestPermissionRationale = false

    /** Counts the state pushes, so a reply that started before one can tell it lost the race. */
    private var permissionStateGeneration = 0

    /** True once this connection was replaced or died; nothing waiting on it is answered after. */
    private var lost = false

    private val pendingRequests = HashMap<Int, CompletableDeferred<PermissionResult>>()

    /** What the manager answered, and the state generation that answer was applied as. */
    private class PermissionResult(val allowed: Boolean, val pushes: Int)
    private val requestCodes = AtomicInteger()

    private val _permission = MutableStateFlow<PermissionState>(PermissionState.Denied(permanentlyDenied = false))

    /**
     * Whether the server lets this app through: what the attach reply said, then every state the
     * server pushed since, then what [checkPermission] and [requestPermission] learned. The latest
     * state, not a log of the changes.
     */
    public val permission: StateFlow<PermissionState> = _permission.asStateFlow()

    internal val deathRecipient = IBinder.DeathRecipient { onBinderDied() }

    /** Whether [deathRecipient] is registered on [binder]. Guarded by [Porter.lock]. */
    private var linked = false

    private inner class SessionCallbacks : PorterWire.Callbacks {

        override fun onRequestPermissionResult(requestCode: Int, allowed: Boolean) {
            // A result belongs to the connection that asked for it, and only while that connection
            // is the one Porter answers for: a replacement can have published between this
            // connection sending the request and its server answering, ahead of it being marked
            // lost.
            val current = Porter.isCurrent(this@PorterConnection)
            val waiting: CompletableDeferred<PermissionResult>
            val pushes: Int
            synchronized(permissionLock) {
                waiting = (if (lost) null else pendingRequests.remove(requestCode)) ?: return
                // Applied here rather than when the caller resumes: a state push that lands in
                // between is newer than this result, and the caller may resume after it.
                if (current) {
                    permissionGranted = allowed
                    if (allowed) shouldShowRequestPermissionRationale = false
                    permissionStateGeneration++
                    publishPermission()
                }
                pushes = permissionStateGeneration
            }
            if (current) {
                waiting.complete(PermissionResult(allowed, pushes))
            } else {
                waiting.completeExceptionally(PorterConnectionLostException())
            }
        }

        override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
            synchronized(Porter.lock) {
                // Both callbacks are oneway, so a server that has been replaced can still have one
                // in flight; it describes a connection nobody asks about any more. The connection
                // that is serving is one nobody asks about only once a replacement has published.
                if (!Porter.isCurrentOrLatest(this@PorterConnection)) return
                synchronized(permissionLock) {
                    permissionGranted = granted
                    shouldShowRequestPermissionRationale = shouldShowRationale
                    permissionStateGeneration++
                    publishPermission()
                }
            }
        }
    }

    // --------------------- what the server reported ----------------------

    /** What this connection's server reported when it attached. */
    public val serverInfo: PorterServerInfo
        get() = PorterServerInfo(backend, serverProtocolVersion, serverPatchVersion)

    /** The uid the server runs as: 0 for root, 2000 for adb. */
    public val uid: Int
        get() {
            if (serverUid != -1) return serverUid
            serverUid = wire.getUid()
            return serverUid
        }

    /** The uid as far as it is already known, without asking the server for it. */
    internal val reportedUid: Int get() = serverUid

    internal val capabilities: Long get() = serverCapabilities

    /**
     * SELinux context of the server process. For adb this is `u:r:shell:s0`; for root it depends
     * on the su implementation.
     */
    public val seLinuxContext: String?
        get() {
            serverContext?.let { return it }
            serverContext = wire.getSELinuxContext()
            return serverContext
        }

    /** Whether the server binder still answers a ping. */
    public val isAlive: Boolean get() = binder.pingBinder()

    internal fun permissionPushes(): Int = synchronized(permissionLock) { permissionStateGeneration }

    internal fun apply(reply: PorterWire.AttachReply, pushes: Int) {
        serverUid = reply.serverUid
        serverProtocolVersion = reply.protocolVersion
        serverPatchVersion = reply.patchVersion
        serverContext = reply.seLinuxContext
        serverCapabilities = reply.capabilities
        synchronized(permissionLock) {
            // The server registers the client before it answers, so a state push can already have
            // overtaken this reply. It then describes the newer state.
            if (permissionStateGeneration == pushes) {
                permissionGranted = reply.permissionGranted
                shouldShowRequestPermissionRationale = reply.shouldShowRequestPermissionRationale
                publishPermission()
            }
        }
    }

    // --------------------- permission ----------------------

    /** The caller holds [permissionLock]. */
    private fun publishPermission() {
        _permission.value = if (permissionGranted) {
            PermissionState.Granted
        } else {
            PermissionState.Denied(shouldShowRequestPermissionRationale)
        }
    }

    /**
     * The permission state, asking the server where [permission] does not already hold the answer:
     * a grant is answered from what the server last reported, a denial is asked about again, and
     * whether it is permanent is asked for when the state does not already say so. Whatever the
     * server answers lands in [permission].
     *
     * On the Shizuku backend the pushed state is synthesized by the SDK from a server's re-sent
     * attach state, because that wire carries no callback of its own for it. A server that never
     * re-sends leaves a grant in place until its binder dies.
     */
    public fun checkPermission(): PermissionState {
        var pushes: Int
        synchronized(permissionLock) {
            if (permissionGranted) return PermissionState.Granted
            pushes = permissionStateGeneration
        }
        var granted = wire.checkSelfPermission()
        synchronized(permissionLock) {
            if (permissionStateGeneration == pushes) {
                permissionGranted = granted
                publishPermission()
            } else {
                granted = permissionGranted
            }
            if (granted) return PermissionState.Granted
            if (shouldShowRequestPermissionRationale) return PermissionState.Denied(permanentlyDenied = true)
            pushes = permissionStateGeneration
        }
        var rationale = wire.shouldShowRequestPermissionRationale()
        synchronized(permissionLock) {
            if (permissionStateGeneration == pushes) {
                shouldShowRequestPermissionRationale = rationale
                publishPermission()
            } else {
                rationale = !permissionGranted && shouldShowRequestPermissionRationale
            }
            return if (permissionGranted) PermissionState.Granted else PermissionState.Denied(rationale)
        }
    }

    /**
     * Asks the manager to prompt the user, and suspends until the user answers. The answer lands in
     * [permission] as it arrives, and what is returned is that state once this call resumes: a
     * state the server pushed after the answer is newer and wins.
     *
     * A prompt already shown is not withdrawn by cancelling this call; its late answer is dropped.
     *
     * @throws PorterConnectionLostException if this connection is replaced or dies before the
     * server answers
     */
    public suspend fun requestPermission(): PermissionState {
        val requestCode = requestCodes.incrementAndGet()
        val answer = CompletableDeferred<PermissionResult>()
        synchronized(permissionLock) {
            if (lost) throw PorterConnectionLostException()
            pendingRequests[requestCode] = answer
        }
        try {
            wire.requestPermission(requestCode)
            val result = answer.await()
            if (result.allowed) return synchronized(permissionLock) { _permission.value }
            // A denial names no rationale flag, so the server is asked for it. The answer
            // describes the denial, so it applies only while that is still the state.
            val rationale = wire.shouldShowRequestPermissionRationale()
            synchronized(permissionLock) {
                if (permissionStateGeneration == result.pushes) {
                    shouldShowRequestPermissionRationale = rationale
                    publishPermission()
                }
                return _permission.value
            }
        } finally {
            synchronized(permissionLock) { pendingRequests.remove(requestCode) }
        }
    }

    /**
     * Fails everything still waiting on this connection and ends its user service flows; it will
     * not be answered any more.
     */
    internal fun markLost() {
        val waiting = synchronized(permissionLock) {
            lost = true
            val waiting = pendingRequests.values.toList()
            pendingRequests.clear()
            waiting
        }
        for (request in waiting) request.completeExceptionally(PorterConnectionLostException())
        // A server that is still running keeps every callback it was given until told otherwise;
        // one that is not answers nothing, so it is not asked.
        val bindings = userServices.close()
        if (bindings.isEmpty() || !binder.pingBinder()) return
        // Off the calling thread, which may be the main one: a server that stalls in the call would
        // otherwise stall the app, and nothing here waits for the answer.
        val unbind = Thread({ for (binding in bindings) dropOnServer(binding) }, "porter-unbind")
        unbind.isDaemon = true
        unbind.start()
    }

    // --------------------- calls on the server ----------------------

    /** Whether the server itself holds [permission]. */
    public fun checkRemotePermission(permission: String): Boolean {
        if (reportedUid == 0) return true
        return wire.checkPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    public fun getSystemProperty(name: String, default: String? = null): String? =
        wire.getSystemProperty(name, default)

    public fun setSystemProperty(name: String, value: String) {
        wire.setSystemProperty(name, value)
    }

    /** Calls [IBinder.transact] in the server. See [wrap] for the usual way to reach it. */
    public fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        wire.transactRemote(data, reply, flags)
    }

    /**
     * Wraps [binder] so that every transaction on the result is forwarded through the server.
     *
     * example:
     * ```
     * val pm = IPackageManager.Stub.asInterface(connection.wrap(binder))
     * pm.getInstalledPackages(0, 0)
     * ```
     */
    public fun wrap(binder: IBinder): IBinder = PorterBinderWrapper(this, binder)

    // --------------------- user services ----------------------

    /** The bindings this connection holds; ended with it, see [markLost]. */
    internal val userServices = PorterServiceConnections()

    /**
     * A user service is a bound service that runs in its own process, as the identity the server
     * runs as.
     *
     * Collecting binds the service, and starts it unless [start] is false. The service binder is
     * emitted once the server reports it connected, and again if the server reports it connected
     * again with a different binder; the flow completes when the server reports the service died,
     * and when this connection is replaced or dies, whether or not the service is still running.
     * With [start] false and no running service, the flow completes without emitting. A flow
     * started on a connection that is already replaced or dead completes at once.
     *
     * Bindings are shared by the service identity, which is [UserServiceArgs.tag] where one is set
     * and the service class name otherwise, among the collectors of this connection: the same
     * identity collected on another connection is another binding, on that connection's server.
     * Cancelling the collection drops this collector; when the last collector of that identity
     * goes, the server is asked to drop the binding without killing the service. A Shizuku server
     * below 13.4 is not asked, and keeps it until the service dies.
     *
     * Unbinding does not kill the service: implement a "destroy" method under transaction code
     * [eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TRANSACTION_destroy] (`16777114` in
     * aidl) that cleans up and calls [System.exit], or call [stopUserService].
     *
     * A service is per Android user: a work profile's copy of an app is served by its own process,
     * started with that profile's uid, whatever the personal profile's copy is running.
     *
     * The service process is not a valid Android application process. A `Context` obtained there
     * cannot register receivers or reach a content resolver.
     */
    public fun userService(args: UserServiceArgs, start: Boolean = true): Flow<IBinder> = callbackFlow {
        val listener = object : UserServiceListener {
            override fun onConnected(componentName: android.content.ComponentName, binder: IBinder) {
                trySend(binder)
            }

            override fun onDisconnected(componentName: android.content.ComponentName) {
                close()
            }
        }
        val registration = userServices.register(args, listener)
        if (registration == null) {
            close()
            return@callbackFlow
        }
        val result = try {
            wire.addUserService(registration.connection, args, noCreate = !start)
        } catch (e: RuntimeException) {
            // Removes the registration this call made, and no lifecycle state: a death either
            // happened, in which case the binding is retired and evicted, or it did not.
            if (registration.inserted) registration.connection.removeListener(listener)
            throw e
        }
        // Registered here before the connection was lost, and on the server after its bindings
        // were dropped there, so this one is dropped on its own. The listener was told of the loss.
        if (userServices.isClosed()) dropOnServer(registration.connection)
        if (!start && result == USER_SERVICE_RESULT_NOT_RUNNING) close()
        awaitClose { release(args, registration.connection, listener) }
    }.distinctUntilChanged { old, new -> old === new }

    /**
     * Drops one collector's registration, and the server's binding with it when it was the last.
     * The registry decides which under its own lock, so a collector arriving at the same time
     * either joins before the count reaches zero or finds a fresh entry after the eviction.
     */
    private fun release(args: UserServiceArgs, connection: PorterServiceConnection, listener: UserServiceListener) {
        val last = synchronized(userServices.lock) {
            connection.removeListener(listener)
            // A dead binding was retired by its death and has nothing left on the server to drop.
            val last = !connection.isTerminal() && !connection.hasListeners()
            if (last) userServices.remove(connection)
            last
        }
        if (!last) return
        // The connection is a Binder the server still holds, so it would keep receiving "connected"
        // and "died" and keep calling back after a later bind. Drop it on the server.
        dropOnServer(connection)
    }

    private fun dropOnServer(binding: PorterServiceConnection) {
        try {
            wire.removeUserService(binding, binding.args, remove = false)
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not drop the user service binding: $e")
        }
    }

    /**
     * Whether the service is running: its version code if it is, or null. Does not start it, and
     * does not bind this caller to it.
     */
    public fun peekUserService(args: UserServiceArgs): Int? {
        // Outside the registry: nothing collects through it, so a running service's push lands on
        // a callback nobody reads, and the registration is dropped again as soon as it answered.
        val probe = PorterServiceConnection(userServices, args)
        val result = wire.addUserService(probe, args, noCreate = true)
        if (result != USER_SERVICE_RESULT_NOT_RUNNING) {
            try {
                wire.removeUserService(probe, args, remove = false)
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not drop the peek registration: $e")
            }
        }
        return result.takeIf { it != USER_SERVICE_RESULT_NOT_RUNNING }
    }

    /**
     * Asks the service to stop, by sending it
     * [eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TRANSACTION_destroy]. The server does
     * not kill the process, so a service that does not implement that transaction keeps running.
     * Collectors of [userService] see the flow complete either way.
     */
    public fun stopUserService(args: UserServiceArgs) {
        wire.removeUserService(null, args, remove = true)
    }

    // --------------------- lifecycle ----------------------

    internal fun link() {
        try {
            binder.linkToDeath(deathRecipient, 0)
            synchronized(Porter.lock) {
                linked = true
            }
        } catch (e: Throwable) {
            Log.i(TAG, "linkToDeath")
        }
    }

    /**
     * Unlinks at most once: several paths give up on a connection, and a binder in another process
     * throws when it is unlinked from a recipient it does not hold.
     */
    internal fun unlink() {
        synchronized(Porter.lock) {
            if (!linked) return
            linked = false
            binder.unlinkToDeath(deathRecipient, 0)
        }
    }

    private fun onBinderDied() {
        val wasCurrent = Porter.connectionDied(this)
        // Whatever this connection had published, it is its own binder that died, and a wire still
        // waiting on that binder has nothing left to wait for. Outside the lock, as the wire counts
        // its own attach latch down outside its own.
        wire.onPeerDied()
        if (wasCurrent) markLost()
    }

    override fun toString(): String = "PorterConnection(generation=$generation, backend=$backend)"

    private companion object {
        const val TAG = "Porter"
    }
}
