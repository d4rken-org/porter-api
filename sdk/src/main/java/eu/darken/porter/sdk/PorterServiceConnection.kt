package eu.darken.porter.sdk

import android.content.ComponentName
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import java.util.EnumMap

internal class PorterServiceConnection(
    private val registry: PorterServiceConnections,
    args: UserServiceArgs,
) : UserServiceCallback {

    /** Guarded by [PorterServiceConnections.lock]. */
    private val listeners = HashSet<UserServiceListener>()

    /** Guarded by this instance, which is also what a wire locks while it registers one. */
    private val registeredBinders = EnumMap<PorterBackend, IBinder>(PorterBackend::class.java)

    private val componentName: ComponentName = args.componentName

    private var binder: IBinder? = null

    /** Set once, by the death of the binder this was bound to. Guarded by the same lock. */
    private var terminal = false

    /** The set a queued death delivery will be handed, null once it ran or was called off. */
    private var pendingDelivery: List<UserServiceListener>? = null

    /** @return whether [listener] was inserted, so a refused call can remove what it added */
    fun addListener(listener: UserServiceListener?): Boolean = synchronized(registry.lock) {
        listener != null && listeners.add(listener)
    }

    fun removeListener(listener: UserServiceListener?) {
        synchronized(registry.lock) {
            if (listener != null) listeners.remove(listener)
        }
    }

    /** Whether anyone is still registered here. The caller holds [PorterServiceConnections.lock]. */
    fun hasListeners(): Boolean = listeners.isNotEmpty()

    /** Whether the binder this was bound to has died. The caller holds [PorterServiceConnections.lock]. */
    fun isTerminal(): Boolean = terminal

    fun clearListeners() {
        synchronized(registry.lock) {
            listeners.clear()
        }
    }

    override fun registeredBinder(backend: PorterBackend): IBinder? = synchronized(this) {
        registeredBinders[backend]
    }

    override fun rememberRegisteredBinder(backend: PorterBackend, binder: IBinder) {
        synchronized(this) {
            registeredBinders[backend] = binder
        }
    }

    override fun connected(binder: IBinder) {
        synchronized(registry.lock) {
            // Nothing is registered here any more and nothing can be, so holding the binder and
            // linking a recipient would only keep both alive for a delivery that cannot happen.
            if (terminal) return
        }

        MAIN_HANDLER.post {
            val snapshot = synchronized(registry.lock) { listeners.toList() }
            for (listener in snapshot) {
                listener.onConnected(componentName, binder)
            }
        }

        // Hold the binder, or linkToDeath will not work after reference to
        // the binder is dropped
        this.binder = binder

        try {
            binder.linkToDeath({ died() }, 0)
        } catch (ignored: RemoteException) {
        }
    }

    /** Calls off a delivery that has not run yet, for a caller that no longer wants the binding. */
    fun cancelPendingDelivery() {
        synchronized(registry.lock) {
            pendingDelivery = null
        }
    }

    override fun died() {
        binder = null

        synchronized(registry.lock) {
            // One binder carries a recipient per "connected" push, so its death arrives repeatedly.
            if (terminal) return
            terminal = true
            pendingDelivery = listeners.toList()
            listeners.clear()
            registry.retire(this)

            // Queued while the eviction is still private to this thread: a rebind has to take this
            // same lock to register, so its "connected" cannot reach the queue ahead of this
            // disconnect. Posting is not executing, the body runs later and outside the lock.
            MAIN_HANDLER.post {
                val snapshot = synchronized(registry.lock) {
                    val delivery = pendingDelivery
                    pendingDelivery = null
                    registry.deliveryDone(this)
                    delivery
                } ?: return@post

                for (listener in snapshot) {
                    listener.onDisconnected(componentName)
                }
            }
        }
    }

    private companion object {
        val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}
