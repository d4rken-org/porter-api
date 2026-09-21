package eu.darken.porter.sdk

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/**
 * One server connection, as the calls a client makes on it: the attach handshake, the raw
 * transaction code and everything reachable once attached.
 *
 * An implementation holds the connection it speaks for, and is the only place that names the
 * wire's own types. Nothing here mentions one, so a server speaking another protocol can be reached
 * through a second implementation.
 */
internal interface PorterWire {

    /** What a session wants told, whatever callback interface the wire actually registers. */
    interface Callbacks {

        fun onRequestPermissionResult(requestCode: Int, allowed: Boolean)

        fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean)
    }

    /** What the server answered, with a defined value for every key it left out. */
    data class AttachReply(
        val serverUid: Int,
        val protocolVersion: Int,
        val seLinuxContext: String?,
        val capabilities: Long,
        val permissionGranted: Boolean,
        val shouldShowRequestPermissionRationale: Boolean,
        /** The Shizuku patch level, and null on a wire whose server has none. */
        val patchVersion: Int?,
    )

    /** @return null if the server answered without a reply at all */
    @Throws(RemoteException::class)
    fun attach(packageName: String): AttachReply?

    /**
     * Told from the death dispatch that the connection this wire speaks over is gone. A wire whose
     * calls all fail by themselves on a dead peer needs nothing here.
     */
    fun onPeerDied() {
    }

    fun transactRemote(data: Parcel, reply: Parcel?, flags: Int)

    /** Forwards one transaction on [target], in whatever envelope this wire's server expects. */
    fun forward(target: IBinder, code: Int, data: Parcel, reply: Parcel?, flags: Int)

    fun getUid(): Int

    fun getSELinuxContext(): String?

    fun checkPermission(permission: String): Int

    fun getSystemProperty(name: String, defaultValue: String?): String?

    fun setSystemProperty(name: String, value: String)

    fun requestPermission(requestCode: Int)

    fun checkSelfPermission(): Boolean

    fun shouldShowRequestPermissionRationale(): Boolean

    /**
     * Binds one user service, encoding [args] in this wire's own server's key space.
     *
     * @param noCreate ask for the service only if it is already running
     */
    fun addUserService(conn: UserServiceCallback, args: UserServiceArgs, noCreate: Boolean): Int

    /**
     * Drops one user service binding, encoding [args] in this wire's own server's key space.
     *
     * A wire whose server cannot honour the call may decline to send it and answer 0.
     *
     * @param remove kill the remote user service; it is not killed otherwise
     */
    fun removeUserService(conn: UserServiceCallback?, args: UserServiceArgs, remove: Boolean): Int

    fun exit()

    fun attachUserService(binder: IBinder, token: String)

    fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        allowed: Boolean,
        onetime: Boolean,
    )

    fun getFlagsForUid(uid: Int, mask: Int): Int

    fun updateFlagsForUid(uid: Int, mask: Int, value: Int)
}
