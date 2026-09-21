package rikka.shizuku.server

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import eu.darken.porter.core.ManagerOperations
import java.util.function.IntFunction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerTestSupport.TestPolicy
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager
import rikka.shizuku.server.ServerTestSupport.newCore
import rikka.shizuku.server.util.HandlerUtil

/** The manager-only Shizuku methods: who reaches the delegate, and with which arguments. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuLegacyEndpointManagerOperationsTest {

    private lateinit var policy: TestPolicy
    private lateinit var managerOperations: ManagerOperations
    private lateinit var endpoint: ShizukuLegacyEndpoint

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        val config = mock(ConfigManager::class.java)
        policy = TestPolicy()
        managerOperations = mock(ManagerOperations::class.java)
        endpoint = ShizukuLegacyEndpoint(
            newCore(ClientManager(config), TestUserServiceManager(), config, policy, IntFunction { listOf(PACKAGE) }),
            managerOperations,
        )

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    private fun tokenOptions(): Bundle {
        val options = Bundle()
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, TOKEN)
        return options
    }

    private fun confirmation(allowed: Boolean, onetime: Boolean): Bundle {
        val data = Bundle()
        data.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
        data.putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime)
        return data
    }

    @Test
    fun aCallerThePolicyDoesNotAcceptReachesNoneOfThem() {
        val binder = mock(IBinder::class.java)

        assertThrows(SecurityException::class.java) { endpoint.exit() }
        assertThrows(SecurityException::class.java) { endpoint.attachUserService(binder, tokenOptions()) }
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

        endpoint.attachUserService(binder, tokenOptions())

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
    fun theSuiMethodsAreInert() {
        assertFalse(endpoint.isHidden(TARGET_UID))
        endpoint.dispatchPackageChanged(Intent())

        verifyNoInteractions(managerOperations)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val TOKEN = "user-service-token"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        const val TARGET_UID = 1010300
        const val TARGET_PID = 45700
        const val REQUEST_CODE = 11
    }
}
