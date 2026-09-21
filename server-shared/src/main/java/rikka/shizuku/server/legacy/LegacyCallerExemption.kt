package rikka.shizuku.server.legacy

import eu.darken.porter.core.CallerExemption
import eu.darken.porter.core.CallerIdentity
import rikka.shizuku.server.util.OsUtils

/** The server's own uid and its own process, answered as Shizuku clients have always been. */
internal object LegacyCallerExemption : CallerExemption {

    override fun waivesGrant(caller: CallerIdentity): Boolean = caller.uid == OsUtils.uid

    override fun answersWithoutRecord(caller: CallerIdentity): Boolean =
        caller.uid == OsUtils.uid || caller.pid == OsUtils.pid
}
