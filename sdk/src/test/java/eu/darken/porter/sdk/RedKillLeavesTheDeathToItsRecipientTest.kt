package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/** A kill is a request to the server, not a local teardown: the death recipient still delivers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedKillLeavesTheDeathToItsRecipientTest {

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

    private fun boundAndConnected(scope: CoroutineScope, args: UserServiceArgs): Pair<RecordingCollector, PorterServiceConnection> {
        val collector = RecordingCollector(scope, connection().userService(args))
        val connection = peek(args)
        assertNotNull(connection)
        connection!!.connected(Binder())
        idle()
        assertEquals("the caller was never connected", 1, collector.connects)
        return collector to connection
    }

    @Test
    fun anAcceptedKillLeavesTheDisconnectToTheDeathRecipient() = runTest {
        val fake = ScriptedPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        val args = args("killed-then-died")
        val (collector, _) = boundAndConnected(backgroundScope, args)

        connection().stopUserService(args)

        assertNotNull(fake.userServiceArgs)
        assertTrue("the kill never reached the server", fake.userServiceArgs!!.getBoolean(USER_SERVICE_REMOVE))

        val afterKill = peek(args)
        assertNotNull("the kill tore down the registration the death still has to reach", afterKill)

        // The killed process exits and its binder dies, which is what actually ends the binding.
        afterKill!!.died()
        idle()

        assertEquals("the caller was never told the killed service died", 1, collector.disconnects)
    }

    @Test
    fun aRefusedKillLeavesTheStillRunningServiceBound() = runTest {
        val fake = ScriptedPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        val args = args("refused-kill")
        val (collector, connection) = boundAndConnected(backgroundScope, args)

        fake.removeFailure = RuntimeException("the server refused")
        assertTrue(runCatching { connection().stopUserService(args) }.exceptionOrNull() is RuntimeException)

        val afterRefusal = peek(args)
        assertNotNull("a refused kill dropped the registrations of a service that is still running", afterRefusal)
        assertEquals("a refused kill disconnected a caller whose service is still running", 0, collector.disconnects)

        // Still the same live binding, so the server's next push still reaches the caller.
        connection.connected(Binder())
        idle()
        assertEquals("the caller of a refused kill stopped hearing from its live service", 2, collector.connects)
    }
}
