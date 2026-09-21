package eu.darken.porter.sdk

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import java.util.Collections
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection

/**
 * A local stub standing in for a Shizuku server: records what it was asked and answers what it was
 * told. The generated dispatch does the decoding, so what the wire wrote has to be what the real
 * server would have read.
 */
internal open class FakeShizukuService : IShizukuService.Stub() {

    /** Every transaction code, in the order it arrived. */
    val codes: MutableList<Int> = Collections.synchronizedList(ArrayList())

    var lastTransactFlags = 0
    var lastTransactExpectedReply = false

    /** Sent from inside attachApplication, as the real server sends it. */
    var bindApplicationReply: Bundle = replyWithVersion(ShizukuProtocol.MINIMUM_VERSION)
    var suppressBindApplication = false

    /** Sends the reply from another thread after this delay instead, when above zero. */
    var deferBindApplicationMs = 0L

    var attachCount = 0
    var attachArgs: Bundle? = null
    var application: IShizukuApplication? = null

    var serverVersion = ShizukuProtocol.MINIMUM_VERSION

    /** Unknown until a test says otherwise, so a value kept from a previous reply stands out. */
    var serverUid = -1
    var seLinuxContext: String? = null
    var checkedPermission: String? = null
    var remotePermission = PackageManager.PERMISSION_DENIED
    var systemProperty: String? = null
    var queriedPropertyName: String? = null
    var queriedPropertyDefault: String? = null
    var setPropertyName: String? = null
    var setPropertyValue: String? = null
    var requestedPermissionCode = -1
    var selfPermission = false
    var rationale = false

    /** What one add or remove carried, read the way the server reads it. */
    class UserServiceCall(val connection: IBinder?, val args: Bundle)

    val userServiceAdds: MutableList<UserServiceCall> = Collections.synchronizedList(ArrayList())
    val userServiceRemoves: MutableList<UserServiceCall> = Collections.synchronizedList(ArrayList())
    var addUserServiceResult = 0
    var removeUserServiceResult = 0

    /** The connection of the newest add, which is what the user service pushes go to. */
    private var userServiceConnection: IShizukuServiceConnection? = null

    var exitCalls = 0
    var attachedUserServiceBinder: IBinder? = null
    var attachedUserServiceOptions: Bundle? = null
    var confirmationUid = -1
    var confirmationPid = -1
    var confirmationRequestCode = -1
    var confirmationData: Bundle? = null
    var flags = 0
    var flagsUid = -1
    var flagsMask = -1
    var flagsValue = -1

    /** What a forwarded transaction carried, read the way the server reads it. */
    var forwardedTarget: IBinder? = null
    var forwardedCode = -1
    var forwardedFlags = -1
    var forwardedPayload = -1

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        codes.add(code)
        lastTransactFlags = flags
        lastTransactExpectedReply = reply != null

        if (code == ShizukuProtocol.TRANSACTION_transactRemote) {
            // Outside the AIDL, so the generated dispatch would enforce the token and then refuse it.
            data.enforceInterface(ShizukuProtocol.DESCRIPTOR)
            forwardedTarget = data.readStrongBinder()
            forwardedCode = data.readInt()
            forwardedFlags = data.readInt()
            forwardedPayload = data.readInt()
            return true
        }

        return super.onTransact(code, data, reply, flags)
    }

    override fun attachApplication(application: IShizukuApplication, args: Bundle) {
        attachCount++
        this.application = application
        this.attachArgs = args

        if (suppressBindApplication) return
        if (deferBindApplicationMs > 0) {
            replyFromAnotherThread(deferBindApplicationMs)
            return
        }
        pushBindApplication(bindApplicationReply)
    }

    private fun replyFromAnotherThread(delayMs: Long) {
        val thread = Thread {
            try {
                Thread.sleep(delayMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@Thread
            }
            pushBindApplication(bindApplicationReply)
        }
        thread.isDaemon = true
        thread.start()
    }

    fun pushBindApplication(state: Bundle?) {
        application!!.bindApplication(state)
    }

    fun pushRequestPermissionResult(requestCode: Int, result: Bundle?) {
        application!!.dispatchRequestPermissionResult(requestCode, result)
    }

    override fun getVersion(): Int = serverVersion

    override fun getUid(): Int = serverUid

    override fun checkPermission(permission: String): Int {
        checkedPermission = permission
        return remotePermission
    }

    override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IRemoteProcess? = null

    override fun getSELinuxContext(): String? = seLinuxContext

    override fun getSystemProperty(name: String, defaultValue: String?): String? {
        queriedPropertyName = name
        queriedPropertyDefault = defaultValue
        return systemProperty
    }

    override fun setSystemProperty(name: String, value: String) {
        setPropertyName = name
        setPropertyValue = value
    }

    override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle): Int {
        userServiceAdds.add(UserServiceCall(conn?.asBinder(), args))
        userServiceConnection = conn
        return addUserServiceResult
    }

    override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle): Int {
        userServiceRemoves.add(UserServiceCall(conn?.asBinder(), args))
        return removeUserServiceResult
    }

    fun pushUserServiceConnected(service: IBinder) {
        userServiceConnection!!.connected(service)
    }

    fun pushUserServiceDied() {
        userServiceConnection!!.died()
    }

    override fun requestPermission(requestCode: Int) {
        requestedPermissionCode = requestCode
    }

    override fun checkSelfPermission(): Boolean = selfPermission

    override fun shouldShowRequestPermissionRationale(): Boolean = rationale

    override fun exit() {
        exitCalls++
    }

    override fun attachUserService(binder: IBinder, options: Bundle) {
        attachedUserServiceBinder = binder
        attachedUserServiceOptions = options
    }

    override fun dispatchPackageChanged(intent: Intent?) {
    }

    override fun isHidden(uid: Int): Boolean = false

    override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle) {
        confirmationUid = requestUid
        confirmationPid = requestPid
        confirmationRequestCode = requestCode
        confirmationData = data
    }

    override fun getFlagsForUid(uid: Int, mask: Int): Int {
        flagsUid = uid
        flagsMask = mask
        return flags
    }

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
        flagsUid = uid
        flagsMask = mask
        flagsValue = value
    }

    companion object {
        fun replyWithVersion(version: Int): Bundle = Bundle().apply {
            putInt(ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION, version)
        }
    }
}
