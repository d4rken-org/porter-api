package eu.darken.porter.sdk

import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the manager-only calls put on the wire for the server to read back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterManagerOperationsTest {

    private lateinit var fake: FakePorterService
    private lateinit var connection: PorterConnection

    @Before
    fun setup() {
        fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        connection = Porter.connection.value!!
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun exitReachesTheServer() {
        connection.exit()

        assertEquals(1, fake.exitCalls)
    }

    @Test
    fun attachUserServiceSendsTheBinderAndItsToken() {
        val binder = mock(IBinder::class.java)

        connection.attachUserService(binder, TOKEN)

        assertSame(binder, fake.attachedUserServiceBinder)
        assertNotNull(fake.attachedUserServiceArgs)
        assertEquals(TOKEN, fake.attachedUserServiceArgs!!.getString(USER_SERVICE_TOKEN))
    }

    @Test
    fun aConfirmationResultSendsBothFlagsWithTheRequest() {
        connection.dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, allowed = true, onetime = false)

        assertEquals(TARGET_UID, fake.confirmationUid)
        assertEquals(TARGET_PID, fake.confirmationPid)
        assertEquals(REQUEST_CODE, fake.confirmationRequestCode)
        val data = fake.confirmationData
        assertNotNull(data)
        assertTrue(data!!.getBoolean(PERMISSION_CONFIRMATION_ALLOWED))
        assertFalse(data.getBoolean(PERMISSION_CONFIRMATION_ONETIME))
    }

    @Test
    fun theFlagsForAUidAreReadAndWritten() {
        fake.flags = 2

        assertEquals(2, connection.getFlagsForUid(TARGET_UID, 6))
        assertEquals(TARGET_UID, fake.flagsUid)
        assertEquals(6, fake.flagsMask)

        connection.updateFlagsForUid(TARGET_UID, 6, 4)

        assertEquals(6, fake.flagsMask)
        assertEquals(4, fake.flagsValue)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter"
        const val TOKEN = "user-service-token"
        const val TARGET_UID = 1010200
        const val TARGET_PID = 45678
        const val REQUEST_CODE = 11
    }
}
