package eu.darken.porter.sdk

import android.content.pm.PackageManager
import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.REPLY_MIN_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection

/** A local stub standing in for the server: records what it was asked and answers what it was told. */
internal open class FakePorterService : IPorterService.Stub() {

    /** The keys beyond the two versions; a test that wants no version at all clears those below. */
    var attachReply: Bundle = Bundle()

    /** What the reply says the server speaks and accepts; null leaves the key out. */
    var protocolVersion: Int? = PorterProtocol.VERSION
    var minProtocolVersion: Int? = PorterProtocol.MIN_VERSION

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
    @Volatile
    var userServiceRemoves = 0

    override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
        attachCount++
        this.application = application
        this.attachArgs = args
        attachFailure?.let { throw it }
        attachTimePermissionPush?.let { pushPermissionState(it) }
        val reply = Bundle(attachReply)
        protocolVersion?.let { if (!reply.containsKey(REPLY_PROTOCOL_VERSION)) reply.putInt(REPLY_PROTOCOL_VERSION, it) }
        minProtocolVersion?.let { if (!reply.containsKey(REPLY_MIN_PROTOCOL_VERSION)) reply.putInt(REPLY_MIN_PROTOCOL_VERSION, it) }
        return reply
    }

    fun pushPermissionState(state: Bundle) {
        application!!.dispatchPermissionStateChanged(state)
    }

    fun pushRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        val data = Bundle()
        data.putBoolean(PorterProtocol.PERMISSION_RESULT_ALLOWED, allowed)
        application!!.dispatchRequestPermissionResult(requestCode, data)
    }

    override fun getUid(): Int = serverUid

    override fun checkPermission(permission: String): Int = PackageManager.PERMISSION_DENIED

    override fun getSELinuxContext(): String? = seLinuxContext

    override fun getSystemProperty(name: String, defaultValue: String?): String? = defaultValue

    override fun setSystemProperty(name: String, value: String) {
    }

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
}
