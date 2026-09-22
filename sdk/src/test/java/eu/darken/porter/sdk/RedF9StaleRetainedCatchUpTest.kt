package eu.darken.porter.sdk

import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.Dispatchers

/** One of two stacked replacements fails while the other is still attaching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF9StaleRetainedCatchUpTest {

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

    @Test
    fun aFailedReplacementBehindAnotherOneTellsAStickyListenerOnlyOnce() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)

        val refused = BlockingRefusingService()
        val failing = Thread({ Porter.onBinderReceived(refused, PACKAGE) }, "porter-attach-refused")
        failing.isDaemon = true

        val replacing = BlockingService()
        val succeeding = Thread({ Porter.onBinderReceived(replacing, PACKAGE) }, "porter-attach-replacing")
        succeeding.isDaemon = true

        val seen: List<Any?>
        try {
            failing.start()
            assertTrue("the first replacement never reached the server", refused.entered.await(5, TimeUnit.SECONDS))
            succeeding.start()
            assertTrue("the second replacement never reached the server", replacing.entered.await(5, TimeUnit.SECONDS))

            // The serving connection is what is published while both replacements attach.
            seen = observer.observe()
            assertEquals("the collector was told something other than the serving connection", listOf(serving), seen)

            // The first replacement gives up; the second is still on its way in.
            refused.release.countDown()
            failing.join(TimeUnit.SECONDS.toMillis(5))

            // The second replacement publishes and announces itself.
            replacing.release.countDown()
            succeeding.join(TimeUnit.SECONDS.toMillis(5))
        } finally {
            refused.release.countDown()
            replacing.release.countDown()
            failing.join(TimeUnit.SECONDS.toMillis(5))
            succeeding.join(TimeUnit.SECONDS.toMillis(5))
        }
        ShadowLooper.shadowMainLooper().idle()

        assertSame("the second replacement is not the connection Porter answers for", replacing, Porter.connection.value?.binder)
        assertEquals(
            "the collector has to see the serving connection and then the arriving one, once each, with no gap for the failed replacement",
            listOf(serving, replacing), seen,
        )
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
