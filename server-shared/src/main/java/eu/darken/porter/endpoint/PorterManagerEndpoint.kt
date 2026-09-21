package eu.darken.porter.endpoint

import android.os.IBinder
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.core.PorterCore
import eu.darken.porter.server.IPorterManager
import eu.darken.porter.server.IPorterRemoteProcess

/**
 * The manager's own binder. The server hands it out only to the manager, and every method here
 * asks the core again, so a caller that came by the binder some other way is refused all the same.
 */
open class PorterManagerEndpoint(
    private val core: PorterCore<*, *, *>,
    private val managerOperations: ManagerOperations,
) : IPorterManager.Stub() {

    final override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IPorterRemoteProcess {
        val caller = CallerIdentity.fromBinder()
        core.enforceManagerPermission("newProcess", caller)
        return PorterRemoteProcessHolder(
            core.newServerProcess(caller, cmd ?: throw NullPointerException("cmd is null"), env, dir),
        )
    }

    final override fun exit() {
        core.enforceManagerPermission("exit", CallerIdentity.fromBinder())
        managerOperations.exit()
    }

    final override fun attachUserService(binder: IBinder?, token: String?) {
        core.enforceManagerPermission("attachUserService", CallerIdentity.fromBinder())
        managerOperations.attachUserService(
            binder ?: throw NullPointerException("binder is null"),
            token ?: throw NullPointerException("token is null"),
        )
    }

    final override fun dispatchPermissionConfirmationResult(uid: Int, pid: Int, requestCode: Int, allowed: Boolean, onetime: Boolean) {
        core.enforceManagerPermission("dispatchPermissionConfirmationResult", CallerIdentity.fromBinder())
        managerOperations.dispatchPermissionConfirmationResult(uid, pid, requestCode, allowed, onetime)
    }

    final override fun getFlagsForUid(uid: Int, mask: Int): Int {
        core.enforceManagerPermission("getFlagsForUid", CallerIdentity.fromBinder())
        return managerOperations.getFlagsForUid(uid, mask)
    }

    final override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        core.enforceManagerPermission("updateFlagsForUid", CallerIdentity.fromBinder())
        managerOperations.updateFlagsForUid(uid, mask, value)
    }
}
