package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a bind the server refuses does to a death another caller is already owed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDeathLostToARolledBackBindTest {

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aRefusedBindDoesNotSwallowAnotherCallersDeath() = runTest {
        val fake = ScriptedPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        val args = args("death-then-refused-bind")

        val first = RecordingCollector(backgroundScope, connection().userService(args))
        val connection = PorterServiceConnections.peek(args)
        assertNotNull(connection)
        connection!!.connected(Binder())
        idle()
        assertEquals("the first caller was never connected", 1, first.connects)

        connection.died()

        // A second caller's bind reaches the server and is refused, so it never becomes a binding.
        fake.addFailure = RuntimeException("the server refused")
        val refused = RecordingCollector(backgroundScope, connection().userService(args))
        assertNotNull(refused.failure)

        idle()

        assertEquals("the live caller was never told the service died", 1, first.disconnects)
        assertEquals("the refused caller was told about a binding it never had", 0, refused.disconnects)
    }
}
