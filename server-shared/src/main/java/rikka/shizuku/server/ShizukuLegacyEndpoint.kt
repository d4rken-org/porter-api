package rikka.shizuku.server

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import androidx.annotation.CallSuper
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.core.PorterCore
import eu.darken.porter.core.confineToFramework
import eu.darken.porter.porsh.PorshService
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION
import rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION
import rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED
import rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME
import rikka.shizuku.server.api.RemoteProcessHolder
import rikka.shizuku.server.legacy.LegacyCallerExemption
import rikka.shizuku.server.legacy.LegacyClientCallback
import rikka.shizuku.server.legacy.LegacyServiceConnection
import rikka.shizuku.server.legacy.LegacyUserServiceOptions
import rikka.shizuku.server.legacy.LegacyUserServiceResults
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils

/**
 * The Shizuku wire, answered by the same core the Porter endpoint answers from. The server
 * bootstrap must call `PorshConfig.init(BINDER_DESCRIPTOR, 30000)` before serving terminal
 * transactions; this class does not load the native library.
 */
open class ShizukuLegacyEndpoint(
    private val core: PorterCore<*, *, *>,
    private val managerOperations: ManagerOperations,
) : IShizukuService.Stub() {

    private val porshService: PorshService = object : PorshService(
        ShizukuApiConstants.BINDER_DESCRIPTOR, LEGACY_PORSH_TRANSACTION_BASE,
    ) {

        override fun enforceCallingPermission(func: String) {
            core.enforceCallingPermission(func, CallerIdentity.fromBinder(), LegacyCallerExemption)
        }
    }

    final override fun attachApplication(application: IShizukuApplication?, args: Bundle?) {
        if (application == null || args == null) {
            return
        }
        confineToFramework(args)

        val packageName = args.getString(ATTACH_APPLICATION_PACKAGE_NAME) ?: return
        val apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1)

        val caller = CallerIdentity.fromBinder()
        val result = core.attach(caller, packageName, LegacyClientCallback(application), apiVersion)

        val reply = Bundle()
        reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.uid)
        reply.putInt(
            BIND_APPLICATION_SERVER_VERSION,
            if (apiVersion == -1) LEGACY_SERVER_VERSION else ShizukuApiConstants.SERVER_VERSION,
        )
        reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.seLinuxContext)
        reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION)
        reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, result.record.allowed)
        reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)

        core.policy.onAttached(result.record, result.created, reply)

        try {
            application.bindApplication(reply)
            core.policy.onBound(result.record, result.created)
        } catch (e: Throwable) {
            LOGGER.w(e, "attachApplication")
        }
    }

    final override fun getVersion(): Int {
        core.enforceCallingPermission("getVersion", CallerIdentity.fromBinder(), LegacyCallerExemption)
        return ShizukuApiConstants.SERVER_VERSION
    }

    final override fun getUid(): Int = core.getUid(CallerIdentity.fromBinder(), LegacyCallerExemption)

    @Throws(RemoteException::class)
    final override fun checkPermission(permission: String?): Int {
        // The gate answers before any argument is read, so an unauthorized caller is refused for
        // being unauthorized whatever it sent.
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("checkPermission", caller, LegacyCallerExemption)
        return core.checkPermission(caller, LegacyCallerExemption, permission ?: throw NullPointerException("permission is null"))
    }

    final override fun getSELinuxContext(): String? = core.getSELinuxContext(CallerIdentity.fromBinder(), LegacyCallerExemption)

    final override fun getSystemProperty(name: String?, defaultValue: String?): String? {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("getSystemProperty", caller, LegacyCallerExemption)
        return core.getSystemProperty(caller, LegacyCallerExemption, name ?: throw NullPointerException("name is null"), defaultValue)
    }

    final override fun setSystemProperty(name: String?, value: String?) {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("setSystemProperty", caller, LegacyCallerExemption)
        core.setSystemProperty(caller, LegacyCallerExemption, name ?: throw NullPointerException("name is null"), value)
    }

    final override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IRemoteProcess {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("newProcess", caller, LegacyCallerExemption)
        return RemoteProcessHolder(
            core.newServerProcess(caller, LegacyCallerExemption, cmd ?: throw NullPointerException("cmd is null"), env, dir),
        )
    }

    final override fun addUserService(conn: IShizukuServiceConnection?, options: Bundle?): Int {
        val caller = CallerIdentity.fromBinder()
        // Before any Bundle content is read: an unauthorized caller is refused, never told what
        // their options decoded to.
        core.enforceCallingPermission("addUserService", caller, LegacyCallerExemption)

        (conn ?: throw NullPointerException("connection is null"))
        (options ?: throw NullPointerException("options is null"))

        return LegacyUserServiceResults.encodeBind(
            core.addUserService(
                caller,
                LegacyCallerExemption,
                LegacyServiceConnection(conn),
                LegacyUserServiceOptions.decodeForBind(options),
            ),
            core.legacyApiLevelOf(caller),
        )
    }

    final override fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle?): Int {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("removeUserService", caller, LegacyCallerExemption)

        return LegacyUserServiceResults.encodeRemove(
            core.removeUserService(
                caller,
                LegacyCallerExemption,
                if (conn == null) null else LegacyServiceConnection(conn),
                LegacyUserServiceOptions.decodeForRemove(options ?: throw NullPointerException("options is null")),
            ),
        )
    }

    final override fun checkSelfPermission(): Boolean =
        core.checkSelfPermission(CallerIdentity.fromBinder(), LegacyCallerExemption)

    final override fun requestPermission(requestCode: Int) {
        core.requestPermission(CallerIdentity.fromBinder(), requestCode, LegacyCallerExemption)
    }

    final override fun shouldShowRequestPermissionRationale(): Boolean =
        core.shouldShowRequestPermissionRationale(CallerIdentity.fromBinder(), LegacyCallerExemption)

    final override fun exit() {
        core.enforceManagerPermission("exit", CallerIdentity.fromBinder())
        managerOperations.exit()
    }

    final override fun attachUserService(binder: IBinder?, options: Bundle?) {
        core.enforceManagerPermission("attachUserService", CallerIdentity.fromBinder())
        val token = LegacyUserServiceOptions.decodeToken(options ?: throw NullPointerException("options is null"))
        managerOperations.attachUserService(binder ?: throw NullPointerException("binder is null"), token)
    }

    final override fun dispatchPermissionConfirmationResult(
        requestUid: Int,
        requestPid: Int,
        requestCode: Int,
        data: Bundle?,
    ) {
        core.enforceManagerPermission("dispatchPermissionConfirmationResult", CallerIdentity.fromBinder())

        if (data == null) {
            return
        }

        managerOperations.dispatchPermissionConfirmationResult(
            requestUid,
            requestPid,
            requestCode,
            data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false),
            data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, false),
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

    final override fun dispatchPackageChanged(intent: Intent?) {
    }

    final override fun isHidden(uid: Int): Boolean = false

    @CallSuper
    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val caller = CallerIdentity.fromBinder()
            core.enforceCallingPermission("transactRemote", caller, LegacyCallerExemption)
            val targetBinder = data.readStrongBinder()
            val targetCode = data.readInt()
            // A recorded v13 client writes the flags into the parcel; anyone else is answered
            // with the outer call's flags.
            val record = core.clientManager.findClient(caller.uid, caller.pid)
            val targetFlags = if (record != null && record.apiVersion >= 13) data.readInt() else flags
            core.transactRemote(caller, targetBinder, targetCode, targetFlags, data, reply)
            return true
        } else if (code == 14 /* attachApplication <= v12 */) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
            val binder = data.readStrongBinder()
            val packageName = data.readString()
            val args = Bundle()
            args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName)
            args.putInt(ATTACH_APPLICATION_API_VERSION, -1)
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args)
            reply?.writeNoException()
            return true
        } else if (porshService.onTransact(code, data, reply, flags)) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    companion object {

        protected val LOGGER = Logger("ShizukuLegacyEndpoint")

        private const val LEGACY_PORSH_TRANSACTION_BASE = 30000

        /** What a client that attached before v13 is told the server is. */
        private const val LEGACY_SERVER_VERSION = 12
    }
}
