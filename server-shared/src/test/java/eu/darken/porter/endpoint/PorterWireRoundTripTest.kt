package eu.darken.porter.endpoint

import android.content.ComponentName
import android.os.Handler
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterUserServiceCodec
import eu.darken.porter.sdk.UserServiceArgs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLooper
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.ServerTestSupport.TestPolicy
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.OsUtils

/**
 * The SDK talking to the endpoint in one process: what one side writes, the other reads. Both
 * halves of the wire are pinned here, so a key renamed on one side alone fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterWireRoundTripTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var endpoint: PorterEndpoint

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        endpoint = PorterEndpoint(
            ServerTestSupport.newCore(clients, TestUserServiceManager(), config, TestPolicy()) { listOf(PACKAGE) },
        )

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
        // Server calls run inline, so the calling identity set above is the one the endpoint reads.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
        ShadowBinder.reset()
    }

    private fun connection(): PorterConnection = Porter.connection.value ?: error("no connection is published")

    @Test
    fun anAllowedClientAttachesAndSeesTheServer() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))

        Porter.onBinderReceived(endpoint, PACKAGE)

        val connection = connection()
        assertTrue(runBlocking { connection.isAlive() })
        assertEquals(OsUtils.uid, connection.uid)
        assertEquals(PorterProtocol.VERSION, connection.serverInfo.version)
        assertEquals(PermissionState.Granted, runBlocking { connection.checkPermission() })
    }

    @Test
    fun aClientWithoutAGrantIsToldSo() {
        Porter.onBinderReceived(endpoint, PACKAGE)

        assertEquals(PermissionState.Denied(permanentlyDenied = false), runBlocking { connection().checkPermission() })
    }

    @Test
    fun aPermissionResultComesBackThroughTheCallback() = runTest {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        Porter.onBinderReceived(endpoint, PACKAGE)

        val answer = async(start = CoroutineStart.UNDISPATCHED) { connection().requestPermission() }
        ShadowLooper.idleMainLooper()

        assertEquals(PermissionState.Granted, answer.await())
    }

    @Test
    fun theUserServiceBundlesSurviveTheRoundTrip() {
        val component = ComponentName(PACKAGE, CLASS)
        val add = PorterUserServiceCodec.encodeUserService(
            UserServiceArgs(component, processNameSuffix = "p", tag = "t", version = 3, debuggable = true, daemon = false),
            noCreate = false,
        )

        val bind = PorterUserServiceOptions.decodeForBind(add)

        assertEquals(component, bind.component)
        assertEquals("t", bind.tag)
        assertEquals(3, bind.versionCode)
        assertEquals("p", bind.processNameSuffix)
        assertFalse(bind.daemon)
        assertTrue(bind.debuggable)
        assertFalse(bind.noCreate)
        assertFalse(bind.use32Bit)
        assertTrue(bind.remove)

        val remove = PorterUserServiceCodec.encodeUserServiceRemoval(
            UserServiceArgs(component, processNameSuffix = "p", tag = "t"),
            remove = false,
        )

        assertFalse(PorterUserServiceOptions.decodeForRemove(remove).remove)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678
    }
}
