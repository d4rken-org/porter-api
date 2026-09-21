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
 * An app that starts collecting after [PorterApiProvider.requestBinderForNonProviderProcess] is
 * behind whatever that call registered for itself. A transition that ends connected still reaches
 * the app in that order, null and then the replacement, and the replacement is fetched on a later
 * looper turn rather than from inside the death dispatch, so connection state derived from the flow
 * does not settle on disconnected while a live replacement is in hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDeathBeforeReconnectTest {

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
    fun theDeathReachesTheAppBeforeTheReconnectThatFollowsIt() {
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
        // in its Application and observes it from a screen starts collecting. That puts it behind
        // the hook the setup call registered for itself.
        val seen = observer.observe()

        deathRecipientOf(shizuku).binderDied()
        val attachedDuringTheDeath = providerProcessBinder.attachCount
        ShadowLooper.shadowMainLooper().idle()

        assertEquals(
            "the reconnect must not run on the dispatching stack: nothing may be attached to before the death dispatch has returned to its caller",
            0, attachedDuringTheDeath,
        )
        assertEquals(
            "the app has to see the death and then the replacement: a replacement announced from inside the death dispatch, ahead of the null, would settle flow-driven connection state on disconnected although a live binder is in hand",
            listOf(shizuku, null, providerProcessBinder), seen,
        )

        assertSame(
            "once everything has settled the provider process's binder is the published one",
            providerProcessBinder, Porter.connection.value?.binder,
        )
        assertEquals("the binder fetched after the death has to be attached to exactly once", 1, providerProcessBinder.attachCount)
    }
}
