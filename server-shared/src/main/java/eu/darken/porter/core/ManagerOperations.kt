package eu.darken.porter.core

import android.os.IBinder

/**
 * The manager-only operations the server application answers, reached from either endpoint once the
 * manager gate passed. An implementation that must be atomic against record creation synchronizes
 * on `core.clientManager`, the monitor [PorterCore.attach] takes.
 */
interface ManagerOperations {

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
