package eu.darken.porter.sdk

import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLooper

/** A collector that starts while a live connection is being replaced by one that dies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF8DeathRollbackStickyCatchUpTest {

    private val observer = ConnectionObserver()

    @After
    fun teardown() {
        observer.close()
        Porter.resetForTest()
    }

    @Test
    fun aStickyListenerIsToldAboutTheConnectionADeathRolledBackTo() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)

        val dying = BlockingService()
        val attaching = Thread({ Porter.onBinderReceived(dying, PACKAGE) }, "porter-attach")
        attaching.isDaemon = true
        attaching.start()

        val seen: List<Any?>
        try {
            assertTrue("the replacement never reached the server", dying.entered.await(5, TimeUnit.SECONDS))

            // The connection that is serving stays published while the replacement attaches, so a
            // collector starting now is told about it at once.
            seen = observer.observe()
            assertEquals("the collector was not told about the connection being replaced", listOf(serving), seen)

            // The replacement dies before it can publish, so the serving connection stays.
            val shadow: ShadowBinder = Shadow.extract(dying)
            val recipients = shadow.deathRecipients
            assertEquals("the attaching connection was not watched for death", 1, recipients.size)
            recipients[0].binderDied()
        } finally {
            dying.release.countDown()
            attaching.join(TimeUnit.SECONDS.toMillis(5))
        }
        ShadowLooper.shadowMainLooper().idle()

        assertSame("the serving connection is not the one Porter answers for", serving, Porter.connection.value?.binder)
        assertTrue("the serving connection does not answer", Porter.connection.value?.isAlive == true)
        assertEquals("the replacement's death was published over the live connection", listOf(serving), seen)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
