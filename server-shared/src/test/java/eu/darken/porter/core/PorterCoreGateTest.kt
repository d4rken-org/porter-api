package eu.darken.porter.core

import android.os.Handler
import android.os.IBinder
import android.system.Os
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.OsUtils

/**
 * Who [PorterCore.enforceCallingPermission] and [PorterCore.enforceManagerPermission]
 * admit, and with which message they refuse everyone else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterCoreGateTest {

    private lateinit var core: PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager>
    private lateinit var policy: ServerTestSupport.TestPolicy
    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var caller: CallerIdentity

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        policy = ServerTestSupport.TestPolicy()
        core = ServerTestSupport.newCore(clients, ServerTestSupport.TestUserServiceManager(), config, policy) {
            listOf(PACKAGE)
        }

        caller = CallerIdentity(CLIENT_UID, CLIENT_PID)
    }

    private fun attach(apiVersion: Int) {
        clients.addClient(CLIENT_UID, CLIENT_PID, ServerTestSupport.application(mock(IBinder::class.java)), PACKAGE, apiVersion)
    }

    @Test
    fun serverUidIsRefusedWithoutARecord() {
        val e = assertThrows(SecurityException::class.java) {
            core.enforceCallingPermission("getVersion", CallerIdentity(OsUtils.uid, CLIENT_PID))
        }

        assertTrue(e.message, e.message!!.contains("is not an attached client"))
    }

    /** A grant cannot add anything to the server's own identity, so it is not asked for one. */
    @Test
    fun anAttachedServerUidPassesWithoutAGrant() {
        `when`(config.find(OsUtils.uid)).thenReturn(ServerTestSupport.entry(false, false))
        clients.addClient(OsUtils.uid, CLIENT_PID, ServerTestSupport.application(mock(IBinder::class.java)), PACKAGE, 13)

        core.enforceCallingPermission("getVersion", CallerIdentity(OsUtils.uid, CLIENT_PID))
    }

    @Test
    fun unattachedCallerIsRefusedAsNotAttached() {
        val e = assertThrows(SecurityException::class.java) {
            core.enforceCallingPermission("getVersion", caller)
        }

        assertTrue(e.message, e.message!!.contains("is not an attached client"))
    }

    @Test
    fun attachedButDisallowedClientIsRefusedAsRequiresPermission() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(false, false))
        attach(13)

        val e = assertThrows(SecurityException::class.java) {
            core.enforceCallingPermission("getVersion", caller)
        }

        assertTrue(e.message, e.message!!.contains("requires permission"))
    }

    @Test
    fun attachedAllowedClientPasses() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        attach(13)

        core.enforceCallingPermission("getVersion", caller)
    }

    @Test
    fun overrideAnsweringTrueAdmitsAnUnattachedCaller() {
        policy.callerPermission = true

        core.enforceCallingPermission("transactRemote", caller)
    }

    @Test
    fun managerGatePassesForOwnPid() {
        core.enforceManagerPermission("exit", CallerIdentity(CLIENT_UID, Os.getpid()))
    }

    @Test
    fun managerGatePassesWhenOverrideAgrees() {
        policy.managerPermission = true

        core.enforceManagerPermission("exit", caller)
    }

    @Test
    fun managerGateRefusesOtherwise() {
        val e = assertThrows(SecurityException::class.java) {
            core.enforceManagerPermission("exit", caller)
        }

        assertTrue(e.message, e.message!!.contains("is not manager"))
    }

    companion object {
        const val CLIENT_UID = 10200
        const val CLIENT_PID = 45678

        private const val PACKAGE = "eu.darken.porter.probe"
    }
}
