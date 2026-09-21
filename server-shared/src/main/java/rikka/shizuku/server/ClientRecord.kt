package rikka.shizuku.server

import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ClientCallback
import moe.shizuku.server.IShizukuApplication
import rikka.shizuku.server.legacy.LegacyClientCallback
import rikka.shizuku.server.util.Logger

class ClientRecord(
    identity: CallerIdentity,
    val callback: ClientCallback,
    val packageName: String,
    /**
     * What the client declared when it attached, on the scale of the wire it attached through:
     * the Shizuku API level (-1 before v13) or the Porter protocol version.
     */
    val apiVersion: Int,
) {

    val uid: Int = identity.uid
    val pid: Int = identity.pid

    /** Null unless the client attached through the Shizuku endpoint. */
    val client: IShizukuApplication? = (callback as? LegacyClientCallback)?.application
    var allowed: Boolean = false

    constructor(uid: Int, pid: Int, client: IShizukuApplication, packageName: String, apiVersion: Int) :
        this(CallerIdentity(uid, pid), LegacyClientCallback(client), packageName, apiVersion)

    fun dispatchRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        try {
            callback.onPermissionResult(requestCode, allowed)
        } catch (e: Throwable) {
            LOGGER.w(e, "dispatchRequestPermissionResult failed for client (uid=%d, pid=%d, package=%s)", uid, pid, packageName)
        }
    }

    companion object {
        private val LOGGER = Logger("ClientRecord")
    }
}
