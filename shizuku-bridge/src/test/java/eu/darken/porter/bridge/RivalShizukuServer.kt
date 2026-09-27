package eu.darken.porter.bridge

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.ShizukuApiConstants

/**
 * A Shizuku server of its own, running as root, that answers the attach only when told to, as a
 * real server's oneway reply can arrive late.
 */
internal class RivalShizukuServer : IShizukuService.Stub() {

    @Volatile
    var application: IShizukuApplication? = null

    override fun attachApplication(application: IShizukuApplication?, args: Bundle?) {
        this.application = application
    }

    /** Sends the attach reply now. */
    fun reply() {
        checkNotNull(application) { "nothing attached" }.bindApplication(Bundle().apply {
            putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, ROOT)
            putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION, 13)
            putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION, 0)
            putString(ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT, "u:r:su:s0")
            putBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, true)
            putBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
        })
    }

    override fun getVersion(): Int = 13

    override fun getUid(): Int = ROOT

    override fun getSELinuxContext(): String = "u:r:su:s0"

    override fun checkSelfPermission(): Boolean = true

    override fun checkPermission(permission: String?): Int = unused()

    override fun newProcess(cmd: Array<out String>?, env: Array<out String>?, dir: String?): IRemoteProcess = unused()

    override fun getSystemProperty(name: String?, defaultValue: String?): String = unused()

    override fun setSystemProperty(name: String?, value: String?) = unused()

    override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int = unused()

    override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int = unused()

    override fun requestPermission(requestCode: Int) = unused()

    override fun shouldShowRequestPermissionRationale(): Boolean = unused()

    override fun exit() = unused()

    override fun attachUserService(binder: IBinder?, options: Bundle?) = unused()

    override fun dispatchPackageChanged(intent: Intent?) = unused()

    override fun isHidden(uid: Int): Boolean = unused()

    override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) = unused()

    override fun getFlagsForUid(uid: Int, mask: Int): Int = unused()

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException()

    companion object {
        const val ROOT = 0
    }
}
