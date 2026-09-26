package eu.darken.porter.sdk

import android.os.RemoteException

/**
 * A server call that failed: at the binder, with the [RemoteException] as its cause, or at the server,
 * with the exception the server answered with as its cause. A [android.os.DeadObjectException] cause
 * means the server is gone.
 */
public class PorterRemoteException internal constructor(cause: Exception) :
    PorterException(cause.message, cause)
