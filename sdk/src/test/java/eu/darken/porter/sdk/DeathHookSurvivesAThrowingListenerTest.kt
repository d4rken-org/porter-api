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
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper

/**
 * A collector of [Porter.connection] that fails when the death is published takes down its own
 * collection and nothing else. The SDK's own re-fetch of the provider process's binder has to run
 * anyway: the app is left disconnected for the rest of its life otherwise, while the provider
 * process holds a binder that answers.
 *
 * This guards a property rather than pinning a defect: the SDK's re-fetch runs as a post-death hook
 * dispatched after the death is published, and it has to run whatever a collector did with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class DeathHookSurvivesAThrowingListenerTest {

    private lateinit var context: Context
    private val observer = ConnectionObserver()

    /** The binder the provider process attached, which this process can only reach by fetching. */
    private val providerProcessBinder = FakePorterService()

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // This process hosts no provider, so the SDK's built-in fetches are all it has.
        PorterApiProvider.enableMultiProcessSupport(false)
        ShadowContentResolver.registerProviderInternal(
            context.packageName + PorterProtocolDelivery.authoritySuffix,
            ProviderProcessStandIn(providerProcessBinder),
        )
    }

    @After
    fun teardown() {
        observer.close()
        Porter.resetForTest()
    }

    private fun declares(packageName: String, permission: String) {
        val info = PermissionInfo()
        info.name = permission
        info.packageName = packageName
        shadowOf(context.packageManager).addPermissionInfo(info)
    }

    @Test
    fun theRefetchStillRunsWhenAnAppDeadListenerThrows() {
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION)
        assertEquals(
            "the only installed manager is Shizuku's, so that is what this process selects",
            Porter.Selection.SHIZUKU, Porter.selectBackend(context),
        )

        // The connection this process is on, as an earlier fetch from the provider left it.
        val shizuku = FakeShizukuService()
        Porter.onBinderReceived(context, shizuku, context.packageName, PorterBackend.SHIZUKU)
        assertSame(
            "the Shizuku connection has to be published before the refused fetch runs",
            shizuku, Porter.connection.value?.binder,
        )

        // Everything this process does about binder delivery, as the SDK documents it.
        PorterApiProvider.requestBinderForNonProviderProcess(context)
        assertSame(
            "the provider process's Porter binder replaced a live Shizuku connection, which the session lock is supposed to refuse",
            shizuku, Porter.connection.value?.binder,
        )
        assertEquals("a refused delivery must not be attached to", 0, providerProcessBinder.attachCount)

        // Collected after the multi-process setup call, which is where an app that sets the SDK up
        // in its Application and observes it from a screen starts collecting.
        val failure = IllegalStateException("app collector failed")
        val seen = observer.observe { if (it == null) throw failure }

        deathRecipientOf(shizuku).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("the collector saw the death before it failed", listOf(shizuku, null), seen)
        assertEquals("the collector's own failure is what took its collection down", listOf<Throwable>(failure), observer.failures)

        assertEquals(
            "the app's collector threw, and the SDK's own re-fetch of the provider process's binder still has to run: nothing else fetches after a death, so skipping it leaves this process disconnected for as long as it lives while the provider process holds a binder that answers",
            1, providerProcessBinder.attachCount,
        )
        assertSame(
            "the re-fetched binder has to end up as the published connection",
            providerProcessBinder, Porter.connection.value?.binder,
        )
    }
}
