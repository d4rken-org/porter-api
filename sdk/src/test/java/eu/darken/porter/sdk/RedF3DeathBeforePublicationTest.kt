package eu.darken.porter.sdk

import android.os.Bundle
import eu.darken.porter.server.IPorterApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowLooper

/** A binder that dies between its attach reply and the publication of its connection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF3DeathBeforePublicationTest {

    /** Answers attach, then holds the reply on its way back until the test lets it through. */
    private class SlowReplyService : FakePorterService() {

        val replied = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
            val reply = super.attach(application, args)
            replied.countDown()
            release.await()
            return reply
        }
    }

    private val observer = ConnectionObserver()

    @After
    fun teardown() {
        observer.close()
        Porter.resetForTest()
    }

    @Test
    fun aBinderThatDiesBeforeItIsPublishedIsNotPublished() {
        val seen = observer.observe()

        val dying = SlowReplyService()
        val attaching = Thread({ Porter.onBinderReceived(dying, PACKAGE) }, "porter-attach")
        attaching.isDaemon = true
        attaching.start()
        assertTrue("the attach never reached the server", dying.replied.await(5, TimeUnit.SECONDS))

        // The server dies while its reply is still on its way back to the client.
        val shadow: ShadowBinder = Shadow.extract(dying)
        val recipients = shadow.deathRecipients
        assertEquals("the attaching connection was not watched for death", 1, recipients.size)
        recipients[0].binderDied()

        dying.release.countDown()
        attaching.join(TimeUnit.SECONDS.toMillis(5))
        ShadowLooper.shadowMainLooper().idle()

        assertNull("a connection whose binder had died was published", Porter.connection.value)
        assertEquals("a dead connection announced itself", listOf(null), seen)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
