package eu.darken.porter.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A collector that replaces the connection it is being told about and then fails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF11ThrowingListenerDuplicateUnlinkTest {

    private val observer = ConnectionObserver()

    @After
    fun teardown() {
        observer.close()
        Porter.resetForTest()
    }

    @Test
    fun aConnectionSupersededFromInsideItsOwnAnnouncementIsUnlinkedOnlyOnce() {
        val first = RemoteLikeService()
        val second = RemoteLikeService()

        var replaced = false
        val failure = RuntimeException("the app failed while handling the connection")
        observer.observe { connection ->
            if (replaced || connection?.binder !== first) return@observe
            replaced = true
            // Porter restarts while the app is handling the connection it just got, and the app
            // then fails on that connection.
            Porter.onBinderReceived(second, PACKAGE)
            throw failure
        }

        var escaped: Throwable? = null
        try {
            Porter.onBinderReceived(first, PACKAGE)
        } catch (t: Throwable) {
            escaped = t
        }

        assertTrue("the collector never replaced the connection", replaced)
        escaped?.let { throw AssertionError("giving up on the superseded connection unlinked it a second time", it) }
        assertEquals("the collector's own failure is the only one, and it stayed with the collector", listOf<Throwable>(failure), observer.failures)
        assertEquals("the superseded connection is unlinked exactly once", 1, first.unlinks)
        assertSame("the replacement is not the connection Porter answers for", second, Porter.connection.value?.binder)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
