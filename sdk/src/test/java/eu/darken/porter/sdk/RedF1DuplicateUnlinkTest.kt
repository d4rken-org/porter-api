package eu.darken.porter.sdk

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/** A connection that is superseded while it attaches must not be unlinked from death twice. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF1DuplicateUnlinkTest {

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun anAttachSupersededWhileItRunsUnlinksItsDeathRecipientOnlyOnce() {
        val serving = RemoteLikeService()
        Porter.onBinderReceived(serving, PACKAGE)

        val slow = BlockingRemoteLikeService()
        val escaped = AtomicReference<Throwable>()
        val attaching = Thread({
            try {
                Porter.onBinderReceived(slow, PACKAGE)
            } catch (t: Throwable) {
                escaped.set(t)
            }
        }, "porter-attach")
        attaching.isDaemon = true
        attaching.start()
        assertTrue("the replacement never reached the server", slow.entered.await(5, TimeUnit.SECONDS))

        // A third binder arrives and supersedes the one that is still attaching, unlinking it.
        Porter.onBinderReceived(RemoteLikeService(), PACKAGE)

        slow.release.countDown()
        attaching.join(TimeUnit.SECONDS.toMillis(5))

        escaped.get()?.let { throw it }
        assertEquals("the superseded connection is unlinked exactly once", 1, slow.unlinks)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
