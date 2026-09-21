package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import eu.darken.porter.core.ClientCallback
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.server.IPorterApplication

/** A [ClientCallback] spoken over the Porter client endpoint. */
class PorterClientCallback(application: IPorterApplication?) : ClientCallback {

    val application: IPorterApplication = (application ?: throw NullPointerException("application is null"))

    override fun asBinder(): IBinder = application.asBinder()

    @Throws(RemoteException::class)
    override fun onPermissionResult(requestCode: Int, allowed: Boolean) {
        val reply = Bundle()
        reply.putBoolean(PERMISSION_RESULT_ALLOWED, allowed)
        application.dispatchRequestPermissionResult(requestCode, reply)
    }

    @Throws(RemoteException::class)
    override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
        val state = Bundle()
        state.putBoolean(REPLY_PERMISSION_GRANTED, granted)
        state.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale)
        application.dispatchPermissionStateChanged(state)
    }
}
