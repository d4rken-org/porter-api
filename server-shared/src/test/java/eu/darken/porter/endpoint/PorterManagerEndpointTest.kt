package eu.darken.porter.endpoint

import android.os.Handler
import android.os.IBinder
import eu.darken.porter.core.ManagerOperations
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.util.HandlerUtil

/** The manager's binder: who reaches the delegate through it, and with which arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterManagerEndpointTest {

    private lateinit var policy: ServerTestSupport.TestPolicy
    private lateinit var managerOperations: ManagerOperations
    private lateinit var endpoint: PorterManagerEndpoint

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        val config = mock(ConfigManager::class.java)
        policy = ServerTestSupport.TestPolicy()
        managerOperations = mock(ManagerOperations::class.java)
        endpoint = PorterManagerEndpoint(
            ServerTestSupport.newCore(ClientManager(config), ServerTestSupport.TestUserServiceManager(), config, policy) {
                listOf(PACKAGE)
            },
            managerOperations,
        )

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    /** Holding the binder is not what authorizes a call: every method asks the policy again. */
    @Test
    fun aCallerThePolicyDoesNotAcceptReachesNoneOfThem() {
        val binder = mock(IBinder::class.java)

        assertThrows(SecurityException::class.java) { endpoint.newProcess(arrayOf("sh", "-c", "exit 0"), null, null) }
        assertThrows(SecurityException::class.java) { endpoint.exit() }
        assertThrows(SecurityException::class.java) { endpoint.attachUserService(binder, TOKEN) }
        assertThrows(SecurityException::class.java) {
            endpoint.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, true, false)
        }
        assertThrows(SecurityException::class.java) { endpoint.getFlagsForUid(TARGET_UID, 6) }
        assertThrows(SecurityException::class.java) { endpoint.updateFlagsForUid(TARGET_UID, 6, 2) }

        verifyNoInteractions(managerOperations)
    }

    @Test
    @Throws(Exception::class)
    fun anAcceptedCallerRunsAProcess() {
        policy.managerPermission = true
        policy.callerPermission = true

        val process = endpoint.newProcess(arrayOf("sh", "-c", "exit 3"), null, null)

        assertNotNull(process)
        assertEquals(3, process.waitFor())
    }

    @Test
    fun anAcceptedCallerExits() {
        policy.managerPermission = true

        endpoint.exit()

        verify(managerOperations).exit()
    }

    @Test
    fun anAcceptedCallerHandsOverTheUserServiceBinderWithItsToken() {
        policy.managerPermission = true
        val binder = mock(IBinder::class.java)

        endpoint.attachUserService(binder, TOKEN)

        verify(managerOperations).attachUserService(binder, TOKEN)
    }

    @Test
    fun aConfirmationResultIsPassedOnAsItsTwoFlags() {
        policy.managerPermission = true

        endpoint.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, true, true)

        verify(managerOperations).dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, true, true)
    }

    @Test
    fun theFlagsForAUidAreReadAndWrittenThroughTheDelegate() {
        policy.managerPermission = true
        `when`(managerOperations.getFlagsForUid(TARGET_UID, 6)).thenReturn(2)

        assertEquals(2, endpoint.getFlagsForUid(TARGET_UID, 6))

        endpoint.updateFlagsForUid(TARGET_UID, 6, 2)

        verify(managerOperations).updateFlagsForUid(TARGET_UID, 6, 2)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter"
        const val TOKEN = "user-service-token"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        const val TARGET_UID = 1010300
        const val TARGET_PID = 45700
        const val REQUEST_CODE = 11
    }
}
