package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A death overtaken by the same caller collecting the same service again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDeathDeliveredToASameConnectionRebindTest {

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aRebindOfTheSameConnectionDoesNotSwallowTheDeathItOvertook() = runTest {
        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)
        val args = args("death-then-same-connection-rebind")

        val first = RecordingCollector(backgroundScope, connection().userService(args))
        val connection = peek(args)
        assertNotNull(connection)
        connection!!.connected(Binder())
        idle()
        assertEquals("the caller was never connected", 1, first.connects)

        connection.died()

        // The same caller collects again, and the server accepts.
        val second = RecordingCollector(backgroundScope, connection().userService(args))

        idle()

        assertEquals("the caller was never told the binder it was connected to died", 1, first.disconnects)

        val rebound = peek(args)
        assertNotNull("the rebind was left without a binding", rebound)
        assertNotSame("the rebind was handed back the binding the death retired", connection, rebound)

        rebound!!.connected(Binder())
        idle()
        assertEquals("the rebound caller never heard about its new binding", 1, second.connects)
        assertEquals("the retired collection was connected again", 1, first.connects)
    }
}
