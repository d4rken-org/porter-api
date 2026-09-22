package eu.darken.porter.sdk

import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import kotlinx.coroutines.runBlocking

/** What a connection that has been replaced or has died may still do to the one that is current. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterSessionTest {

    private val recorder = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        recorder.cancel()
        Porter.resetForTest()
    }

    /** Every value the connection flow publishes from now on, the current one first. */
    private fun published(): List<PorterConnection?> {
        val seen = ArrayList<PorterConnection?>()
        recorder.launch { Porter.connection.collect { seen.add(it) } }
        return seen
    }

    @Test
    fun aDeathNotificationForASupersededConnectionLeavesTheCurrentOne() = runBlocking<Unit> {
        val first = FakePorterService()
        Porter.onBinderReceived(first, PACKAGE)
        val firstDeath = deathRecipientOf(first)
        val seen = published()

        val second = FakePorterService()
        Porter.onBinderReceived(second, PACKAGE)
        firstDeath.binderDied()

        val current = Porter.connection.value!!
        assertTrue(current.isAlive())
        assertSame(second, current.binder)
        assertFalse("a death of the superseded connection must not be published", seen.contains(null))
    }

    @Test
    fun aDeathNotificationForTheCurrentConnectionTearsItDownOnce() {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        val death = deathRecipientOf(fake)
        val connection = Porter.connection.value
        val seen = published()

        death.binderDied()
        death.binderDied()

        assertNull(Porter.connection.value)
        assertEquals(listOf(connection, null), seen)
    }

    @Test
    fun anAttachThatFailsLeavesTheConnectionItCouldNotReplace() = runBlocking<Unit> {
        val serving = FakePorterService()
        serving.attachReply = permissionState(true, false)
        Porter.onBinderReceived(serving, PACKAGE)

        val refused = FakePorterService()
        refused.attachFailure = SecurityException("not an attached client")
        Porter.onBinderReceived(refused, PACKAGE)

        val current = Porter.connection.value!!
        assertTrue(current.isAlive())
        assertSame(serving, current.binder)
        assertEquals(PermissionState.Granted, current.checkPermission())
    }

    @Test
    fun aSupersededConnectionCannotChangeThePermissionState() = runBlocking<Unit> {
        val first = FakePorterService()
        Porter.onBinderReceived(first, PACKAGE)
        val firstApplication = first.application!!

        val second = FakePorterService()
        second.attachReply = permissionState(true, false)
        Porter.onBinderReceived(second, PACKAGE)
        firstApplication.dispatchPermissionStateChanged(permissionState(false, true))

        val current = Porter.connection.value!!
        assertEquals(PermissionState.Granted, current.checkPermission())
        assertEquals(PermissionState.Granted, current.permission.value)
        assertEquals("the current connection was never asked", 0, second.selfPermissionQueries)
    }

    @Test
    fun aSupersededConnectionCannotDeliverAPermissionResult() = runTest {
        val first = FakePorterService()
        Porter.onBinderReceived(first, PACKAGE)
        val firstApplication = first.application!!
        val second = FakePorterService()
        Porter.onBinderReceived(second, PACKAGE)
        val current = Porter.connection.value!!

        val result = async(start = CoroutineStart.UNDISPATCHED) { current.requestPermission() }
        val data = Bundle()
        data.putBoolean(PERMISSION_RESULT_ALLOWED, true)
        firstApplication.dispatchRequestPermissionResult(second.requestedPermissionCode, data)

        assertTrue("a result from the superseded connection answered the current one's request", result.isActive)

        second.application!!.dispatchRequestPermissionResult(second.requestedPermissionCode, data)

        assertEquals(PermissionState.Granted, result.await())
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"

        fun deathRecipientOf(fake: FakePorterService): IBinder.DeathRecipient {
            val shadow: ShadowBinder = Shadow.extract(fake)
            val recipients = shadow.deathRecipients
            assertEquals("one connection links one recipient", 1, recipients.size)
            return recipients[0]
        }

        fun permissionState(granted: Boolean, shouldShowRationale: Boolean): Bundle = Bundle().apply {
            putBoolean(REPLY_PERMISSION_GRANTED, granted)
            putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale)
        }
    }
}
