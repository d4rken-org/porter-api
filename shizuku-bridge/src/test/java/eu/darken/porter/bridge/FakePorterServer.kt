package eu.darken.porter.bridge

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.extras.internal.PorterShellService
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection

/**
 * A local stub standing in for the server. A forwarded transaction is parsed the way the server's
 * own endpoint parses it: target, code and flags, then the caller's parcel.
 */
internal class FakePorterServer : IPorterService.Stub() {

    var attachReply: Bundle = Bundle().apply {
        putInt(PorterProtocol.REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION)
        putInt(PorterProtocol.REPLY_MIN_PROTOCOL_VERSION, PorterProtocol.MIN_VERSION)
        putInt(PorterProtocol.REPLY_SERVER_UID, 2000)
        putString(PorterProtocol.REPLY_SERVER_SECONTEXT, "u:r:shell:s0")
        putBoolean(PorterProtocol.REPLY_PERMISSION_GRANTED, true)
    }

    /** Called with what a forward carried; the payload is positioned at its start. */
    var onForward: (target: IBinder?, code: Int, flags: Int, payload: Parcel, reply: Parcel?) -> Unit = { _, _, _, _, _ -> }

    /** Thrown by the server's forwarding before it reads the payload. */
    var forwardFailure: Throwable? = null

    /** Thrown from every system property read. */
    var propertyFailure: Throwable? = null

    /** Refuses every user service bind, as a server does for an app without a grant. */
    var refuseBinds = false

    val shell = PorterShellService()

    /** What the SDK attached with; a test answers a permission request through it. */
    @Volatile
    var application: IPorterApplication? = null

    /** The code of the last permission request, which its answer has to carry. */
    @Volatile
    var requestedCode: Int? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != PorterProtocol.TRANSACTION_transactRemote) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(PorterProtocol.DESCRIPTOR)
        val target = data.readStrongBinder()
        val targetCode = data.readInt()
        val targetFlags = data.readInt()
        forwardFailure?.let { throw it }
        val payload = Parcel.obtain()
        try {
            payload.appendFrom(data, data.dataPosition(), data.dataAvail())
            payload.setDataPosition(0)
            onForward(target, targetCode, targetFlags, payload, reply)
        } finally {
            payload.recycle()
        }
        return true
    }

    override fun attach(application: IPorterApplication, args: Bundle): Bundle {
        this.application = application
        return attachReply
    }

    override fun getUid(): Int = 2000

    override fun checkPermission(permission: String): Int = PackageManager.PERMISSION_GRANTED

    override fun getSELinuxContext(): String = "u:r:shell:s0"

    override fun getSystemProperty(name: String, defaultValue: String?): String? {
        propertyFailure?.let { throw it }
        return defaultValue
    }

    override fun setSystemProperty(name: String, value: String) {
    }

    override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
        if (refuseBinds) throw SecurityException("not granted")
        conn?.connected(shell)
        return 0
    }

    override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle): Int = 0

    /** Thrown from a permission request instead of showing a dialog. */
    var permissionFailure: Throwable? = null

    override fun requestPermission(requestCode: Int) {
        permissionFailure?.let { throw it }
        requestedCode = requestCode
    }

    /** Answers the last permission request as the manager's dialog would. */
    fun answerPermissionRequest(allowed: Boolean) {
        application!!.dispatchRequestPermissionResult(requestedCode!!, Bundle().apply {
            putBoolean(PorterProtocol.PERMISSION_RESULT_ALLOWED, allowed)
        })
    }

    override fun checkSelfPermission(): Boolean = true

    override fun shouldShowRequestPermissionRationale(): Boolean = false
}
