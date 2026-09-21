package eu.darken.porter.sdk

import android.os.IBinder

/**
 * One user service binding as the SDK holds it: what a server pushes about the service, and the
 * binder each wire registered to receive those pushes.
 */
internal interface UserServiceCallback {

    fun connected(binder: IBinder)

    fun died()

    /**
     * The binder a wire registered for this callback, or null if that wire has not registered one.
     *
     * One callback presents one binder identity for its whole life; see
     * `PorterProtocolWireUserServiceTest`, which pins it across an add and a later remove.
     */
    fun registeredBinder(backend: PorterBackend): IBinder?

    fun rememberRegisteredBinder(backend: PorterBackend, binder: IBinder)
}
