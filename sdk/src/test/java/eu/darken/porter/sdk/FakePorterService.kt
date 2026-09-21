package eu.darken.porter.sdk

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterRemoteProcess
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection

/** A local stub standing in for the server: records what it was asked and answers what it was told. */
internal open class FakePorterService : IPorterService.Stub() {

    var attachReply: Bundle = Bundle()
    var attachFailure: RuntimeException? = null
    var attachCount = 0
    var attachArgs: Bundle? = null
    var application: IPorterApplication? = null

    /** Pushed to the client from inside a call, before that call answers. */
    var attachTimePermissionPush: Bundle? = null
    var checkTimePermissionPush: Bundle? = null

    /** Unknown until a test says otherwise, so a value kept from a previous reply stands out. */
    var serverUid = -1
    var seLinuxContext: String? = null
    var selfPermission = false
    var selfPermissionQueries = 0
    var requestedPermissionCode = -1
    var userServiceResult = 0
    var userServiceArgs: Bundle? = null
    var userServiceConnection: IPorterServiceConnection? = null
    var removedUserServiceConnection: IPorterServiceConnection? = null
    var userServiceRemoves = 0

    var exitCalls = 0
    var attachedUserServiceBinder: IBinder? = null
    var attachedUserServiceArgs: Bundle? = null
    var confirmationUid = -1
    var confirmationPid = -1
    var confirmationRequestCode = -1
    var confirmationData: Bundle? = null
    var flags = 0
    var flagsUid = -1
    var flagsMask = -1
    var flagsValue = -1

    override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
        attachCount++
        this.application = application
        this.attachArgs = args
        attachFailure?.let { throw it }
        attachTimePermissionPush?.let { pushPermissionState(it) }
        return attachReply
    }

    fun pushPermissionState(state: Bundle) {
        application!!.dispatchPermissionStateChanged(state)
    }

    fun pushRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        val data = Bundle()
        data.putBoolean(eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED, allowed)
        application!!.dispatchRequestPermissionResult(requestCode, data)
    }

    override fun getUid(): Int = serverUid

    override fun checkPermission(permission: String): Int = PackageManager.PERMISSION_DENIED

    override fun getSELinuxContext(): String? = seLinuxContext

    override fun getSystemProperty(name: String, defaultValue: String?): String? = defaultValue

    override fun setSystemProperty(name: String, value: String) {
    }

    override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IPorterRemoteProcess? = null

    open override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
        userServiceArgs = args
        userServiceConnection = conn
        return userServiceResult
    }

    open override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
        userServiceArgs = args
        removedUserServiceConnection = conn
        userServiceRemoves++
        return 0
    }

    override fun requestPermission(requestCode: Int) {
        requestedPermissionCode = requestCode
    }

    override fun checkSelfPermission(): Boolean {
        selfPermissionQueries++
        checkTimePermissionPush?.let {
            checkTimePermissionPush = null
            pushPermissionState(it)
        }
        return selfPermission
    }

    override fun shouldShowRequestPermissionRationale(): Boolean = false

    override fun exit() {
        exitCalls++
    }

    override fun attachUserService(binder: IBinder, args: Bundle) {
        attachedUserServiceBinder = binder
        attachedUserServiceArgs = args
    }

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
}
