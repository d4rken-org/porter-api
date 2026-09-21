package eu.darken.porter.core

import android.os.IBinder
import android.os.RemoteException

/** What the server sends an attached client, whichever endpoint the client attached through. */
interface ClientCallback {

    /** The binder whose death means the client is gone. */
    fun asBinder(): IBinder

    @Throws(RemoteException::class)
    fun onPermissionResult(requestCode: Int, allowed: Boolean)

    @Throws(RemoteException::class)
    fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean)
}
