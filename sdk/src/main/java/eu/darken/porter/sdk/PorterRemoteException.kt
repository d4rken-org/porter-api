package eu.darken.porter.sdk

import android.os.RemoteException

/** A server call that failed at the binder, thrown unchecked with the [RemoteException] as its cause. */
public class PorterRemoteException(cause: RemoteException) : RuntimeException(cause)
