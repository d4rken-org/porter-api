package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.annotation.CallSuper
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.core.PorterCore
import eu.darken.porter.porsh.PorshService
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME
import eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterRemoteProcess
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils

/**
 * Porter's own client endpoint. Every operation is answered by the same core the Shizuku endpoint
 * answers from, so the two wires cannot drift apart in what they permit or record.
 */
open class PorterEndpoint(
    private val core: PorterCore<*, *, *>,
    private val managerOperations: ManagerOperations,
) : IPorterService.Stub() {

    private val porshService: PorshService =
        object : PorshService(PorterProtocol.DESCRIPTOR, PorterProtocol.TRANSACTION_PORSH_BASE) {

            override fun enforceCallingPermission(func: String) {
                core.enforceCallingPermission(func, CallerIdentity.fromBinder())
            }
        }

    override fun attach(application: IPorterApplication?, args: Bundle?): Bundle {
        val app: IPorterApplication = (application ?: throw NullPointerException("application is null"))
        val attachArgs: Bundle = (args ?: throw NullPointerException("args is null"))

        val packageName: String = (attachArgs.getString(ATTACH_PACKAGE_NAME) ?: throw NullPointerException("package name is null"))
        val caller = CallerIdentity.fromBinder()

        LOGGER.d(
            "attach: uid=%d, pid=%d, package=%s, protocol=%d",
            caller.uid, caller.pid, packageName, attachArgs.getInt(ATTACH_PROTOCOL_VERSION, 0),
        )

        val result = core.attach(caller, packageName, PorterClientCallback(app), CORE_API_LEVEL)

        val reply = Bundle()
        reply.putInt(REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION)
        reply.putInt(REPLY_SERVER_UID, OsUtils.uid)
        reply.putString(REPLY_SERVER_SECONTEXT, OsUtils.seLinuxContext)
        reply.putBoolean(REPLY_PERMISSION_GRANTED, result.record.allowed)
        reply.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
        reply.putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE)

        core.policy.onAttached(result.record, result.created, reply)
        // The synchronous reply is the delivery: returning it is what a bindApplication call is on
        // the Shizuku wire.
        core.policy.onBound(result.record, result.created)
        return reply
    }

    final override fun getUid(): Int = core.getUid(CallerIdentity.fromBinder())

    @Throws(RemoteException::class)
    final override fun checkPermission(permission: String?): Int {
        // The gate answers before any argument is read, so an unauthorized caller is refused for
        // being unauthorized whatever it sent.
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("checkPermission", caller)
        return core.checkPermission(caller, permission ?: throw NullPointerException("permission is null"))
    }

    final override fun getSELinuxContext(): String? = core.getSELinuxContext(CallerIdentity.fromBinder())

    final override fun getSystemProperty(name: String?, defaultValue: String?): String? {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("getSystemProperty", caller)
        return core.getSystemProperty(caller, name ?: throw NullPointerException("name is null"), defaultValue)
    }

    final override fun setSystemProperty(name: String?, value: String?) {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("setSystemProperty", caller)
        core.setSystemProperty(caller, name ?: throw NullPointerException("name is null"), value)
    }

    final override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IPorterRemoteProcess {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("newProcess", caller)
        return PorterRemoteProcessHolder(
            core.newServerProcess(caller, cmd ?: throw NullPointerException("cmd is null"), env, dir),
        )
    }

    final override fun addUserService(conn: IPorterServiceConnection?, args: Bundle?): Int {
        val caller = CallerIdentity.fromBinder()
        // Before any Bundle content is read: an unauthorized caller is refused, never told what
        // their arguments decoded to.
        core.enforceCallingPermission("addUserService", caller)

        val connection: IPorterServiceConnection = (conn ?: throw NullPointerException("connection is null"))
        val checkedArgs: Bundle = (args ?: throw NullPointerException("args is null"))

        return core.addUserService(
            caller,
            PorterServiceConnection(connection),
            PorterUserServiceOptions.decodeForBind(checkedArgs),
            CORE_API_LEVEL,
        )
    }

    final override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle?): Int {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("removeUserService", caller)

        return core.removeUserService(
            caller,
            if (conn == null) null else PorterServiceConnection(conn),
            PorterUserServiceOptions.decodeForRemove(args ?: throw NullPointerException("args is null")),
        )
    }

    final override fun requestPermission(requestCode: Int) {
        core.requestPermission(CallerIdentity.fromBinder(), requestCode)
    }

    final override fun checkSelfPermission(): Boolean = core.checkSelfPermission(CallerIdentity.fromBinder())

    final override fun shouldShowRequestPermissionRationale(): Boolean =
        core.shouldShowRequestPermissionRationale(CallerIdentity.fromBinder())

    final override fun exit() {
        core.enforceManagerPermission("exit", CallerIdentity.fromBinder())
        managerOperations.exit()
    }

    final override fun attachUserService(binder: IBinder?, args: Bundle?) {
        core.enforceManagerPermission("attachUserService", CallerIdentity.fromBinder())
        val token = (args ?: throw NullPointerException("args is null")).getString(USER_SERVICE_TOKEN)
            ?: throw NullPointerException("token is null")
        managerOperations.attachUserService(binder ?: throw NullPointerException("binder is null"), token)
    }

    final override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) {
        core.enforceManagerPermission("dispatchPermissionConfirmationResult", CallerIdentity.fromBinder())

        if (data == null) {
            return
        }

        managerOperations.dispatchPermissionConfirmationResult(
            requestUid,
            requestPid,
            requestCode,
            data.getBoolean(PERMISSION_CONFIRMATION_ALLOWED, false),
            data.getBoolean(PERMISSION_CONFIRMATION_ONETIME, false),
        )
    }

    final override fun getFlagsForUid(uid: Int, mask: Int): Int {
        core.enforceManagerPermission("getFlagsForUid", CallerIdentity.fromBinder())
        return managerOperations.getFlagsForUid(uid, mask)
    }

    final override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        core.enforceManagerPermission("updateFlagsForUid", CallerIdentity.fromBinder())
        managerOperations.updateFlagsForUid(uid, mask, value)
    }

    @CallSuper
    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == PorterProtocol.TRANSACTION_transactRemote) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            core.transactRemote(CallerIdentity.fromBinder(), data, reply, flags)
            return true
        } else if (porshService.onTransact(code, data, reply, flags)) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private companion object {
        val LOGGER = Logger("PorterEndpoint")

        /**
         * The legacy API level whose semantics the neutral entry points implement. Porter records carry
         * it so `transactRemote` reads in-parcel flags and `addUserService` answers with
         * version codes.
         */
        const val CORE_API_LEVEL = ShizukuApiConstants.SERVER_VERSION
    }
}
