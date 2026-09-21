package eu.darken.porter.sdk

import android.content.ComponentName
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.server.IPorterServiceConnection
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.robolectric.shadows.ShadowLooper

/** What the user service tests share: the service identity, the connection and the looper. */
internal object UserServiceTestSupport {

    const val PACKAGE = "eu.darken.porter.probe"
    const val CLASS = "ProbeService"
    const val PROCESS_SUFFIX = "probe"

    fun args(tag: String): UserServiceArgs =
        UserServiceArgs(ComponentName(PACKAGE, CLASS), processNameSuffix = PROCESS_SUFFIX, tag = tag)

    fun connection(): PorterConnection = Porter.connection.value ?: error("no connection is published")

    /** The published connection's binding for [args], if it holds one. */
    fun peek(args: UserServiceArgs): PorterServiceConnection? = connection().userServices.peek(args)

    fun idle() {
        ShadowLooper.shadowMainLooper().idle()
    }

    /** Polls [condition] for up to [timeoutMs], for a collector on a thread of its own. */
    fun await(timeoutMs: Long = 2000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }
}

/**
 * Fails or scripts the user service calls a test asks it to, as a server that refused them would,
 * and records the connection binder of every add and remove it was asked for.
 *
 * A snippet and a failure are both cleared before the snippet runs, so a bind the snippet itself
 * makes is accepted untouched.
 */
internal open class ScriptedPorterService : FakePorterService() {

    var addFailure: RuntimeException? = null
    var removeFailure: RuntimeException? = null
    var duringAdd: (() -> Unit)? = null

    /** The connection binder of every add and remove, in order; null where none was named. */
    val connections: MutableList<IBinder?> = Collections.synchronizedList(ArrayList())

    /** Adds the server accepted. */
    @Volatile
    var adds = 0

    override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
        val snippet = duringAdd
        duringAdd = null
        val failure = addFailure
        addFailure = null
        connections.add(conn?.asBinder())
        snippet?.invoke()
        if (failure != null) throw failure
        adds++
        return super.addUserService(conn, args)
    }

    override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
        connections.add(conn?.asBinder())
        removeFailure?.let { throw it }
        return super.removeUserService(conn, args)
    }
}

/**
 * One caller collecting a user service flow: every binder it was handed, whether the flow
 * completed (the service died) and whether it failed (the server refused the bind).
 */
internal class RecordingCollector(
    scope: CoroutineScope,
    flow: Flow<IBinder>,
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {

    val binders: MutableList<IBinder> = Collections.synchronizedList(ArrayList())

    @Volatile
    var completed = false

    @Volatile
    var failure: Throwable? = null

    val job: Job = scope.launch(dispatcher) {
        try {
            flow.collect { binders.add(it) }
            completed = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failure = e
        }
    }

    val connects: Int get() = binders.size

    val disconnects: Int get() = if (completed) 1 else 0
}
