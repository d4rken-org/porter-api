package eu.darken.porter.core

import android.os.Binder
import rikka.shizuku.server.util.UserHandleCompat

/**
 * Who made a call. Captured once at the endpoint the call arrived on and handed down from there, so
 * that nothing below the endpoint has to ask Binder again.
 */
class CallerIdentity(val uid: Int, val pid: Int) {

    fun userId(): Int = UserHandleCompat.getUserId(uid)

    fun appId(): Int = UserHandleCompat.getAppId(uid)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CallerIdentity) return false
        return uid == other.uid && pid == other.pid
    }

    override fun hashCode(): Int = 31 * uid + pid

    override fun toString(): String = "CallerIdentity{uid=$uid, pid=$pid}"

    companion object {
        fun fromBinder(): CallerIdentity = CallerIdentity(Binder.getCallingUid(), Binder.getCallingPid())
    }
}
