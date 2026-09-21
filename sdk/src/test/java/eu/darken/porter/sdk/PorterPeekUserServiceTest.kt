package eu.darken.porter.sdk

import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A peek is a query: it asks without starting, and leaves no registration behind on the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterPeekUserServiceTest {

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun attached(): FakePorterService {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        return fake
    }

    @Test
    fun aRunningServiceAnswersItsVersionAndTheRegistrationIsDroppedAgain() {
        val fake = attached()
        fake.userServiceResult = 7

        assertEquals(7, connection().peekUserService(args("peeked")))

        assertEquals(1, fake.userServiceRemoves)
        assertFalse("dropped, not killed", fake.userServiceArgs!!.getBoolean(USER_SERVICE_REMOVE))
        assertSame(
            "the callback the peek registered is the one it asked the server to drop",
            fake.userServiceConnection!!.asBinder(), fake.removedUserServiceConnection!!.asBinder(),
        )
        assertNull("nothing is bound under that identity", peek(args("peeked")))
    }

    @Test
    fun aServiceThatIsNotRunningAnswersNullAndNothingIsDropped() {
        val fake = attached()
        fake.userServiceResult = -1

        assertNull(connection().peekUserService(args("absent")))

        assertTrue(fake.userServiceArgs!!.getBoolean(USER_SERVICE_NO_CREATE))
        assertEquals(0, fake.userServiceRemoves)
        assertNull(peek(args("absent")))
    }
}
