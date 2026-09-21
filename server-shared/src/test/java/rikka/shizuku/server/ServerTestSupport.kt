package rikka.shizuku.server

import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.PorterCore
import eu.darken.porter.core.ServerPolicy
import java.util.function.IntFunction
import moe.shizuku.server.IShizukuApplication
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** The server pieces every suite in this module builds its subject out of. */
object ServerTestSupport {

    /** A policy that answers what a test set on it and remembers what it was told. */
    open class TestPolicy : ServerPolicy {

        var callerPermission = false
        var managerPermission = false

        var confirmationShown = false
        var confirmationRequestCode = 0
        var confirmationRecord: ClientRecord? = null
        var confirmationUid = 0
        var confirmationPid = 0
        var confirmationUserId = 0

        val attachingCallers: MutableList<CallerIdentity> = ArrayList()
        val attachingPackages: MutableList<String> = ArrayList()
        val attachedRecords: MutableList<ClientRecord> = ArrayList()
        val attachedCreated: MutableList<Boolean> = ArrayList()
        val attachedReplies: MutableList<Bundle> = ArrayList()
        val boundRecords: MutableList<ClientRecord> = ArrayList()
        val boundCreated: MutableList<Boolean> = ArrayList()

        override fun checkCallerPermission(func: String, caller: CallerIdentity, record: ClientRecord?): Boolean =
            callerPermission

        override fun checkCallerManagerPermission(func: String, caller: CallerIdentity): Boolean =
            managerPermission

        override fun showPermissionConfirmation(requestCode: Int, record: ClientRecord, caller: CallerIdentity, userId: Int) {
            confirmationShown = true
            confirmationRequestCode = requestCode
            confirmationRecord = record
            confirmationUid = caller.uid
            confirmationPid = caller.pid
            confirmationUserId = userId
        }

        override fun onAttaching(caller: CallerIdentity, packageName: String) {
            attachingCallers.add(caller)
            attachingPackages.add(packageName)
        }

        override fun onAttached(record: ClientRecord, created: Boolean, reply: Bundle) {
            attachedRecords.add(record)
            attachedCreated.add(created)
            attachedReplies.add(reply)
        }

        override fun onBound(record: ClientRecord, created: Boolean) {
            boundRecords.add(record)
            boundCreated.add(created)
        }
    }

    /** A concrete manager so the core carries a real object rather than a mock. */
    open class TestUserServiceManager : UserServiceManager() {
        override fun getUserServiceStartCmd(
            record: UserServiceRecord,
            key: String,
            token: String,
            packageName: String,
            classname: String,
            processNameSuffix: String?,
            callingUid: Int,
            use32Bits: Boolean,
            debug: Boolean,
        ): String = "exit 0"
    }

    fun newCore(
        clientManager: ClientManager<ConfigManager>,
        userServiceManager: UserServiceManager,
        configManager: ConfigManager,
        policy: ServerPolicy,
        packagesForUid: IntFunction<List<String>>,
    ): PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> =
        PorterCore(userServiceManager, clientManager, configManager, policy, packagesForUid)

    /** An application binder death can be fired from, which `addClient` needs to link to. */
    fun application(binder: IBinder): IShizukuApplication {
        val application = mock(IShizukuApplication::class.java)
        `when`(application.asBinder()).thenReturn(binder)
        return application
    }

    fun entry(allowed: Boolean, denied: Boolean): ConfigPackageEntry = object : ConfigPackageEntry() {
        override fun isAllowed(): Boolean = allowed
        override fun isDenied(): Boolean = denied
    }
}
