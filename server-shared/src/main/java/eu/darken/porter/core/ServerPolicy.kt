package eu.darken.porter.core

import android.os.Bundle
import rikka.shizuku.server.ClientRecord

/**
 * The decisions a server makes for itself. Every method has a default, so a server that grants
 * nothing is a valid one and a test implements only the hooks it drives.
 */
interface ServerPolicy {

    fun checkCallerPermission(func: String, caller: CallerIdentity, record: ClientRecord?): Boolean = false

    fun checkCallerManagerPermission(func: String, caller: CallerIdentity): Boolean = false

    fun showPermissionConfirmation(requestCode: Int, record: ClientRecord, caller: CallerIdentity, userId: Int) {
        throw UnsupportedOperationException("no permission confirmation UI")
    }

    /** Before a record exists for the caller. */
    fun onAttaching(caller: CallerIdentity, packageName: String) {
    }

    /** The reply the endpoint is about to send, still open to shaping. */
    fun onAttached(record: ClientRecord, created: Boolean, reply: Bundle) {
    }

    /** After the reply reached the client. */
    fun onBound(record: ClientRecord, created: Boolean) {
    }
}
