package eu.darken.porter.sdk

import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Redelivering the binder the app already holds, while a replacement is attaching.
 *
 * This asserts the attach count only. An earlier draft also asserted that the redelivered binder
 * gained no second death recipient; that assertion was never reached when the defect was reproduced,
 * and it cannot hold under any variant of the fix, because publishing the replacement unlinks the
 * connection that handed over. The double-link half of this defect is therefore not covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF6RedeliveryDuringReplacementTest {

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun redeliveringTheHeldBinderDuringAReplacementAttachesItOnlyOnce() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)
        assertEquals(1, serving.attachCount)

        val replacement = BlockingService()
        val attaching = Thread({ Porter.onBinderReceived(replacement, PACKAGE) }, "porter-attach")
        attaching.isDaemon = true
        attaching.start()
        assertTrue("the replacement never reached the server", replacement.entered.await(5, TimeUnit.SECONDS))

        // Porter redelivers the binder this process already holds.
        Porter.onBinderReceived(serving, PACKAGE)

        replacement.release.countDown()
        attaching.join(TimeUnit.SECONDS.toMillis(5))

        assertEquals("the binder the app already held was attached again", 1, serving.attachCount)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
