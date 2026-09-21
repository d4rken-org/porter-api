package eu.darken.porter.sdk

import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.server.IPorterApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A permission revoked while a replacement was attaching must not survive the rollback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedF4RevokedPermissionTest {

    /** Reaches the server, waits for the test, and then refuses the client. */
    private class BlockingRefusingService : FakePorterService() {

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
            super.attach(application, args)
            entered.countDown()
            try {
                release.await()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
            throw SecurityException("not an attached client")
        }
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aRevocationDuringAFailedReplacementIsNotForgotten() {
        val serving = FakePorterService()
        serving.attachReply = permissionState(true, false)
        serving.selfPermission = false
        Porter.onBinderReceived(serving, PACKAGE)
        val connection = Porter.connection.value!!
        assertEquals(PermissionState.Granted, connection.checkPermission())

        val refused = BlockingRefusingService()
        val attaching = Thread({ Porter.onBinderReceived(refused, PACKAGE) }, "porter-attach")
        attaching.isDaemon = true
        attaching.start()
        assertTrue("the replacement never reached the server", refused.entered.await(5, TimeUnit.SECONDS))

        // Porter revokes the grant on the connection that is still serving calls.
        serving.application!!.dispatchPermissionStateChanged(permissionState(false, true))

        refused.release.countDown()
        attaching.join(TimeUnit.SECONDS.toMillis(5))

        assertSame("the serving connection was not restored", serving, Porter.connection.value!!.binder)
        assertEquals(
            "the revoked permission survived the rollback",
            PermissionState.Denied(permanentlyDenied = true), connection.checkPermission(),
        )
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"

        fun permissionState(granted: Boolean, shouldShowRationale: Boolean): Bundle = Bundle().apply {
            putBoolean(REPLY_PERMISSION_GRANTED, granted)
            putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale)
        }
    }
}
