package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.Parcel
import android.os.RemoteException
import androidx.annotation.CallSuper
import eu.darken.porter.core.CallerExemption
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.PorterCore
import eu.darken.porter.core.UserServiceBindResult
import eu.darken.porter.core.UserServiceRemoveResult
import eu.darken.porter.core.confineToFramework
import eu.darken.porter.porsh.PorshService
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES
import eu.darken.porter.protocol.PorterProtocol.REPLY_MIN_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.protocol.PorterProtocol.REPLY_UNSUPPORTED
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_RESULT_BOUND
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_RESULT_NOT_RUNNING
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_RESULT_NO_SUCH_SERVICE
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils

/**
 * Porter's own client endpoint. Every operation is answered by the same core the Shizuku endpoint
 * answers from, so the two wires cannot drift apart in what they permit or record.
 */
open class PorterEndpoint(private val core: PorterCore<*, *, *>) : IPorterService.Stub() {

    private val porshService: PorshService =
        object : PorshService(PorterProtocol.DESCRIPTOR, PorterProtocol.TRANSACTION_PORSH_BASE) {

            override fun enforceCallingPermission(func: String) {
                core.enforceCallingPermission(func, CallerIdentity.fromBinder(), CallerExemption.None)
            }
        }

    override fun attach(application: IPorterApplication?, args: Bundle?): Bundle {
        val app: IPorterApplication = (application ?: throw NullPointerException("application is null"))
        val attachArgs: Bundle = confineToFramework(args ?: throw NullPointerException("args is null"))

        val packageName: String = (attachArgs.getString(ATTACH_PACKAGE_NAME) ?: throw NullPointerException("package name is null"))
        val caller = CallerIdentity.fromBinder()
        val version = attachArgs.getInt(ATTACH_PROTOCOL_VERSION, 0)

        LOGGER.d("attach: uid=%d, pid=%d, package=%s, protocol=%d", caller.uid, caller.pid, packageName, version)

        // Decided before the core hears of the caller: a refused attach leaves no record and
        // reaches no policy hook, so nothing is created that a compatible attach would have to
        // find and reconcile later.
        if (version < PorterProtocol.MIN_VERSION) {
            LOGGER.w("attach: refusing protocol %d from %s, the floor is %d", version, packageName, PorterProtocol.MIN_VERSION)
            val reply = Bundle()
            reply.putBoolean(REPLY_UNSUPPORTED, true)
            reply.putInt(REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION)
            reply.putInt(REPLY_MIN_PROTOCOL_VERSION, PorterProtocol.MIN_VERSION)
            return reply
        }

        val result = core.attach(caller, packageName, PorterClientCallback(app), version)
        val entry = core.configManager.find(caller.uid)

        val reply = Bundle()
        reply.putInt(REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION)
        reply.putInt(REPLY_MIN_PROTOCOL_VERSION, PorterProtocol.MIN_VERSION)
        reply.putInt(REPLY_SERVER_UID, OsUtils.uid)
        reply.putString(REPLY_SERVER_SECONTEXT, OsUtils.seLinuxContext)
        reply.putBoolean(REPLY_PERMISSION_GRANTED, result.record.allowed)
        reply.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, entry != null && entry.isDenied())
        reply.putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE)

        core.policy.onAttached(result.record, result.created, reply)
        // The synchronous reply is the delivery: returning it is what a bindApplication call is on
        // the Shizuku wire.
        core.policy.onBound(result.record, result.created)
        return reply
    }

    final override fun getUid(): Int = core.getUid(CallerIdentity.fromBinder(), CallerExemption.None)

    @Throws(RemoteException::class)
    final override fun checkPermission(permission: String?): Int {
        // The gate answers before any argument is read, so an unauthorized caller is refused for
        // being unauthorized whatever it sent.
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("checkPermission", caller, CallerExemption.None)
        return core.checkPermission(caller, CallerExemption.None, permission ?: throw NullPointerException("permission is null"))
    }

    final override fun getSELinuxContext(): String? = core.getSELinuxContext(CallerIdentity.fromBinder(), CallerExemption.None)

    final override fun getSystemProperty(name: String?, defaultValue: String?): String? {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("getSystemProperty", caller, CallerExemption.None)
        return core.getSystemProperty(caller, CallerExemption.None, name ?: throw NullPointerException("name is null"), defaultValue)
    }

    final override fun setSystemProperty(name: String?, value: String?) {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("setSystemProperty", caller, CallerExemption.None)
        core.setSystemProperty(caller, CallerExemption.None, name ?: throw NullPointerException("name is null"), value)
    }

    final override fun addUserService(conn: IPorterServiceConnection?, args: Bundle?): Int {
        val caller = CallerIdentity.fromBinder()
        // Before any Bundle content is read: an unauthorized caller is refused, never told what
        // their arguments decoded to.
        core.enforceCallingPermission("addUserService", caller, CallerExemption.None)

        val connection: IPorterServiceConnection = (conn ?: throw NullPointerException("connection is null"))
        val checkedArgs: Bundle = (args ?: throw NullPointerException("args is null"))

        val result = core.addUserService(
            caller,
            CallerExemption.None,
            PorterServiceConnection(connection),
            PorterUserServiceOptions.decodeForBind(checkedArgs),
        )
        return when (result) {
            UserServiceBindResult.Bound -> USER_SERVICE_RESULT_BOUND
            is UserServiceBindResult.Running -> result.versionCode
            UserServiceBindResult.NotRunning -> USER_SERVICE_RESULT_NOT_RUNNING
        }
    }

    final override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle?): Int {
        val caller = CallerIdentity.fromBinder()
        core.enforceCallingPermission("removeUserService", caller, CallerExemption.None)

        val options = PorterUserServiceOptions.decodeForRemove(args ?: throw NullPointerException("args is null"))
        // Unregistering names the connection to unregister; only a removal can leave it out.
        if (conn == null && !options.remove) {
            throw IllegalArgumentException("connection is null and the service is not being removed")
        }

        val result = core.removeUserService(
            caller,
            CallerExemption.None,
            if (conn == null) null else PorterServiceConnection(conn),
            options,
        )
        return when (result) {
            UserServiceRemoveResult.Removed -> 0
            UserServiceRemoveResult.NoSuchRecord -> USER_SERVICE_RESULT_NO_SUCH_SERVICE
        }
    }

    final override fun requestPermission(requestCode: Int) {
        core.requestPermission(CallerIdentity.fromBinder(), requestCode, CallerExemption.None)
    }

    final override fun checkSelfPermission(): Boolean =
        core.checkSelfPermission(CallerIdentity.fromBinder(), CallerExemption.None)

    final override fun shouldShowRequestPermissionRationale(): Boolean =
        core.shouldShowRequestPermissionRationale(CallerIdentity.fromBinder(), CallerExemption.None)

    @CallSuper
    @Throws(RemoteException::class)
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == PorterProtocol.TRANSACTION_transactRemote) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            val caller = CallerIdentity.fromBinder()
            core.enforceCallingPermission("transactRemote", caller, CallerExemption.None)
            // The Porter client always writes the flags into the parcel, so the outer call's flags
            // never reach the target.
            val targetBinder = data.readStrongBinder()
            val targetCode = data.readInt()
            val targetFlags = data.readInt()
            core.transactRemote(caller, targetBinder, targetCode, targetFlags, data, reply)
            return true
        } else if (porshService.onTransact(code, data, reply, flags)) {
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    private companion object {
        val LOGGER = Logger("PorterEndpoint")
    }
}
