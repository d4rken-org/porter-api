package eu.darken.porter.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder

/** A connection restored after a failed replacement must still be watched for death. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF2RollbackMonitoringTest {

    private val observer = ConnectionObserver()

    @After
    fun teardown() {
        observer.close()
        Porter.resetForTest()
    }

    @Test
    fun theConnectionARollbackRestoresIsStillWatchedForDeath() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)
        val seen = observer.observe()

        val refused = FakePorterService()
        refused.attachFailure = SecurityException("not an attached client")
        Porter.onBinderReceived(refused, PACKAGE)

        assertSame("the serving connection was not restored", serving, Porter.connection.value?.binder)

        val shadow: ShadowBinder = Shadow.extract(serving)
        val recipients = shadow.deathRecipients
        assertEquals("the restored connection has no death recipient", 1, recipients.size)

        for (recipient in recipients) recipient.binderDied()

        assertEquals("the restored connection died unannounced", listOf(serving, null), seen)
        assertNull("a dead connection is still published", Porter.connection.value)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
