package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PermissionInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/**
 * What a delivery may do to a connection that is already live. Acceptance is decided under
 * `Porter.lock`: a binder on the backend this process did not select is refused there, so it
 * never becomes the published connection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedSelectionAcceptanceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
        declareShizukuProvider(context)
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun declares(packageName: String, permission: String) {
        val info = PermissionInfo()
        info.name = permission
        info.packageName = packageName
        shadowOf(context.packageManager).addPermissionInfo(info)
    }

    /** Selects Shizuku and publishes a live connection on it. */
    private fun liveShizukuConnection(): FakeShizukuService {
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION)
        assertEquals(
            "the only installed manager is Shizuku's, so that is what this process selects",
            Porter.Selection.SHIZUKU, Porter.selectBackend(context),
        )

        val shizuku = FakeShizukuService()
        Porter.onBinderReceived(context, shizuku, PACKAGE, PorterBackend.SHIZUKU)

        val current = Porter.connection.value!!
        assertSame("the Shizuku delivery has to be the published connection before the route runs", shizuku, current.binder)
        assertEquals("the published connection has to be on Shizuku before the route runs", PorterBackend.SHIZUKU, current.backend)
        return shizuku
    }

    /**
     * Route C. The two-argument [Porter.onBinderReceived] consults no selection at all, so what protects
     * a live connection on that path is the locked live-backend check: a Porter binder handed to it
     * while a Shizuku connection is live is refused rather than attached to.
     */
    @Test
    fun theUngatedOverloadDoesNotPublishPorterOverALiveShizukuConnection() {
        val shizuku = liveShizukuConnection()

        val porter = FakePorterService()
        Porter.onBinderReceived(porter, PACKAGE)

        val current = Porter.connection.value!!
        assertSame(
            "a Porter binder delivered through the ungated two-argument onBinderReceived" +
                " replaced the live Shizuku connection this process selected",
            shizuku, current.binder,
        )
        assertEquals("the published connection changed backend while it was live", PorterBackend.SHIZUKU, current.backend)
        assertEquals(
            "the Porter binder was attached to, so this process talked to a second server" +
                " on a backend it never selected",
            0, porter.attachCount,
        )
    }

    /**
     * Route B. A cross-process fetch on the other authority must not move this process's selection off
     * the backend it is connected on. [Porter.adoptBackend] leaves the selection alone while a
     * connection is live, so a later delivery on the other backend is still refused.
     */
    @Test
    fun adoptingABackendDoesNotMoveTheSelectionOffALiveConnection() {
        val shizuku = liveShizukuConnection()

        Porter.adoptBackend(PorterBackend.PORTER)

        assertEquals(
            "adoptBackend moved the selection to Porter while a Shizuku connection was" +
                " live, so this process now answers for a backend it is not connected on",
            Porter.Selection.SHIZUKU, Porter.selectBackend(context),
        )

        // What the moved selection then lets through: no package declares Porter's permission here,
        // so nothing but the moved selection can make this delivery acceptable.
        val porter = FakePorterService()
        Porter.onBinderReceived(context, porter, PACKAGE, PorterBackend.PORTER)

        val current = Porter.connection.value!!
        assertSame(
            "the selection adoptBackend moved let a Porter delivery replace the live Shizuku connection",
            shizuku, current.binder,
        )
        assertEquals("the published connection changed backend while it was live", PorterBackend.SHIZUKU, current.backend)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
