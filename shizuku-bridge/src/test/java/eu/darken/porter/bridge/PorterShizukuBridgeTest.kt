package eu.darken.porter.bridge

import android.os.Binder
import android.os.IBinder
import eu.darken.porter.sdk.Porter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/** What upstream holds as Porter's connections come and go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterShizukuBridgeTest {

    private val scopes = ArrayList<CoroutineScope>()

    /** The binder upstream held at each received-listener run. */
    private val receivedBy = java.util.Collections.synchronizedList(ArrayList<IBinder?>())
    private val recorder = Shizuku.OnBinderReceivedListener { receivedBy += Shizuku.getBinder() }

    @Before
    fun setup() {
        ShadowLooper.shadowMainLooper().idle()
        assertEquals("replies credited before this test", 0, ShizukuServiceAdapter.ownReplies.get())
        Shizuku.addBinderReceivedListener(recorder)
    }

    @After
    fun teardown() {
        scopes.forEach { it.cancel() }
        until { !Shizuku.pingBinder() }
        settleUpstream()
        Shizuku.onBinderReceived(null, null)
        ShadowLooper.shadowMainLooper().idle()
        Shizuku.removeBinderReceivedListener(recorder)
        Porter.resetForTest()
        assertEquals("replies still credited after this test", 0, ShizukuServiceAdapter.ownReplies.get())
    }

    @Test
    fun aReplacementIsServedAndTheConnectionBeforeItRetired() {
        PorterShizukuBridge.start(scope())
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        val first = served()

        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        until { Shizuku.getBinder() !== first }
        served()

        assertFalse(first.pingBinder())
    }

    @Test
    fun endingTheScopeRetiresTheConnectionAndAStartAfterItServesAgain() {
        val first = scope()
        PorterShizukuBridge.start(first)
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        served()

        first.cancel()
        until { !Shizuku.pingBinder() }

        PorterShizukuBridge.start(scope())
        served()
    }

    @Test
    fun aStartRightAfterTheScopeEndedIsNotUndoneByTheRetirementBeforeIt() {
        val first = scope()
        PorterShizukuBridge.start(first)
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        val retired = served()

        first.cancel()
        PorterShizukuBridge.start(scope())
        until { Shizuku.getBinder() !== retired }
        val serving = served()
        settleUpstream()

        assertSame(serving, Shizuku.getBinder())
    }

    @Test
    fun aServerBinderDeliveredWhilePorterIsServedGivesWayToPorter() {
        PorterShizukuBridge.start(scope())
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        val porter = served()
        val rival = RivalShizukuServer()

        // What upstream's provider does with a Shizuku server's binder.
        Shizuku.onBinderReceived(rival, PACKAGE)
        rival.reply()

        until { Shizuku.getBinder() === porter }
        served()
        assertEquals(2000, Shizuku.getUid())
    }

    @Test
    fun aServersLateAttachReplyDoesNotOutlastPorter() {
        PorterShizukuBridge.start(scope())
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        served()
        val rival = RivalShizukuServer()
        Shizuku.onBinderReceived(rival, PACKAGE)

        // Porter's next connection takes the binder back before the server's reply arrives.
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        until { Shizuku.getBinder() !== rival }
        val porter = served()
        rival.reply()

        until { ShizukuServiceAdapter.ownReplies.get() == 0 && Shizuku.getUid() == 2000 }
        settleUpstream()
        assertSame(porter, Shizuku.getBinder())
        assertEquals(2000, Shizuku.getUid())
        assertEquals(Porter.connection.value!!.seLinuxContext, Shizuku.getSELinuxContext())
    }

    @Test
    fun aServerReplyBehindAnUntakenPorterReplySettlesOnPorter() {
        PorterShizukuBridge.start(scope())
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        val first = served()
        val rival = RivalShizukuServer()

        // Off the main thread, where every listener run waits until the test drains it: Porter's
        // reply is credited and not yet taken when the server's reply overwrites it.
        offMainHeld {
            Shizuku.onBinderReceived(rival, PACKAGE)
            Porter.onBinderReceived(FakePorterServer(), PACKAGE)
            val deadline = System.currentTimeMillis() + 5_000
            while (Shizuku.getBinder() === rival || Shizuku.getBinder() === first || ShizukuServiceAdapter.ownReplies.get() == 0) {
                check(System.currentTimeMillis() < deadline) { "Porter's next connection did not reply" }
                Thread.sleep(5)
            }
            // The credit goes up just before the reply; this lets the reply itself land first.
            ShizukuServiceAdapter.UPSTREAM.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(2000, Shizuku.getUid())
            rival.reply()
            assertEquals(RivalShizukuServer.ROOT, Shizuku.getUid())
        }

        served()
        assertEquals(Porter.connection.value!!.seLinuxContext, Shizuku.getSELinuxContext())
    }

    @Test
    fun aServerBeforeApi11IsLeftToUpstream() {
        PorterShizukuBridge.start(scope())
        Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        served()
        // Answers neither attach, as a server before API 11 does not.
        val old = Binder()
        try {
            Shizuku.onBinderReceived(old, PACKAGE)
            assertTrue(Shizuku.isPreV11())
            settleUpstream()
            settleUpstream()

            assertSame(old, Shizuku.getBinder())
        } finally {
            // Upstream never leaves this mode by itself, and every later test shares it.
            Shizuku::class.java.getDeclaredField("preV11").apply { isAccessible = true }.setBoolean(null, false)
            Shizuku.onBinderReceived(null, null)
        }
    }

    /**
     * Waits until upstream holds a live Porter binder, has run its received-listeners for it, and
     * has no Porter reply left to take.
     */
    private fun served(): IBinder {
        until {
            val binder = Shizuku.getBinder()
            binder is ShizukuServiceAdapter && binder.pingBinder() && receivedBy.lastOrNull() === binder &&
                ShizukuServiceAdapter.ownReplies.get() == 0
        }
        settleUpstream()
        val binder = assertNotNullAndGet(Shizuku.getBinder())
        assertTrue(binder is ShizukuServiceAdapter && binder.pingBinder())
        assertSame(binder, receivedBy.last())
        assertEquals(0, ShizukuServiceAdapter.ownReplies.get())
        assertEquals(2000, Shizuku.getUid())
        return binder
    }

    /** Runs [block] on a thread of its own without running the main looper, and rethrows what it threw. */
    private fun offMainHeld(block: () -> Unit) {
        val result = java.util.concurrent.atomic.AtomicReference<Result<Unit>?>()
        val worker = Thread { result.set(runCatching(block)) }
        worker.start()
        worker.join(10_000)
        check(!worker.isAlive) { "the worker did not finish" }
        result.get()!!.getOrThrow()
    }

    /**
     * Runs what adapters queued for upstream, then what upstream posted to the main thread.
     * Never called on [ShizukuServiceAdapter.UPSTREAM] itself.
     */
    private fun settleUpstream() {
        ShizukuServiceAdapter.UPSTREAM.submit {}.get(5, TimeUnit.SECONDS)
        ShizukuServiceAdapter.UPSTREAM.submit {}.get(5, TimeUnit.SECONDS)
        ShadowLooper.shadowMainLooper().idle()
    }

    private fun scope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

    private fun <T : Any> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** Waits for [condition] while running the main looper, where upstream reports to the app. */
    private fun until(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            ShadowLooper.shadowMainLooper().idle()
            Thread.sleep(5)
        }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.bridge.test"
    }
}
