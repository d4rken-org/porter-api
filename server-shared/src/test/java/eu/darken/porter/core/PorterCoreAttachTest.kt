package eu.darken.porter.core

import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import eu.darken.porter.endpoint.PorterClientCallback
import eu.darken.porter.server.IPorterApplication
import java.util.function.IntFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.legacy.LegacyClientCallback
import rikka.shizuku.server.util.HandlerUtil

/** Which process gets a record, through which endpoint, and what the policy sees on the way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterCoreAttachTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var policy: RecordingPolicy
    private lateinit var core: PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager>
    private lateinit var caller: CallerIdentity

    /** Whether a record was already there each time the policy was told an attach is starting. */
    private class RecordingPolicy(private val clients: ClientManager<ConfigManager>) : ServerTestSupport.TestPolicy() {

        val recordExistedWhenAttaching = ArrayList<Boolean>()

        override fun onAttaching(caller: CallerIdentity, packageName: String) {
            super.onAttaching(caller, packageName)
            recordExistedWhenAttaching.add(clients.findClient(caller.uid, caller.pid) != null)
        }
    }

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        policy = RecordingPolicy(clients)
        core = ServerTestSupport.newCore(clients, ServerTestSupport.TestUserServiceManager(), config, policy) {
            listOf(PACKAGE, OTHER_PACKAGE)
        }

        caller = CallerIdentity(CLIENT_UID, CLIENT_PID)
    }

    @Test
    fun aProcessAttachedThroughShizukuCannotAttachThroughPorter() {
        core.attach(caller, PACKAGE, legacyCallback(), API_LEVEL)

        val e = assertThrows(IllegalStateException::class.java) {
            core.attach(caller, PACKAGE, porterCallback(), API_LEVEL)
        }

        assertTrue(e.message, e.message!!.contains("is attached through another endpoint"))
        assertEquals(1, clients.findClients(CLIENT_UID).size)
    }

    @Test
    fun aProcessAttachedThroughPorterCannotAttachThroughShizuku() {
        core.attach(caller, PACKAGE, porterCallback(), API_LEVEL)

        val e = assertThrows(IllegalStateException::class.java) {
            core.attach(caller, PACKAGE, legacyCallback(), API_LEVEL)
        }

        assertTrue(e.message, e.message!!.contains("is attached through another endpoint"))
        assertEquals(1, clients.findClients(CLIENT_UID).size)
    }

    @Test
    fun aReattachMayNotRenameTheClient() {
        core.attach(caller, PACKAGE, porterCallback(), API_LEVEL)

        assertThrows(SecurityException::class.java) {
            core.attach(caller, OTHER_PACKAGE, porterCallback(), API_LEVEL)
        }

        assertEquals(1, clients.findClients(CLIENT_UID).size)
        assertEquals(PACKAGE, clients.findClient(CLIENT_UID, CLIENT_PID)!!.packageName)
    }

    @Test
    fun theSecondAttachFromAProcessReusesTheRecordItFinds() {
        val first = core.attach(caller, PACKAGE, porterCallback(), API_LEVEL)
        val second = core.attach(caller, PACKAGE, porterCallback(), API_LEVEL)

        assertTrue(first.created)
        assertFalse(second.created)
        assertSame(first.record, second.record)

        assertEquals(listOf(PACKAGE, PACKAGE), policy.attachingPackages)
        assertEquals(
            "the first attach is told before the record exists",
            listOf(false, true), policy.recordExistedWhenAttaching,
        )
    }

    @Test
    @Throws(Exception::class)
    fun aDeadCallbackBinderIsReportedAsIllegalState() {
        val dead = mock(IBinder::class.java)
        doThrow(RemoteException()).`when`(dead).linkToDeath(any(IBinder.DeathRecipient::class.java), anyInt())
        val application = mock(IPorterApplication::class.java)
        `when`(application.asBinder()).thenReturn(dead)

        assertThrows(IllegalStateException::class.java) {
            core.attach(caller, PACKAGE, PorterClientCallback(application), API_LEVEL)
        }

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
    }

    @Test
    fun aPackageThatDoesNotBelongToTheCallerIsRefusedBeforeThePolicyHears() {
        val foreign = ServerTestSupport.newCore(
            clients, ServerTestSupport.TestUserServiceManager(), config, policy,
            IntFunction { listOf(OTHER_PACKAGE) },
        )

        assertThrows(SecurityException::class.java) {
            foreign.attach(caller, PACKAGE, porterCallback(), API_LEVEL)
        }

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
        assertTrue(policy.attachingPackages.isEmpty())
    }

    @Test
    fun anAttachedRecordCarriesWhatTheCallerWasGranted() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))

        val record = core.attach(caller, PACKAGE, porterCallback(), API_LEVEL).record

        assertTrue(record.allowed)
        assertEquals(PACKAGE, record.packageName)
        assertEquals(API_LEVEL, record.apiVersion)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val OTHER_PACKAGE = "eu.darken.porter.probe.other"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678
        const val API_LEVEL = 13

        fun legacyCallback(): ClientCallback =
            LegacyClientCallback(ServerTestSupport.application(mock(IBinder::class.java)))

        fun porterCallback(): ClientCallback {
            val application = mock(IPorterApplication::class.java)
            `when`(application.asBinder()).thenReturn(mock(IBinder::class.java))
            return PorterClientCallback(application)
        }
    }
}
