package eu.darken.porter.core

import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException

/**
 * Someone waiting to be told about a user service. Extends [IInterface] because
 * `RemoteCallbackList` keys its registrations on [IInterface.asBinder].
 */
interface UserServiceConnection : IInterface {

    @Throws(RemoteException::class)
    fun connected(service: IBinder)

    @Throws(RemoteException::class)
    fun died()
}
