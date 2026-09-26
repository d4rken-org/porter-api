package eu.darken.porter.sdk

import android.os.Binder
import android.os.IBinder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.idle
import eu.darken.porter.sdk.UserServiceTestSupport.peek
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

/** One binder carries a death recipient per "connected" push, so its death is signalled repeatedly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDuplicateDeathRecipientTest {

    /** Keeps every recipient linked to it, so a test can signal the death one recipient at a time. */
    private class RecordingBinder : Binder() {

        val recipients = ArrayList<IBinder.DeathRecipient>()

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            recipients.add(recipient)
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
            recipients.remove(recipient)
    }

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

    @Test
    fun aSecondDeathSignalOnOneBinderLeavesALaterBindAlone() = runTest {
        Porter.onBinderReceived(FakePorterService(), UserServiceTestSupport.PACKAGE)
        val args = args("duplicate-death-recipient")
        val binder = RecordingBinder()

        // The server pushes "connected" for each accepted add while the service is alive, and each
        // push links a recipient of its own to the one binder.
        val first = RecordingCollector(backgroundScope, connection().userService(args))
        val connection = peek(args)
        assertNotNull(connection)
        connection!!.connected(binder)
        idle()

        val second = RecordingCollector(backgroundScope, connection().userService(args))
        connection.connected(binder)
        idle()

        assertEquals("the second connected push did not link a recipient of its own", 2, binder.recipients.size)
        // The same binder pushed twice is one service binder to a collector, so it is emitted once.
        assertEquals("the first caller was never connected", 1, first.connects)
        assertEquals("the second caller was never connected", 1, second.connects)

        binder.recipients[0].binderDied()

        // A bind lands between the two signals of the same binder's single death.
        val late = RecordingCollector(backgroundScope, connection().userService(args))

        binder.recipients[1].binderDied()
        idle()

        assertEquals("the caller that bound after the death was told its new binding died", 0, late.disconnects)
        assertEquals("the first caller was never told its service died", 1, first.disconnects)
        assertEquals("the second caller was never told its service died", 1, second.disconnects)

        val rebound = peek(args)
        assertNotNull("the second death signal dropped the binding that arrived after the death", rebound)
        assertNotSame("the later bind was handed back the binding the death retired", connection, rebound)

        rebound!!.connected(Binder())
        idle()
        assertEquals("the later caller never heard about its own binding", 1, late.connects)
    }
}
