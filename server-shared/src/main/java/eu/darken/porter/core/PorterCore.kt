package eu.darken.porter.core

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SELinux
import android.os.SystemProperties
import android.system.Os
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.function.IntFunction
import rikka.hidden.compat.PackageManagerApis
import rikka.hidden.compat.PermissionManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ClientRecord
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.OsUtils

/**
 * Every operation an endpoint answers with, and the managers it answers from. The caller is handed
 * in rather than read from Binder, so an operation is the same one whichever wire reached it.
 */
class PorterCore<UserServiceMgr : UserServiceManager, ClientMgr : ClientManager<ConfigMgr>, ConfigMgr : ConfigManager>(
    userServiceManager: UserServiceMgr?,
    clientManager: ClientMgr?,
    configManager: ConfigMgr?,
    policy: ServerPolicy?,
    packagesForUid: IntFunction<List<String>>? = IntFunction { uid -> PackageManagerApis.getPackagesForUidNoThrow(uid) },
) {

    val userServiceManager: UserServiceMgr = (userServiceManager ?: throw NullPointerException("user service manager is null"))
    val clientManager: ClientMgr = (clientManager ?: throw NullPointerException("client manager is null"))
    val configManager: ConfigMgr = (configManager ?: throw NullPointerException("config manager is null"))
    val policy: ServerPolicy = (policy ?: throw NullPointerException("policy is null"))
    private val packagesForUid: IntFunction<List<String>> = (packagesForUid ?: throw NullPointerException("package lookup is null"))

    fun enforceCallingPermission(func: String, caller: CallerIdentity) {
        val clientRecord = clientManager.findClient(caller.uid, caller.pid)

        if (policy.checkCallerPermission(func, caller, clientRecord)) {
            return
        }

        if (clientRecord == null) {
            val msg = "Permission Denial: " + func + " from pid=" + caller.pid + " is not an attached client"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }

        // The server's own uid is attached like anyone else, but a grant cannot add anything to
        // what that identity already is, so it is not asked for one.
        if (!clientRecord.allowed && caller.uid != OsUtils.uid) {
            val msg = "Permission Denial: " + func + " from pid=" + caller.pid + " requires permission"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }
    }

    fun enforceManagerPermission(func: String, caller: CallerIdentity) {
        if (caller.pid == Os.getpid()) {
            return
        }

        if (policy.checkCallerManagerPermission(func, caller)) {
            return
        }

        val msg = "Permission Denial: " + func + " from pid=" + caller.pid + " is not manager "
        LOGGER.w(msg)
        throw SecurityException(msg)
    }

    /**
     * Finds or creates the caller's record. A process is attached through one endpoint only: a
     * second endpoint reaching an existing record is refused rather than given a second record.
     */
    fun attach(caller: CallerIdentity, packageName: String, callback: ClientCallback, apiLevel: Int): AttachResult {
        // Never null: getPackagesForUidNoThrow answers with an empty list when it cannot look up.
        if (!packagesForUid.apply(caller.uid).contains(packageName)) {
            throw SecurityException("Request package " + packageName + " does not belong to uid " + caller.uid)
        }

        policy.onAttaching(caller, packageName)

        // One critical section: a second attach from the same process must find what the first left.
        synchronized(clientManager) {
            var record = clientManager.findClient(caller.uid, caller.pid)
            if (record == null) {
                record = clientManager.attach(caller, callback, packageName, apiLevel)
                if (record == null) {
                    throw IllegalStateException("client binder is dead")
                }
                return AttachResult(record, true)
            }
            if (record.callback.javaClass != callback.javaClass) {
                throw IllegalStateException(
                    "uid " + caller.uid + "/pid " + caller.pid + " is attached through another endpoint",
                )
            }
            if (record.packageName != packageName) {
                throw SecurityException(
                    "uid " + caller.uid + "/pid " + caller.pid + " is attached as " + record.packageName,
                )
            }
            return AttachResult(record, false)
        }
    }

    /**
     * Forwards one transaction to [targetBinder] as the caller wrote it. The endpoint has already
     * admitted the caller and read the target, code and flags in its own wire's layout; what is
     * left in [data] is the payload.
     */
    @Throws(RemoteException::class)
    fun transactRemote(
        caller: CallerIdentity,
        targetBinder: IBinder,
        targetCode: Int,
        targetFlags: Int,
        data: Parcel,
        reply: Parcel?,
    ) {
        // A binder of this very process arrives as the local object, and a call on it would run
        // with the server's own identity once the caller's is cleared below: the endpoints, whose
        // manager gate admits that identity, are exactly what a client must not reach this way.
        if (targetBinder is Binder) {
            val msg = "Permission Denial: transactRemote from pid=" + caller.pid + " targets a binder of the server process"
            LOGGER.w(msg)
            throw SecurityException(msg)
        }
        if (Logger.debugEnabled()) {
            // Best effort: a descriptor lookup that fails must not stop the caller's transaction
            // from being forwarded, so diagnostics can never change what a client observes.
            val descriptor = try {
                targetBinder.interfaceDescriptor
            } catch (tr: Throwable) {
                "<unavailable>"
            }
            LOGGER.d("transact: uid=%d, descriptor=%s, code=%d", caller.uid, descriptor, targetCode)
        }
        val newData = Parcel.obtain()
        try {
            newData.appendFrom(data, data.dataPosition(), data.dataAvail())
        } catch (tr: Throwable) {
            LOGGER.w(tr, "appendFrom")
            return
        }
        try {
            val id = Binder.clearCallingIdentity()
            try {
                targetBinder.transact(targetCode, newData, reply, targetFlags)
            } finally {
                Binder.restoreCallingIdentity(id)
            }
        } finally {
            newData.recycle()
        }
    }

    fun getUid(caller: CallerIdentity): Int {
        enforceCallingPermission("getUid", caller)
        return Os.getuid()
    }

    @Throws(RemoteException::class)
    fun checkPermission(caller: CallerIdentity, permission: String): Int {
        enforceCallingPermission("checkPermission", caller)
        return PermissionManagerApis.checkPermission(permission, Os.getuid())
    }

    fun getSELinuxContext(caller: CallerIdentity): String? {
        enforceCallingPermission("getSELinuxContext", caller)

        try {
            return SELinux.getContext()
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    fun getSystemProperty(caller: CallerIdentity, name: String, defaultValue: String?): String? {
        enforceCallingPermission("getSystemProperty", caller)

        try {
            return SystemProperties.get(name, defaultValue)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    /** A null [value] clears the property, as the platform reads it. */
    fun setSystemProperty(caller: CallerIdentity, name: String, value: String?) {
        enforceCallingPermission("setSystemProperty", caller)

        try {
            SystemProperties.set(name, value)
        } catch (tr: Throwable) {
            throw IllegalStateException(tr.message)
        }
    }

    fun newServerProcess(caller: CallerIdentity, cmd: Array<String>, env: Array<String>?, dir: String?): ServerProcess {
        enforceCallingPermission("newProcess", caller)

        if (Logger.debugEnabled()) {
            LOGGER.d(
                "newProcess: uid=%d, cmd=%s, env=%s, dir=%s", caller.uid,
                Arrays.toString(cmd), Arrays.toString(env), dir,
            )
        }

        val process = try {
            Runtime.getRuntime().exec(cmd, env, if (dir != null) File(dir) else null)
        } catch (e: IOException) {
            throw IllegalStateException(e.message)
        }

        val clientRecord = clientManager.findClient(caller.uid, caller.pid)
        val token = clientRecord?.callback?.asBinder()

        return ServerProcess(process, token)
    }

    fun addUserService(caller: CallerIdentity, conn: UserServiceConnection?, options: UserServiceOptions?): UserServiceBindResult {
        enforceCallingPermission("addUserService", caller)

        val connection: UserServiceConnection = (conn ?: throw NullPointerException("connection is null"))
        val checkedOptions: UserServiceOptions = (options ?: throw NullPointerException("options is null"))

        return userServiceManager.addUserService(caller, connection, checkedOptions)
    }

    fun removeUserService(caller: CallerIdentity, conn: UserServiceConnection?, options: UserServiceOptions): UserServiceRemoveResult {
        enforceCallingPermission("removeUserService", caller)

        return userServiceManager.removeUserService(caller, conn, options)
    }

    /** No gate of its own: the endpoints enforce the manager permission before they reach this. */
    fun attachUserService(binder: IBinder, token: String, interfaceDescriptor: String?) {
        userServiceManager.attachUserService(binder, token, interfaceDescriptor)
    }

    fun checkSelfPermission(caller: CallerIdentity): Boolean {
        if (caller.uid == OsUtils.uid || caller.pid == OsUtils.pid) {
            return true
        }

        return clientManager.requireClient(caller.uid, caller.pid).allowed
    }

    fun requestPermission(caller: CallerIdentity, requestCode: Int) {
        val userId = caller.userId()

        if (caller.uid == OsUtils.uid || caller.pid == OsUtils.pid) {
            return
        }

        val clientRecord = clientManager.requireClient(caller.uid, caller.pid)

        if (clientRecord.allowed) {
            clientRecord.dispatchRequestPermissionResult(requestCode, true)
            return
        }

        val entry = configManager.find(caller.uid)
        if (entry != null && entry.isDenied()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false)
            return
        }

        policy.showPermissionConfirmation(requestCode, clientRecord, caller, userId)
    }

    fun shouldShowRequestPermissionRationale(caller: CallerIdentity): Boolean {
        if (caller.uid == OsUtils.uid || caller.pid == OsUtils.pid) {
            return true
        }

        clientManager.requireClient(caller.uid, caller.pid)

        val entry = configManager.find(caller.uid)
        return entry != null && entry.isDenied()
    }

    /** The API level a legacy caller is answered at: its own, or the current one when unrecorded. */
    fun legacyApiLevelOf(caller: CallerIdentity): Int {
        val clientRecord = clientManager.findClient(caller.uid, caller.pid)
        return clientRecord?.apiVersion ?: ShizukuApiConstants.SERVER_VERSION
    }

    private companion object {
        val LOGGER = Logger("PorterCore")
    }
}
