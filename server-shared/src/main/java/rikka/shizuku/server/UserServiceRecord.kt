package rikka.shizuku.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteCallbackList
import eu.darken.porter.core.HostProcess
import eu.darken.porter.core.UserServiceConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.Logger

abstract class UserServiceRecord(val versionCode: Int, daemon: Boolean) {

    private var startTimeoutCallback: Runnable? = null

    private inner class ConnectionList : RemoteCallbackList<UserServiceConnection>() {

        override fun onCallbackDied(callback: UserServiceConnection) {
            if (daemon || registeredCallbackCount != 0) {
                return
            }

            LOGGER.v("Remove service record %s since it does not run as a daemon and all connections are gone", logId)
            removeSelf()
        }
    }

    private val deathRecipient: IBinder.DeathRecipient
    var token: String = UUID.randomUUID().toString() + "-" + System.currentTimeMillis()

    /** Names the record in logs, which every host process can read, in place of [token]. */
    val logId: String = "#" + NEXT_LOG_ID.incrementAndGet()
    var service: IBinder? = null
    val callbacks: RemoteCallbackList<UserServiceConnection> = ConnectionList()
    var daemon: Boolean = daemon

    /**
     * Written under the manager monitor, read from the start executor and the main handler, neither
     * of which holds it.
     */
    @Volatile
    var starting: Boolean = false

    @Volatile
    private var removed = false

    /**
     * The host process that claimed this record's launch, set once under the manager monitor. Removing
     * the record kills it, because a host running the app's code need not honour [destroy].
     */
    @Volatile
    var host: HostProcess? = null
        internal set

    /**
     * Acquired once, with no monitor held, by whoever publishes the binder. [destroy] needs
     * it and cannot ask the remote for it: that is a synchronous round trip a wedged service never
     * answers.
     */
    @Volatile
    private var interfaceDescriptor: String? = null

    init {
        deathRecipient = IBinder.DeathRecipient {
            LOGGER.v("Binder for service record %s is dead", logId)
            removeSelf()
        }
    }

    fun setStartingTimeout(timeoutMillis: Long) {
        if (starting) {
            LOGGER.w("Service record %s is already starting", logId)
            return
        }

        LOGGER.v("Set starting timeout for service record %s: %d", logId, timeoutMillis)

        starting = true
        val callback = Runnable {
            if (!removed && starting) {
                LOGGER.w("Service record %s is not started in %d ms", logId, timeoutMillis)
                removeSelf()
            }
        }
        startTimeoutCallback = callback
        HandlerUtil.mainHandler.postDelayed(callback, timeoutMillis)
    }

    /**
     * Marks the record detached from every index. A record only ever goes from live to removed, so
     * the callers that consult [isRemoved] without the monitor cannot miss a later revival.
     */
    fun markRemoved() {
        removed = true
        starting = false
        startTimeoutCallback?.let { HandlerUtil.mainHandler.removeCallbacks(it) }
    }

    val isRemoved: Boolean get() = removed

    fun setBinder(binder: IBinder, interfaceDescriptor: String?) {
        LOGGER.v("Binder received for service record %s", logId)

        startTimeoutCallback?.let { HandlerUtil.mainHandler.removeCallbacks(it) }

        service = binder
        this.interfaceDescriptor = interfaceDescriptor

        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (tr: Throwable) {
            LOGGER.w("linkToDeath %s", logId)
        }

        broadcastBinderReceived()
    }

    fun broadcastBinderReceived() {
        LOGGER.v("Broadcast binder received for service record %s", logId)

        val service = service
        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).connected(checkNotNull(service))
            } catch (e: Throwable) {
                LOGGER.w("Failed to call connected %s", logId)
            }
        }
        callbacks.finishBroadcast()
    }

    fun broadcastBinderDied() {
        LOGGER.v("Broadcast binder died for service record %s", logId)

        val count = callbacks.beginBroadcast()
        for (i in 0 until count) {
            try {
                callbacks.getBroadcastItem(i).died()
            } catch (e: Throwable) {
                LOGGER.w("Failed to call died %s", logId)
            }
        }
        callbacks.finishBroadcast()
    }

    abstract fun removeSelf()

    fun destroy() {
        try {
            val service = service
            if (service != null) {
                try {
                    service.unlinkToDeath(deathRecipient, 0)
                } catch (tr: Throwable) {
                    LOGGER.w("unlinkToDeath %s", logId)
                }
            }

            // A record removed by start timeout never received a binder, so both guards stay.
            val interfaceDescriptor = interfaceDescriptor
            if (service != null && interfaceDescriptor != null) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(interfaceDescriptor)
                    service.transact(USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY)
                } catch (e: Throwable) {
                    LOGGER.w("Failed to call destroy %s", logId)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            } else if (service != null) {
                // Parcel.writeInterfaceToken(null) reaches a JNI null check that aborts the process,
                // which no catch here would see.
                LOGGER.w("No interface descriptor for service record %s, cannot request destroy", logId)
            } else {
                // Nothing was delivered that a caller could watch die, so this is the only way it
                // learns the binding ended.
                broadcastBinderDied()
            }
        } finally {
            callbacks.kill()
        }
    }

    companion object {
        protected val LOGGER = Logger("UserServiceRecord")

        private val NEXT_LOG_ID = AtomicInteger()
    }
}
