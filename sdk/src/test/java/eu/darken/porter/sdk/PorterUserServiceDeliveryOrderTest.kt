package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.events
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import eu.darken.porter.sdk.UserServiceTestSupport.queueEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Connects and disconnects run on their own thread; the SDK's post-dead hooks and a Main-dispatched
 * collector run on the main one. Whichever of the two runs first, a flow ends once with the
 * connection it was collected on and never hears of the server after it, including when that
 * server's binding connects while the old disconnect is still queued.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterUserServiceDeliveryOrderTest(
    private val end: End,
    private val deliveryFirst: Boolean,
    private val collectorOnMain: Boolean,
) {

    enum class End { DEATH, REPLACEMENT }

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        queueEvents()
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private val dispatcher: CoroutineDispatcher
        get() = if (collectorOnMain) Dispatchers.Main else Dispatchers.Unconfined

    /** Runs what the SDK queued for delivery, without the main looper. */
    private fun idleDelivery() = events.runAll()

    private fun idleMain() = ShadowLooper.shadowMainLooper().idle()

    private fun drain() {
        repeat(2) {
            if (deliveryFirst) {
                idleDelivery()
                idleMain()
            } else {
                idleMain()
                idleDelivery()
            }
        }
    }

    @Test
    fun aFlowEndsOnceWithItsConnectionAndNeverHearsOfTheNextServer() = runTest {
        val first = FakePorterService()
        Porter.onBinderReceived(first, UserServiceTestSupport.PACKAGE)
        val args = args("order-$end")
        val old = RecordingCollector(backgroundScope, connection().userService(args), dispatcher)
        drain()
        val oldBinding = peek(args)
        assertNotNull("the bind left no binding to connect", oldBinding)
        oldBinding!!.connected(Binder())
        drain()
        assertEquals("the caller was never connected", 1, old.connects)

        val nextServer = FakePorterService()
        when (end) {
            End.DEATH -> {
                // The SDK's own reaction to a death runs on main; here it attaches the next server,
                // standing in for a secondary process's refetch.
                Porter.addPostBinderDeadHook { Porter.onBinderReceived(nextServer, UserServiceTestSupport.PACKAGE) }
                deathRecipientOf(first).binderDied()
            }
            End.REPLACEMENT -> Porter.onBinderReceived(nextServer, UserServiceTestSupport.PACKAGE)
        }
        // Unless delivery goes first, the old disconnect stays queued while the next server's
        // binding is made and its connect is queued behind it.
        if (deliveryFirst) idleDelivery()
        idleMain()
        assertSame("the next server was not the one published", nextServer, connection().binder)
        val next = RecordingCollector(backgroundScope, connection().userService(args), dispatcher)
        idleMain()
        val nextBinding = peek(args)
        assertNotNull("the next server's bind left no binding to connect", nextBinding)
        assertNotSame("the next server was handed the old connection's binding", oldBinding, nextBinding)
        nextBinding!!.connected(Binder())
        if (!deliveryFirst) assertFalse("the old disconnect was delivered while delivery was held", old.completed)
        // Delivery before main, so a Main collector's last hop runs too.
        idleDelivery()
        idleMain()

        assertTrue("the flow outlived the connection it was collected on", old.completed)
        assertNull(old.failure)
        assertEquals("the next server's binder reached a collector of the old connection", 1, old.connects)
        assertEquals("the next server's collector was never connected", 1, next.connects)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} deliveryFirst={1} collectorOnMain={2}")
        fun cases(): List<Array<Any>> = End.values().flatMap { end ->
            listOf(true, false).flatMap { deliveryFirst ->
                listOf(true, false).map { onMain -> arrayOf<Any>(end, deliveryFirst, onMain) }
            }
        }
    }
}
