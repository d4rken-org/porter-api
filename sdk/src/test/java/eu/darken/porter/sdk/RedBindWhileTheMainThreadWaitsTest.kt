package eu.darken.porter.sdk

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.server.IPorterServiceConnection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A user service bound, or dying, while the main thread blocks on it still reaches its collector: an
 * app that waits on a user service from the main thread is not waiting on itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedBindWhileTheMainThreadWaitsTest {

    /** A server that connects every bind at once, as a running service does. */
    private class ConnectingService : FakePorterService() {
        val service = Binder()

        override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
            conn?.connected(service)
            return super.addUserService(conn, args)
        }
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aBindCompletesWhileTheMainThreadWaitsForIt() {
        val server = ConnectingService()
        Porter.onBinderReceived(server, UserServiceTestSupport.PACKAGE)

        assertSame("the test is meant to block the main thread", Looper.getMainLooper().thread, Thread.currentThread())
        // Nothing here runs the main looper.
        val bound = runBlocking { withTimeout(5_000) { connection().userService(args("main-blocked")).first() } }

        assertSame("the collector got another binder than the one the server connected", server.service, bound)
    }

    @Test
    fun aDeathCompletesTheFlowWhileTheMainThreadWaitsForIt() {
        val server = ConnectingService()
        Porter.onBinderReceived(server, UserServiceTestSupport.PACKAGE)
        val args = args("main-blocked-death")

        assertSame("the test is meant to block the main thread", Looper.getMainLooper().thread, Thread.currentThread())
        // The service dies once it is bound; nothing here runs the main looper.
        val emitted = runBlocking {
            withTimeout(5_000) {
                connection().userService(args)
                    .onEach { UserServiceTestSupport.peek(args)!!.died() }
                    .toList()
            }
        }

        assertEquals("the flow did not end with the one binder it was handed", listOf<IBinder>(server.service), emitted)
    }
}
