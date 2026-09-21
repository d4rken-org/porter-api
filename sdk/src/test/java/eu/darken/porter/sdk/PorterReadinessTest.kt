package eu.darken.porter.sdk

import android.os.Bundle
import eu.darken.porter.server.IPorterApplication
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a reader sees in the connection flow while an attach is still running, and after one that went nowhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterReadinessTest {

    /** Reads the flow from inside attach, while the connection is neither yet nor no longer. */
    private class ReadingService : FakePorterService() {

        var seenDuringAttach: PorterConnection? = null
        var read = false

        override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
            seenDuringAttach = Porter.connection.value
            read = true
            return super.attach(application, args)
        }
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aReaderDuringAReplacingAttachSeesTheConnectionThatIsStillServing() {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)
        val servingConnection = Porter.connection.value

        val replacement = ReadingService()
        Porter.onBinderReceived(replacement, PACKAGE)

        assertSame(servingConnection, replacement.seenDuringAttach)
        assertSame(replacement, Porter.connection.value!!.binder)
    }

    @Test
    fun aReaderDuringTheFirstAttachSeesNoConnectionYet() {
        val first = ReadingService()

        Porter.onBinderReceived(first, PACKAGE)

        assertNull(first.seenDuringAttach)
        assertSame(first, Porter.connection.value!!.binder)
    }

    @Test
    fun anAttachThatFailedLeavesNothingToCatchUpOn() {
        val refused = FakePorterService()
        refused.attachFailure = SecurityException("not an attached client")
        Porter.onBinderReceived(refused, PACKAGE)

        assertNull(Porter.connection.value)
    }

    @Test
    fun aConnectionThatIsGoneLeavesNothingToCatchUpOn() {
        Porter.onBinderReceived(FakePorterService(), PACKAGE)
        Porter.onBinderReceived(null, PACKAGE)

        assertNull(Porter.connection.value)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
