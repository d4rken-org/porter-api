package rikka.shizuku.server.legacy

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import eu.darken.porter.core.ClientCallback
import moe.shizuku.server.IShizukuApplication
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED

/** A [ClientCallback] spoken over the Shizuku client endpoint. */
class LegacyClientCallback(application: IShizukuApplication?) : ClientCallback {

    val application: IShizukuApplication = (application ?: throw NullPointerException("application is null"))

    override fun asBinder(): IBinder = application.asBinder()

    @Throws(RemoteException::class)
    override fun onPermissionResult(requestCode: Int, allowed: Boolean) {
        val reply = Bundle()
        reply.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        application.dispatchRequestPermissionResult(requestCode, reply)
    }

    override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
    }
}
