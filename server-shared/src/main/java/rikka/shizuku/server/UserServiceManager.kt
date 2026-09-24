package rikka.shizuku.server

import android.annotation.SuppressLint
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateUtils
import android.util.ArrayMap
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.HostProcess
import eu.darken.porter.core.UserServiceBindResult
import eu.darken.porter.core.UserServiceConnection
import eu.darken.porter.core.UserServiceOptions
import eu.darken.porter.core.UserServiceRemoveResult
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IShizukuServiceConnection
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.legacy.LegacyServiceConnection
import rikka.shizuku.server.legacy.LegacyUserServiceOptions
import rikka.shizuku.server.legacy.LegacyUserServiceResults
import rikka.shizuku.server.util.AbiUtil
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.UserHandleCompat

abstract class UserServiceManager {

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** Separate from [executor] so a cleanup cannot delay a service start. */
    private val cleanupExecutor: Executor = Executors.newSingleThreadExecutor()

    /**
     * Runs nothing but host kills: the main handler also makes calls into client apps, and one that
     * never returns must not hold a revoked host's kill back.
     */
    private val killer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val userServiceRecords: MutableMap<String, UserServiceRecord> = Collections.synchronizedMap(ArrayMap())
    private val packageUserServiceRecords: MutableMap<String, MutableList<UserServiceRecord>> =
        Collections.synchronizedMap(ArrayMap())

    fun ensureCallingPackageForUserService(packageName: String, appId: Int, userId: Int): PackageInfo {
        @SuppressLint("UnsafeOptInUsageError")
        val packageInfo = PackageManagerApis.getPackageInfoNoThrow(packageName, userServiceLookupFlags(), userId)
        if (packageInfo == null || packageInfo.applicationInfo == null) {
            throw SecurityException("unable to find package $packageName")
        }

        if (UserHandleCompat.getAppId(packageInfo.applicationInfo!!.uid) != appId) {
            throw SecurityException("package $packageName is not owned by $appId")
        }
        return packageInfo
    }

    /** The Shizuku wire's entry point, answering in its integers. */
    fun removeUserService(conn: IShizukuServiceConnection?, options: Bundle): Int = LegacyUserServiceResults.encodeRemove(
        removeUserService(
            CallerIdentity.fromBinder(),
            if (conn == null) null else LegacyServiceConnection(conn),
            LegacyUserServiceOptions.decodeForRemove(options),
        ),
    )

    fun removeUserService(caller: CallerIdentity, conn: UserServiceConnection?, options: UserServiceOptions): UserServiceRemoveResult {
        val appId = caller.appId()
        val userId = caller.userId()

        val packageName = options.packageName()
        ensureCallingPackageForUserService(packageName, appId, userId)

        (options.className() ?: throw NullPointerException("class is null"))
        val key = options.key(userId)

        synchronized(this) {
            val record = getUserServiceRecordLocked(key) ?: return UserServiceRemoveResult.NoSuchRecord
            if (options.remove) {
                removeUserServiceLocked(record)
            } else {
                record.callbacks.unregister(conn)
            }
        }
        return UserServiceRemoveResult.Removed
    }

    /**
     * Detaches [record] from both indexes. Returns null when it was already detached, so a
     * second removal - binder death racing a start timeout, say - is a no-op rather than a second
     * `destroy`.
     */
    private fun detachUserServiceLocked(record: UserServiceRecord): UserServiceRecord? {
        val removed = userServiceRecords.values.remove(record)
        val it = packageUserServiceRecords.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.remove(record)
            if (entry.value.isEmpty()) it.remove()
        }
        if (!removed) return null
        record.markRemoved()
        onUserServiceRecordRemoved(record)
        return record
    }

    private fun removeUserServiceLocked(record: UserServiceRecord) {
        val detached = detachUserServiceLocked(record) ?: return
        // Armed before destroy() is even queued: the deadline must not wait on a remote that may never
        // answer, and it holds this record's host, never whatever claims the key next.
        detached.host?.let(::scheduleHostKill)
        // destroy() talks to the remote; off the monitor, so a wedged daemon cannot block every bind.
        cleanupExecutor.execute { detached.destroy() }
    }

    /** The Shizuku wire's entry point, answering in the integers of [callingApiVersion]. */
    fun addUserService(conn: IShizukuServiceConnection?, options: Bundle?, callingApiVersion: Int): Int {
        (conn ?: throw NullPointerException("connection is null"))
        (options ?: throw NullPointerException("options is null"))

        return LegacyUserServiceResults.encodeBind(
            addUserService(
                CallerIdentity.fromBinder(),
                LegacyServiceConnection(conn),
                LegacyUserServiceOptions.decodeForBind(options),
            ),
            callingApiVersion,
        )
    }

    /**
     * [stillPermitted] runs with this monitor held, before any record is touched, so it must not wait
     * on the client manager: revocation holds that monitor while it takes this one.
     */
    fun addUserService(
        caller: CallerIdentity,
        conn: UserServiceConnection,
        options: UserServiceOptions,
        stillPermitted: () -> Boolean = { true },
    ): UserServiceBindResult {
        val uid = caller.uid
        val appId = caller.appId()
        val userId = caller.userId()

        val packageName = (options.packageName() ?: throw NullPointerException("package is null"))
        val packageInfo = ensureCallingPackageForUserService(packageName, appId, userId)

        val className = (options.className() ?: throw NullPointerException("class is null"))
        (packageInfo.applicationInfo!!.sourceDir ?: throw NullPointerException("apk path is null"))

        val versionCode = options.versionCode
        val processNameSuffix = options.processNameSuffix
        val debug = options.debuggable
        val noCreate = options.noCreate
        val daemon = options.daemon
        val use32Bits = options.use32Bit
        val key = options.key(userId)

        synchronized(this) {
            if (!stillPermitted()) {
                throw SecurityException("Permission Denial: addUserService from pid=${caller.pid} was revoked")
            }
            var record = getUserServiceRecordLocked(key)
            // Before the branch: noCreate hands back the existing binder without ever reaching
            // createUserServiceRecordIfNeededLocked, so a check placed there would miss it. Package
            // name ownership alone is satisfied by whoever installed over the name last.
            if (record != null && !canReuseUserServiceRecord(record, packageInfo)) {
                LOGGER.w("Service record %s (%s) does not belong to the current installation of %s", key, record.logId, packageName)
                removeUserServiceLocked(record)
                record = null
            }
            if (noCreate) {
                if (record != null) {
                    record.callbacks.register(conn)

                    val service = record.service
                    if (service != null && service.pingBinder()) {
                        record.broadcastBinderReceived()
                        return UserServiceBindResult.Running(record.versionCode)
                    }
                }

                return UserServiceBindResult.NotRunning
            } else {
                val newRecord = createUserServiceRecordIfNeededLocked(record, key, versionCode, daemon, packageInfo)
                newRecord.callbacks.register(conn)

                val service = newRecord.service
                if (service != null && service.pingBinder()) {
                    newRecord.broadcastBinderReceived()
                } else if (!newRecord.starting) {
                    newRecord.setStartingTimeout(DateUtils.SECOND_IN_MILLIS * 30)

                    val runnable = Runnable {
                        startUserService(newRecord, key, newRecord.token, packageName, className, processNameSuffix, uid, use32Bits, debug)
                    }
                    executor.execute(runnable)
                    return UserServiceBindResult.Bound
                }
                return UserServiceBindResult.Bound
            }
        }
    }

    /**
     * Whether [record] may still be handed to a caller whose installation is described by
     * [packageInfo]. Called with the monitor held, before either hand-over path. The default
     * accepts every record; an implementation that records an identity compares it here.
     */
    open fun canReuseUserServiceRecord(record: UserServiceRecord, packageInfo: PackageInfo): Boolean = true

    private fun getUserServiceRecordLocked(key: String): UserServiceRecord? = userServiceRecords[key]

    private fun createUserServiceRecordIfNeededLocked(
        record: UserServiceRecord?,
        key: String,
        versionCode: Int,
        daemon: Boolean,
        packageInfo: PackageInfo,
    ): UserServiceRecord {
        if (record != null) {
            val service = record.service
            if (record.versionCode != versionCode) {
                LOGGER.v("Remove service record %s (%s) because version code not matched (old=%d, new=%d)", key, record.logId, record.versionCode, versionCode)
            } else if (!record.starting && (service == null || !service.pingBinder())) {
                LOGGER.v("Service in record %s (%s) is dead", key, record.logId)
            } else {
                LOGGER.i("Found existing service record %s (%s)", key, record.logId)

                if (record.daemon != daemon) {
                    record.daemon = daemon
                }
                return record
            }

            removeUserServiceLocked(record)
        }

        val created = object : UserServiceRecord(versionCode, daemon) {

            override fun removeSelf() {
                synchronized(this@UserServiceManager) {
                    removeUserServiceLocked(this)
                }
            }
        }

        created.ownerUid = packageInfo.applicationInfo!!.uid

        val packageName = packageInfo.packageName
        var list = packageUserServiceRecords[packageName]
        if (list == null) {
            list = Collections.synchronizedList(ArrayList<UserServiceRecord>())
            packageUserServiceRecords[packageName] = list
        }
        list.add(created)

        onUserServiceRecordCreated(created, packageInfo)

        userServiceRecords[key] = created
        LOGGER.i("New service record %s (%s): version=%d, daemon=%s, apk=%s", key, created.logId, versionCode, daemon.toString(), packageInfo.applicationInfo!!.sourceDir)
        return created
    }

    private fun startUserService(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String?,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean,
    ) {
        // The task waited on the start executor; whoever removed the record in the meantime wanted
        // the service gone, not started. A removal landing after the second guard still spawns.
        if (record.isRemoved) {
            LOGGER.v("Service record %s (%s) was removed before it could start", key, record.logId)
            return
        }

        LOGGER.v("Starting process for service record %s (%s)...", key, record.logId)

        val cmd = getUserServiceStartCmd(record, key, token, packageName, classname, processNameSuffix, callingUid, use32Bits && AbiUtil.has32Bit(), debug)
        val exitCode: Int
        try {
            if (record.isRemoved) {
                LOGGER.v("Service record %s (%s) was removed before it could start", key, record.logId)
                return
            }
            val process = Runtime.getRuntime().exec("sh")
            val os = process.outputStream
            os.write(cmd.toByteArray())
            os.flush()
            os.close()

            exitCode = process.waitFor()
        } catch (e: Throwable) {
            throw IllegalStateException(e.message)
        }
        if (exitCode != 0) {
            throw IllegalStateException("sh exited with $exitCode")
        }
    }

    abstract fun getUserServiceStartCmd(
        record: UserServiceRecord,
        key: String,
        token: String,
        packageName: String,
        classname: String,
        processNameSuffix: String?,
        callingUid: Int,
        use32Bits: Boolean,
        debug: Boolean,
    ): String

    private fun sendUserServiceLocked(binder: IBinder, token: String, interfaceDescriptor: String?) {
        val entry = userServiceRecords.entries.firstOrNull { it.value.token == token }
            ?: throw IllegalArgumentException("no service record for this token")

        val record = entry.value
        if (record.isRemoved) {
            throw IllegalArgumentException("service record ${record.logId} is removed")
        }
        // One host per record, attaching once: every host runs as the same uid, so a second attach
        // under a live token would let one app's host stand in for another app's service.
        if (record.service != null) {
            throw IllegalArgumentException("service record ${record.logId} already has a binder")
        }

        LOGGER.v("Received binder for service record %s", record.logId)

        record.setBinder(binder, interfaceDescriptor)
    }

    fun attachUserService(binder: IBinder?, options: Bundle) {
        (binder ?: throw NullPointerException("binder is null"))
        attachUserService(binder, options, getInterfaceDescriptor(binder))
    }

    fun attachUserService(binder: IBinder?, options: Bundle, interfaceDescriptor: String?) {
        (binder ?: throw NullPointerException("binder is null"))
        attachUserService(binder, LegacyUserServiceOptions.decodeToken(options), interfaceDescriptor)
    }

    fun attachUserService(binder: IBinder?, token: String, interfaceDescriptor: String?) {
        (binder ?: throw NullPointerException("binder is null"))

        synchronized(this) {
            sendUserServiceLocked(binder, token, interfaceDescriptor)
        }
    }

    /**
     * Admits the host process [pid] as the one launched for [token], before it loads the app's code.
     * The token must name a live record that is starting and has no binder, and the process must run
     * as [expectedUid]. The first process to claim a record keeps it: a repeat from that same process
     * is admitted again, any other is refused, and so is a process whose identity cannot be read.
     */
    fun claimUserServiceLaunch(token: String?, pid: Int, expectedUid: Int): Boolean {
        if (token == null) return false
        val host = captureHost(pid) ?: return false
        if (host.uid != expectedUid) return false
        synchronized(this) {
            val record = userServiceRecords.values.firstOrNull { it.token == token } ?: return false
            if (record.isRemoved || !record.starting || record.service != null) return false
            val claimed = record.host
            if (claimed != null) return claimed.sameAs(host)
            record.host = host
            return true
        }
    }

    protected open fun captureHost(pid: Int): HostProcess? = HostProcess.capture(pid)

    protected open fun scheduleHostKill(host: HostProcess) {
        killer.schedule({ host.killIfSame() }, HOST_KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS)
    }

    /** Whether a live, non-removed record carries [token]. */
    fun isUserServiceTokenLive(token: String?): Boolean {
        if (token == null) return false
        synchronized(this) {
            for (record in userServiceRecords.values) {
                if (token == record.token) {
                    return !record.isRemoved
                }
            }
        }
        return false
    }

    open fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
    }

    open fun onUserServiceRecordRemoved(record: UserServiceRecord) {
    }

    fun removeUserServicesForPackage(packageName: String) {
        val snapshot: List<UserServiceRecord>
        synchronized(this) {
            val list = packageUserServiceRecords[packageName] ?: return
            // removeSelf() prunes this very list, so iterating it directly invalidates the iterator.
            snapshot = ArrayList(list)
        }
        for (record in snapshot) {
            record.removeSelf()
            LOGGER.i("Remove user service %s for package %s", record.logId, packageName)
        }
    }

    /**
     * Removes the records created for [uid] alone, where [removeUserServicesForPackage] reaches the
     * package in every user.
     */
    fun removeUserServicesForUid(uid: Int) {
        val snapshot: List<UserServiceRecord>
        synchronized(this) {
            snapshot = userServiceRecords.values.filter { it.ownerUid == uid }
        }
        for (record in snapshot) {
            record.removeSelf()
            LOGGER.i("Remove user service %s for uid %d", record.logId, uid)
        }
    }

    companion object {

        protected val LOGGER = Logger("UserServiceManager")

        /** How long a removed record's host gets to act on destroy() before it is killed. */
        const val HOST_KILL_GRACE_MILLIS = 3000L

        /**
         * The signing flags ride along with the authorising lookup so that whoever records or compares a
         * binder's identity never needs a second call to the package manager under the monitor.
         */
        @Suppress("DEPRECATION")
        private fun userServiceLookupFlags(): Long {
            val flags = 0x00002000L /*PackageManager.MATCH_UNINSTALLED_PACKAGES*/
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return flags or PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            }
            return flags or PackageManager.GET_SIGNATURES.toLong()
        }

        /**
         * Must be called with no monitor held: this is a synchronous binder round trip, and Binder has
         * no client-side timeout.
         */
        fun getInterfaceDescriptor(binder: IBinder?): String? {
            if (binder == null) return null
            return try {
                binder.interfaceDescriptor
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getInterfaceDescriptor")
                null
            }
        }
    }
}
