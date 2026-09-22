package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/** A death that arrives while a second caller's bind is still outstanding, and that bind is refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDeathDuringARefusedBindTest {

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aDeathDuringARefusedBindStillReachesTheLiveCaller() = runTest {
        val fake = ScriptedPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        val args = args("death-during-refused-bind")

        val live = RecordingCollector(backgroundScope, connection().userService(args))
        val connection = peek(args)
        assertNotNull(connection)
        connection!!.connected(Binder())
        idle()
        assertEquals("the live caller was never connected", 1, live.connects)

        // The service dies while the second caller's bind is still outstanding, and that bind is
        // then refused, so its rollback must not take the death down with it.
        fake.duringAdd = { connection.died() }
        fake.addFailure = RuntimeException("the server refused")
        val refused = RecordingCollector(backgroundScope, connection().userService(args))
        assertNotNull(refused.failure)

        idle()

        assertEquals("the live caller was never told its service died", 1, live.disconnects)
        assertNull("the dead binding was left in the cache for the next bind to reuse", peek(args))
    }
}
