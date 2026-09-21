package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
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

/** The manager-only Porter methods: who reaches the delegate, and with which arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterEndpointManagerOperationsTest {

    private lateinit var policy: ServerTestSupport.TestPolicy
    private lateinit var managerOperations: ManagerOperations
    private lateinit var endpoint: PorterEndpoint

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        val config = mock(ConfigManager::class.java)
        policy = ServerTestSupport.TestPolicy()
        managerOperations = mock(ManagerOperations::class.java)
        endpoint = PorterEndpoint(
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

    @Test
    fun aCallerThePolicyDoesNotAcceptReachesNoneOfThem() {
        val binder = mock(IBinder::class.java)

        assertThrows(SecurityException::class.java) { endpoint.exit() }
        assertThrows(SecurityException::class.java) { endpoint.attachUserService(binder, tokenArgs()) }
        assertThrows(SecurityException::class.java) {
            endpoint.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, confirmation(true, false))
        }
        assertThrows(SecurityException::class.java) { endpoint.getFlagsForUid(TARGET_UID, 6) }
        assertThrows(SecurityException::class.java) { endpoint.updateFlagsForUid(TARGET_UID, 6, 2) }

        verifyNoInteractions(managerOperations)
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

        endpoint.attachUserService(binder, tokenArgs())

        verify(managerOperations).attachUserService(binder, TOKEN)
    }

    @Test
    fun aConfirmationResultIsPassedOnAsItsTwoFlags() {
        policy.managerPermission = true

        endpoint.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, confirmation(true, true))

        verify(managerOperations).dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, true, true)
    }

    @Test
    fun aConfirmationResultWithoutDataIsDropped() {
        policy.managerPermission = true

        endpoint.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, null)

        verifyNoInteractions(managerOperations)
    }

    @Test
    fun theFlagsForAUidAreReadAndWrittenThroughTheDelegate() {
        policy.managerPermission = true
        `when`(managerOperations.getFlagsForUid(TARGET_UID, 6)).thenReturn(2)

        assertEquals(2, endpoint.getFlagsForUid(TARGET_UID, 6))

        endpoint.updateFlagsForUid(TARGET_UID, 6, 2)

        verify(managerOperations).updateFlagsForUid(TARGET_UID, 6, 2)
    }

    @Test
    fun theAttachReplyCarriesTheCurrentProtocolVersion() {
        val application = mock(IPorterApplication::class.java)
        `when`(application.asBinder()).thenReturn(mock(IBinder::class.java))
        val args = Bundle()
        args.putString(ATTACH_PACKAGE_NAME, PACKAGE)
        args.putInt(ATTACH_PROTOCOL_VERSION, 3)

        val reply = endpoint.attach(application, args)

        assertEquals(3, reply.getInt(REPLY_PROTOCOL_VERSION))
    }

    /**
     * A refused caller is told it has no permission, not what its Bundle failed to decode to: a
     * null or component-less Bundle would raise something else if the gate ran after the decoder.
     */
    @Test
    fun theUserServiceCallsRefuseAnUnauthorizedCallerBeforeReadingItsBundle() {
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), null) }
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), Bundle()) }
        assertThrows(SecurityException::class.java) { endpoint.removeUserService(connection(), Bundle()) }
    }

    /**
     * Through the generated interface, whose parameter is a platform type: the wire can carry a
     * null Bundle whatever the Kotlin signature says, and the gate has to run before it is read.
     */
    @Test
    fun removeUserServiceRefusesAnUnauthorizedCallerBeforeReadingANullBundle() {
        assertThrows(SecurityException::class.java) { (endpoint as IPorterService).removeUserService(connection(), null) }
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

        fun tokenArgs(): Bundle = Bundle().apply { putString(USER_SERVICE_TOKEN, TOKEN) }

        fun confirmation(allowed: Boolean, onetime: Boolean): Bundle = Bundle().apply {
            putBoolean(PERMISSION_CONFIRMATION_ALLOWED, allowed)
            putBoolean(PERMISSION_CONFIRMATION_ONETIME, onetime)
        }

        fun connection(): IPorterServiceConnection {
            val connection = mock(IPorterServiceConnection::class.java)
            `when`(connection.asBinder()).thenReturn(mock(IBinder::class.java))
            return connection
        }
    }
}
