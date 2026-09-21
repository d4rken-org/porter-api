package rikka.shizuku.server.legacy

import eu.darken.porter.core.UserServiceBindResult
import eu.darken.porter.core.UserServiceRemoveResult

/**
 * The integers a Shizuku client reads back. A client at API 13 or later is told the version code
 * of a running service and -1 for none; an older client is told 0 and 1.
 */
object LegacyUserServiceResults {

    fun encodeBind(result: UserServiceBindResult, callingApiVersion: Int): Int = when (result) {
        UserServiceBindResult.Bound -> 0
        is UserServiceBindResult.Running -> if (callingApiVersion >= 13) result.versionCode else 0
        UserServiceBindResult.NotRunning -> if (callingApiVersion >= 13) -1 else 1
    }

    fun encodeRemove(result: UserServiceRemoveResult): Int = when (result) {
        UserServiceRemoveResult.Removed -> 0
        UserServiceRemoveResult.NoSuchRecord -> 1
    }
}
