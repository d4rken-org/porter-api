package eu.darken.porter.sdk

import android.os.Binder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
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

/**
 * Cancelling a collection while the death of its service is already queued ends that collection
 * without a completion: a caller that has said it no longer wants the binding is not told the
 * binding ended. Nobody else is affected.
 *
 * The cancellation is scoped to the one collector. A death queued under one tag survives a
 * cancellation under another, and a new collection is not a cancellation, so the collector that
 * was bound still receives the death it overtook.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedUnbindCancelsAQueuedDeathTest {

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun boundAndConnected(scope: CoroutineScope, args: UserServiceArgs): Pair<RecordingCollector, PorterServiceConnection> {
        val collector = RecordingCollector(scope, connection().userService(args))
        val connection = peek(args)
        assertNotNull("the bind left no binding to connect", connection)
        connection!!.connected(Binder())
        idle()
        assertEquals("the caller was never connected", 1, collector.connects)
        return collector to connection
    }

    @Test
    fun anUnbindCancelsTheDeathQueuedForTheCallerThatUnbound() = runTest {
        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)
        val args = args("death-then-unbind")
        val (cancelled, connection) = boundAndConnected(backgroundScope, args)
        val kept = RecordingCollector(backgroundScope, connection().userService(args))

        // The service dies. The delivery is queued on the main looper and has not run yet.
        connection.died()
        assertEquals("the death was delivered before the main looper ran it", 0, cancelled.disconnects)

        // One caller cancels before that delivery runs: it no longer wants this binding at all.
        cancelled.job.cancelAndJoin()

        idle()

        assertEquals("the caller completed as disconnected after it had cancelled", 0, cancelled.disconnects)
        assertNull(cancelled.failure)
        assertEquals("the caller that stayed was not told its service died", 1, kept.disconnects)
    }

    @Test
    fun anUnbindOfAnotherTagLeavesTheQueuedDeathAlone() = runTest {
        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)
        val dying = args("death-scoped-dying")
        val other = args("death-scoped-other")

        val (dyingCollector, dyingConnection) = boundAndConnected(backgroundScope, dying)
        val (otherCollector, _) = boundAndConnected(backgroundScope, other)

        dyingConnection.died()

        // A cancellation under a different service must not cancel the death queued for this one.
        otherCollector.job.cancelAndJoin()

        idle()

        assertEquals("a cancellation of another tag swallowed the death of the tag that died", 1, dyingCollector.disconnects)
        assertEquals("the caller that cancelled was told its own live service disconnected", 0, otherCollector.disconnects)
    }

    @Test
    fun aRebindWithoutAnUnbindStillReceivesTheDeathItOvertook() = runTest {
        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)
        val args = args("death-then-rebind-no-unbind")
        val (collector, connection) = boundAndConnected(backgroundScope, args)

        connection.died()

        // A new collection is not a cancellation: the caller still wants to know the binding it had ended.
        val rebound = RecordingCollector(backgroundScope, connection().userService(args))

        idle()

        assertEquals("a rebind swallowed the death of the binding it overtook", 1, collector.disconnects)
        assertEquals("the new collection was told about a death it never saw", 0, rebound.disconnects)
    }
}
