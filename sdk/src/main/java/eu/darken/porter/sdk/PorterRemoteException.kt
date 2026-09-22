package eu.darken.porter.sdk

import android.os.RemoteException

/**
 * A server call that failed at the binder, with the [RemoteException] as its cause. A
 * [android.os.DeadObjectException] cause means the server is gone.
 */
public class PorterRemoteException internal constructor(cause: RemoteException) :
    PorterException(cause.message, cause)
