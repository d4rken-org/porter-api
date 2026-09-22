package eu.darken.porter.sdk

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import moe.shizuku.server.IShizukuApplication
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLog
import kotlinx.coroutines.Dispatchers

/**
 * A Shizuku server that dies while a client is waiting for the attach state it answers nothing for.
 * The wait is on a latch no reply will ever count down, and it runs on the thread the delivery
 * arrived on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedAttachWaitDeadBinderTest {

    private val callbacks = object : PorterWire.Callbacks {
        override fun onRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        }

        override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
        }
    }

    /** Records the attach as its parent does, and says when the server has been asked. */
    private class AttachSignalService : FakeShizukuService() {

        val asked = CountDownLatch(1)

        override fun attachApplication(application: IShizukuApplication, args: Bundle) {
            super.attachApplication(application, args)
            asked.countDown()
        }
    }

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        ShadowLog.stream = null
        Porter.resetForTest()
    }

    @Test
    fun theAttachWaitIsWokenByTheDeathOfTheServerItIsWaitingOn() {
        val silent = FakeShizukuService()
        silent.suppressBindApplication = true
        val wire = ShizukuProtocolWire(silent, callbacks, ATTACH_TIMEOUT_MS)

        val thrown = AtomicReference<Throwable>()
        val attach = attaching {
            try {
                wire.attach(PACKAGE)
            } catch (e: Throwable) {
                thrown.set(e)
            }
        }

        wire.onPeerDied()

        attach.join(JOIN_MS)
        assertFalse(
            "the attach was still waiting ${JOIN_MS}ms after the server died, so the" +
                " death was recorded without releasing the wait",
            attach.isAlive,
        )
        val failure = thrown.get()
        assertNotNull("a wire whose server died answered the attach instead of failing it", failure)
        assertTrue(
            "the failure does not name the death: ${failure.message}",
            failure.message!!.contains("died before it answered attach"),
        )
    }

    @Test
    fun aSessionThatDiesWhileAttachingTellsTheWireItIsWaitingOn() {
        val context: Context = RuntimeEnvironment.getApplication()
        Porter.selectBackendForTest(Porter.Selection.SHIZUKU)
        val dying = AttachSignalService()
        dying.suppressBindApplication = true

        // The failure is swallowed into a log line on the delivery thread, so that is where it can
        // be read; a device sees the same line and nothing else.
        val logged = ByteArrayOutputStream()
        ShadowLog.stream = PrintStream(logged)

        val delivery = attaching { Porter.onBinderReceived(context, dying, PACKAGE, PorterBackend.SHIZUKU) }
        assertTrue("the delivery never reached the server", dying.asked.await(JOIN_MS, TimeUnit.MILLISECONDS))

        deathRecipientOf(dying).binderDied()

        delivery.join(JOIN_MS)
        assertFalse("the delivery thread was still attaching ${JOIN_MS}ms after the binder died", delivery.isAlive)
        val log = logged.toString()
        assertTrue(
            "the death of the binder being attached to was not what ended the attach: $log",
            log.contains("died before it answered attach"),
        )
        assertFalse(
            "the attach ran to its timeout although its binder had already died: $log",
            log.contains("did not answer attach within"),
        )
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"

        /** Long enough that waiting it out cannot be mistaken for the wait being woken. */
        val ATTACH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60)
        val JOIN_MS = TimeUnit.SECONDS.toMillis(5)

        fun deathRecipientOf(binder: IBinder): IBinder.DeathRecipient {
            val shadow: ShadowBinder = Shadow.extract(binder)
            val recipients = shadow.deathRecipients
            assertEquals("one connection links one recipient", 1, recipients.size)
            return recipients[0]
        }

        fun attaching(body: () -> Unit): Thread {
            val thread = Thread(body, "porter-attach")
            thread.isDaemon = true
            thread.start()
            return thread
        }
    }
}
