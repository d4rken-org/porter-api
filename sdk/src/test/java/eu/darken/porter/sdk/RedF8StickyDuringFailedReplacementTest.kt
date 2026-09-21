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
import org.robolectric.shadows.ShadowLooper

/** A collector that starts while a live connection is being replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF8StickyDuringFailedReplacementTest {

    private val observer = ConnectionObserver()

    @After
    fun teardown() {
        observer.close()
        Porter.resetForTest()
    }

    @Test
    fun aStickyListenerRegisteredDuringAFailedReplacementIsCalled() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)

        val refused = BlockingRefusingService()
        val attaching = Thread({ Porter.onBinderReceived(refused, PACKAGE) }, "porter-attach")
        attaching.isDaemon = true
        attaching.start()
        assertTrue("the replacement never reached the server", refused.entered.await(5, TimeUnit.SECONDS))

        val seen = observer.observe()

        refused.release.countDown()
        attaching.join(TimeUnit.SECONDS.toMillis(5))
        ShadowLooper.shadowMainLooper().idle()

        assertSame("the serving connection was not restored", serving, Porter.connection.value?.binder)
        assertTrue("the restored connection does not answer", Porter.connection.value?.isAlive == true)
        assertEquals("the collector was never told about the live connection, or was told of a gap", listOf(serving), seen)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
