package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.await
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/** What a refused bind does to a third caller that rebound successfully while it was outstanding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDeathRewoundByARefusedBindTest {

    /**
     * The rebind happens from inside the refused caller's bind call. A collector launched there
     * with an unconfined dispatcher would be queued behind the caller, so it runs on a thread of
     * its own and the test waits for the server to have accepted it.
     */
    private val executor = Executors.newSingleThreadExecutor()
    private val rebinderScope = CoroutineScope(executor.asCoroutineDispatcher())

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        UserServiceTestSupport.queueEvents()
    }

    @After
    fun teardown() {
        rebinderScope.cancel()
        executor.shutdownNow()
        Porter.resetForTest()
    }

    @Test
    fun aRefusedBindDoesNotRewindAThirdCallersSuccessfulRebind() = runTest {
        val fake = ScriptedPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        val args = args("rebind-rewound-by-refused-bind")

        val first = RecordingCollector(backgroundScope, connection().userService(args))
        val rebinder = RecordingCollector(backgroundScope, connection().userService(args))
        val connection = peek(args)
        assertNotNull(connection)
        connection!!.connected(Binder())
        idle()
        assertEquals("the first caller was never connected", 1, first.connects)
        assertEquals("the rebinding caller was never connected", 1, rebinder.connects)

        connection.died()

        // A third caller's bind is outstanding when the rebinder collects again. That rebind is
        // accepted; only the outstanding bind is refused.
        var rebound: RecordingCollector? = null
        fake.duringAdd = {
            rebound = RecordingCollector(rebinderScope, connection().userService(args), rebinderScope.coroutineContext[kotlinx.coroutines.CoroutineDispatcher]!!)
            await { fake.adds == 3 }
        }
        fake.addFailure = RuntimeException("the server refused")
        val refused = RecordingCollector(backgroundScope, connection().userService(args))
        assertNotNull(refused.failure)

        idle()

        assertEquals("the caller registered at the death was never told about it", 1, first.disconnects)
        assertEquals("the rebinder's first collection was never told about the death", 1, rebinder.disconnects)
        assertEquals("the refused caller was told about a binding it never had", 0, refused.disconnects)

        val reboundConnection = peek(args)
        assertNotNull("the refused bind's rollback dropped the successful rebind's binding", reboundConnection)
        assertNotSame("the rebind was handed back the binding the death retired", connection, reboundConnection)

        reboundConnection!!.connected(Binder())
        idle()
        await { rebound!!.connects == 1 }
        assertEquals("the rebound caller never heard about its new binding", 1, rebound!!.connects)
    }
}
