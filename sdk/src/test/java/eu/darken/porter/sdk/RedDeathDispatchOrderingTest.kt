package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PermissionInfo
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.Dispatchers

/**
 * A death that arrives on a binder thread is published from that thread, and what the SDK itself
 * does about it is queued to the main thread afterwards. The main thread is free to run at any
 * point in between, so the app is told dead before it is told received whichever thread the death
 * arrived on and whenever the main thread got to run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDeathDispatchOrderingTest {

    private lateinit var context: Context
    private val observer = ConnectionObserver()

    /** The binder the provider process attached, which this process can only reach by fetching. */
    private val providerProcessBinder = FakePorterService()

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
        declareShizukuProvider(context)
        // This process hosts no provider, so the SDK's built-in fetches are all it has; they run
        // inline, where the assertions can see them.
        Porter.deliveryExecutor = Executor { it.run() }
        // The test app declares the SDK's provider in its own process; this one is another.
        ShadowApplication.setProcessName(context.packageName + ":secondary")
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
    fun theReplacementIsNotAnnouncedMidwayThroughTheDeathDispatch() {
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

        val seen = observer.observe()

        val recipient = deathRecipientOf(shizuku)
        val binderThread = Thread({ recipient.binderDied() }, "binder-death")
        binderThread.start()
        binderThread.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse("the death dispatch has to finish within the timeout", binderThread.isAlive)

        assertEquals(
            "the death has to be published before the dispatching thread returns, and nothing may be attached to from it: the re-fetch belongs to the main thread",
            listOf(shizuku, null), seen,
        )
        assertEquals("nothing is attached to from the binder thread", 0, providerProcessBinder.attachCount)

        // The main thread, doing what a main thread does whenever it is not busy.
        ShadowLooper.shadowMainLooper().idle()

        assertEquals(
            "the app was told received before dead: the replacement announced itself ahead of the death, so flow-driven connection state settles on disconnected with a live binder in hand",
            listOf(shizuku, null, providerProcessBinder), seen,
        )

        assertSame(
            "once everything has settled the provider process's binder is the published one",
            providerProcessBinder, Porter.connection.value?.binder,
        )
        assertEquals("the binder fetched after the death has to be attached to exactly once", 1, providerProcessBinder.attachCount)
    }
}
