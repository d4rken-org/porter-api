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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A user service binding belongs to the connection that made it. The flows collected on a
 * connection end with that connection, and a server that replaces it is never handed a callback
 * the replaced server was given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterUserServiceConnectionScopeTest {

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aReplacementServerNeverReachesTheCollectorsOfTheServerItReplaced() = runTest {
        val first = FakePorterService()
        Porter.onBinderReceived(first, UserServiceTestSupport.PACKAGE)
        val args = args("across-servers")
        val onFirst = RecordingCollector(backgroundScope, connection().userService(args))
        val firstBinding = peek(args)
        assertNotNull(firstBinding)

        // The first server dies before it delivered a service binder.
        deathRecipientOf(first).binderDied()
        idle()
        assertTrue("the flow collected on the dead connection was left open", onFirst.completed)
        assertNull(onFirst.failure)

        val second = FakePorterService()
        Porter.onBinderReceived(second, UserServiceTestSupport.PACKAGE)
        val onSecond = RecordingCollector(backgroundScope, connection().userService(args))
        val secondBinding = peek(args)
        assertNotNull(secondBinding)
        assertNotSame("the second connection was handed the first one's binding", firstBinding, secondBinding)
        assertNotSame(
            "the second server was handed the callback registered with the first",
            first.userServiceConnection!!.asBinder(), second.userServiceConnection!!.asBinder(),
        )

        secondBinding!!.connected(Binder())
        idle()
        assertEquals(1, onSecond.connects)
        assertEquals("the second server's binder reached a collector of the first connection", 0, onFirst.connects)
    }

    @Test
    fun aFlowCompletesWhenItsConnectionIsReplaced() = runTest {
        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)
        val args = args("replaced")
        val collector = RecordingCollector(backgroundScope, connection().userService(args))
        peek(args)!!.connected(Binder())
        idle()
        assertEquals(1, collector.connects)

        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)
        idle()

        assertTrue("the flow outlived the connection it was collected on", collector.completed)
        assertNull(collector.failure)
        assertNull("the replacement inherited a binding of the connection it replaced", peek(args))
    }

    @Test
    fun aFlowCompletesWhenItsConnectionDies() = runTest {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        val args = args("died")
        val collector = RecordingCollector(backgroundScope, connection().userService(args))
        peek(args)!!.connected(Binder())
        idle()
        assertEquals(1, collector.connects)

        deathRecipientOf(fake).binderDied()
        idle()

        assertTrue("the flow outlived the connection it was collected on", collector.completed)
        assertNull(collector.failure)
    }

    @Test
    fun aFlowStartedOnALostConnectionCompletesWithoutAskingTheServer() = runTest {
        val first = FakePorterService()
        Porter.onBinderReceived(first, UserServiceTestSupport.PACKAGE)
        val lost = connection()
        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)

        val collector = RecordingCollector(backgroundScope, lost.userService(args("late")))

        assertTrue(collector.completed)
        assertNull(collector.failure)
        assertNull("a lost connection still asked its server for a binding", first.userServiceConnection)
    }
}
