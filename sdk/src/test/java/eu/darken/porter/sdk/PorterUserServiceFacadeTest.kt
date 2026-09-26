package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/** What a failed or finished user service call leaves behind in the caller's own registrations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterUserServiceFacadeTest {

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        UserServiceTestSupport.queueEvents()
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun attached(): ScriptedPorterService {
        val fake = ScriptedPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        return fake
    }

    /** What the server would push once the binding exists, delivered to whoever is registered. */
    private fun pushConnected(args: UserServiceArgs) {
        val connection = peek(args)
        assertNotNull(connection)
        connection!!.connected(Binder())
        idle()
    }

    @Test
    fun aFailedBindLeavesTheCallerUnregisteredAndRethrows() = runTest {
        val fake = attached()
        val args = args("failed-bind")
        val failure = RuntimeException("the server refused")
        fake.addFailure = failure

        val conn = RecordingCollector(backgroundScope, connection().userService(args))

        assertTrue(conn.failure is PorterRemoteException)
        assertSame(failure, conn.failure?.cause)
        pushConnected(args)
        assertEquals(0, conn.connects)
    }

    @Test
    fun aFailedSecondBindOfOneConnectionLeavesTheFirstRegistrationLive() = runTest {
        val fake = attached()
        val args = args("rebound")
        val conn = RecordingCollector(backgroundScope, connection().userService(args))

        fake.addFailure = RuntimeException("the server refused")
        val refused = RecordingCollector(backgroundScope, connection().userService(args))
        assertNotNull(refused.failure)

        pushConnected(args)
        assertEquals(1, conn.connects)
        assertEquals(0, refused.connects)
    }

    @Test
    fun aFailedUnbindStillClearsTheLocalState() = runTest {
        val fake = attached()
        val args = args("failed-unbind")
        val conn = RecordingCollector(backgroundScope, connection().userService(args))

        fake.removeFailure = RuntimeException("the server refused")
        conn.job.cancelAndJoin()

        assertNull(peek(args))
        assertNull("a refused removal escaped the cancellation", conn.failure)
    }

    @Test
    fun killingTheServiceLeavesTheDisconnectToTheDeathRecipient() = runTest {
        val fake = attached()
        val args = args("killed")
        RecordingCollector(backgroundScope, connection().userService(args))

        connection().stopUserService(args)

        assertNotNull(peek(args))
        assertNotNull(fake.userServiceArgs)
        assertTrue(fake.userServiceArgs!!.getBoolean(USER_SERVICE_REMOVE))
    }

    @Test
    fun aDeathIssuedBeforeARebindDisconnectsOnlyTheCallerItWasBoundTo() = runTest {
        attached()
        val args = args("death-then-rebind")
        val first = RecordingCollector(backgroundScope, connection().userService(args))
        pushConnected(args)
        assertEquals(1, first.connects)

        val connection = peek(args)
        assertNotNull(connection)
        connection!!.died()
        val second = RecordingCollector(backgroundScope, connection().userService(args))
        idle()

        assertEquals(1, first.disconnects)
        assertEquals(0, second.disconnects)
        assertNotNull(peek(args))
        assertNotSame(connection, peek(args))
    }
}
