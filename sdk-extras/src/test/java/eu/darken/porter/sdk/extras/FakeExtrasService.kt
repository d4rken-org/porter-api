package eu.darken.porter.sdk.extras

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterRemoteProcess
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection

/** A local stub standing in for the server, carrying only the system property calls. */
internal class FakeExtrasService : IPorterService.Stub() {

    var attachReply: Bundle = Bundle()

    /** Answered to every `getSystemProperty`; null means "echo the default back". */
    var propertyValue: String? = null

    var readName: String? = null
    var readDefault: String? = null
    var writtenName: String? = null
    var writtenValue: String? = null

    override fun attach(application: IPorterApplication, args: Bundle): Bundle = attachReply

    override fun getSystemProperty(name: String, defaultValue: String?): String? {
        readName = name
        readDefault = defaultValue
        return propertyValue ?: defaultValue
    }

    override fun setSystemProperty(name: String, value: String) {
        writtenName = name
        writtenValue = value
    }

    override fun getUid(): Int = -1

    override fun checkPermission(permission: String): Int = PackageManager.PERMISSION_DENIED

    override fun getSELinuxContext(): String? = null

    override fun newProcess(cmd: Array<String>?, env: Array<String>?, dir: String?): IPorterRemoteProcess? = null

    override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int = 0

    override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle): Int = 0

    override fun requestPermission(requestCode: Int) {
    }

    override fun checkSelfPermission(): Boolean = false

    override fun shouldShowRequestPermissionRationale(): Boolean = false

    override fun exit() {
    }

    override fun attachUserService(binder: IBinder, args: Bundle) {
    }

    override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle) {
    }

    override fun getFlagsForUid(uid: Int, mask: Int): Int = 0

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) {
    }
}
