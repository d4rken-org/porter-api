package eu.darken.porter.sdk

import java.io.OutputStream
import java.io.PrintStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

/**
 * A connection that dies right after it is published. Publication and announcement are one step
 * now, so what is left of the window is the death arriving before the attach call has returned to
 * its caller: it has to be published as a death, not leave the dead connection in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF10DeathBetweenPublicationAndAnnouncementTest {

    /**
     * The window under test is the handful of statements between publishing a connection and the
     * attach call returning. It holds no lock and makes no binder call, so the one point a test can
     * reach into it is the "attached" log line. Firing the death from there keeps the whole test on
     * one thread: there is no race to lose and no timeout to wait out.
     */
    private class LogTrigger(private val marker: String, private val action: () -> Unit) : OutputStream() {

        private val written = StringBuilder()
        private var fired = false

        override fun write(b: Int) {
            written.append((b and 0xff).toChar())
            if (!fired && written.indexOf(marker) >= 0) {
                fired = true
                action()
            }
        }
    }

    private val observer = ConnectionObserver()

    @After
    fun teardown() {
        ShadowLog.stream = null
        observer.close()
        Porter.resetForTest()
    }

    @Test
    fun aConnectionThatDiesBeforeItIsAnnouncedIsNotAnnounced() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)

        val seen = observer.observe()

        val replacement = FakePorterService()
        var killed = false
        ShadowLog.stream = PrintStream(LogTrigger("attached, connection") {
            val shadow: ShadowBinder = Shadow.extract(replacement)
            val recipients = ArrayList(shadow.deathRecipients)
            killed = recipients.isNotEmpty()
            for (recipient in recipients) recipient.binderDied()
        })
        try {
            Porter.onBinderReceived(replacement, PACKAGE)
        } finally {
            ShadowLog.stream = null
        }
        ShadowLooper.shadowMainLooper().idle()

        assertTrue("the published connection was never killed, so nothing was under test", killed)
        assertNull("a connection that died right after publication stayed published", Porter.connection.value)
        assertEquals(
            "the death has to be published as a death, behind the connection it ended",
            listOf(serving, replacement, null), seen,
        )
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
