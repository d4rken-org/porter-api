package eu.darken.porter.sdk

import android.os.DeadObjectException
import android.os.IBinder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLooper

/** What [Porter.state] says for each delivery outcome, and that it agrees with [Porter.connection]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterConnectionStateTest {

    private val observer = ConnectionObserver()

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        observer.close()
        Porter.resetForTest()
    }

    private fun refusingService() = FakePorterService().apply { protocolVersion = 1 }

    @Test
    fun nothingDeliveredIsDisconnected() {
        assertEquals(PorterConnectionState.Disconnected, Porter.state.value)
    }

    @Test
    fun anAttachedServerIsConnectedWithThePublishedConnection() {
        Porter.onBinderReceived(FakePorterService(), PACKAGE)

        val state = Porter.state.value as PorterConnectionState.Connected
        assertSame(Porter.connection.value, state.connection)
    }

    /** A replacement on the same backend and manager is a new state, not the old one again. */
    @Test
    fun aReplacementIsANewConnectedState() {
        Porter.onBinderReceived(FakePorterService(), PACKAGE)
        val first = Porter.state.value

        Porter.onBinderReceived(FakePorterService(), PACKAGE)

        assertNotEquals(first, Porter.state.value)
        assertSame(Porter.connection.value, (Porter.state.value as PorterConnectionState.Connected).connection)
    }

    @Test
    fun aRefusedServerIsIncompatibleWithTheReason() {
        Porter.onBinderReceived(refusingService(), PACKAGE)

        val state = Porter.state.value as PorterConnectionState.Incompatible
        assertEquals(Porter.incompatibility(), state.incompatibility)
        assertTrue(state.incompatibility.serverTooOld)
    }

    @Test
    fun aRefusedShizukuServerIsIncompatibleOnShizukusScale() {
        val refused = FakeShizukuService()
        refused.bindApplicationReply = FakeShizukuService.replyWithVersion(ShizukuProtocol.MINIMUM_VERSION - 1)

        Porter.onBinderReceived(refused, PACKAGE, PorterBackend.SHIZUKU)

        val why = (Porter.state.value as PorterConnectionState.Incompatible).incompatibility
        assertEquals(PorterBackend.SHIZUKU, why.backend)
        assertEquals(ShizukuProtocol.MINIMUM_VERSION - 1, why.serverVersion)
        assertTrue(why.serverTooOld)
    }

    @Test
    fun aRefusedReplacementLeavesTheServingConnection() {
        Porter.onBinderReceived(FakePorterService(), PACKAGE)
        val serving = Porter.state.value

        Porter.onBinderReceived(refusingService(), PACKAGE)

        assertSame(serving, Porter.state.value)
    }

    @Test
    fun theRefusedServersDeathDisconnects() {
        val refused = refusingService()
        Porter.onBinderReceived(refused, PACKAGE)

        deathRecipientOf(refused).binderDied()

        assertEquals(PorterConnectionState.Disconnected, Porter.state.value)
    }

    /** A refused server that is gone by the time the refusal would watch it leaves nothing to report. */
    @Test
    fun aRefusedServerAlreadyGoneWhenWatchedDisconnects() {
        val refused = object : FakePorterService() {
            var links = 0

            override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
                // The first link is the session's; the server is gone by the refusal's.
                if (++links > 1) throw DeadObjectException()
                super.linkToDeath(recipient, flags)
            }
        }
        refused.protocolVersion = 1

        Porter.onBinderReceived(refused, PACKAGE)

        assertEquals(2, refused.links)
        val shadow: ShadowBinder = Shadow.extract(refused)
        assertTrue(shadow.deathRecipients.isEmpty())
        assertEquals(PorterConnectionState.Disconnected, Porter.state.value)
        assertNull(Porter.incompatibility())
    }

    /** A death callback for a refusal that has since been replaced changes nothing. */
    @Test
    fun aStaleRefusalDeathLeavesTheNewerRefusal() {
        val old = refusingService()
        Porter.onBinderReceived(old, PACKAGE)
        val oldRecipient = deathRecipientOf(old)
        val newer = FakePorterService().apply { protocolVersion = 2 }
        Porter.onBinderReceived(newer, PACKAGE)

        oldRecipient.binderDied()

        assertEquals(Porter.incompatibility(), (Porter.state.value as PorterConnectionState.Incompatible).incompatibility)
        assertEquals(2, Porter.incompatibility()!!.serverVersion)
    }

    /**
     * A refusal that arrives after a newer server connected is dropped. While the newer connection
     * serves it would be hidden either way, so the check is after that connection dies.
     */
    @Test
    fun aRefusalOvertakenByANewerConnectionIsForgotten() {
        val slow = BlockingService()
        slow.protocolVersion = 1
        var workerFailure: Throwable? = null
        val attaching = Thread({
            try {
                Porter.onBinderReceived(slow, PACKAGE)
            } catch (t: Throwable) {
                workerFailure = t
            }
        }, "porter-attach")
        attaching.isDaemon = true
        attaching.start()
        val newer = FakePorterService()
        try {
            assertTrue("the slow attach never reached the server", slow.entered.await(5, TimeUnit.SECONDS))
            Porter.onBinderReceived(newer, PACKAGE)
        } finally {
            slow.release.countDown()
        }
        attaching.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("the slow attach never finished", attaching.isAlive)
        workerFailure?.let { throw AssertionError("the slow attach failed", it) }
        assertSame(newer, (Porter.state.value as PorterConnectionState.Connected).connection.binder)

        deathRecipientOf(newer).binderDied()
        ShadowLooper.idleMainLooper()

        assertEquals(PorterConnectionState.Disconnected, Porter.state.value)
    }

    @Test
    fun aCompatibleDeliveryReplacesTheRefusal() {
        Porter.onBinderReceived(refusingService(), PACKAGE)

        Porter.onBinderReceived(FakePorterService(), PACKAGE)

        assertSame(Porter.connection.value, (Porter.state.value as PorterConnectionState.Connected).connection)
    }

    @Test
    fun aNullDeliveryClearsTheRefusal() {
        Porter.onBinderReceived(refusingService(), PACKAGE)

        Porter.onBinderReceived(null, PACKAGE)

        assertEquals(PorterConnectionState.Disconnected, Porter.state.value)
    }

    /** A refused replacement is what the process has once the connection it could not replace dies. */
    @Test
    fun theServingConnectionsDeathRevealsTheRefusal() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)
        Porter.onBinderReceived(refusingService(), PACKAGE)

        deathRecipientOf(serving).binderDied()
        ShadowLooper.idleMainLooper()

        assertTrue(Porter.state.value is PorterConnectionState.Incompatible)
        assertEquals(null, Porter.connection.value)
    }

    @Test
    fun theServingConnectionsDeathWithoutARefusalDisconnects() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)

        deathRecipientOf(serving).binderDied()
        ShadowLooper.idleMainLooper()

        assertEquals(PorterConnectionState.Disconnected, Porter.state.value)
    }

    /** A collector that replaces the connection it is being told about leaves state on the replacement. */
    @Test
    fun aReplacementFromInsideAnAnnouncementLeavesStateOnTheReplacement() {
        val first = FakePorterService()
        val second = FakePorterService()
        var replaced = false
        observer.observe { connection ->
            if (replaced || connection?.binder !== first) return@observe
            replaced = true
            Porter.onBinderReceived(second, PACKAGE)
        }

        Porter.onBinderReceived(first, PACKAGE)

        assertTrue("the collector never replaced the connection", replaced)
        assertSame(second, Porter.connection.value?.binder)
        assertSame(Porter.connection.value, (Porter.state.value as PorterConnectionState.Connected).connection)
    }

    /** A collector that drops the connection it is being told about leaves state disconnected. */
    @Test
    fun aDropFromInsideAnAnnouncementLeavesStateDisconnected() {
        val first = FakePorterService()
        var dropped = false
        observer.observe { connection ->
            if (dropped || connection?.binder !== first) return@observe
            dropped = true
            Porter.onBinderReceived(null, PACKAGE)
        }

        Porter.onBinderReceived(first, PACKAGE)

        assertTrue("the collector never dropped the connection", dropped)
        assertNull(Porter.connection.value)
        assertEquals(PorterConnectionState.Disconnected, Porter.state.value)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
