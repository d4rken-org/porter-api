package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PermissionInfo
import android.os.IBinder
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
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
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper

/**
 * A collector on the main dispatcher is resumed by a looper turn rather than inline, so a death
 * published on the main thread reaches it one turn later. What the SDK does about that death has to
 * wait behind it: the app is told dead and then received whether it collects inline or on the main
 * dispatcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedDeathDispatchOrderingHandlerTest {

    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The binder the provider process attached, which this process can only reach by fetching. */
    private val providerProcessBinder = FakePorterService()

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
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
        scope.cancel()
        Porter.resetForTest()
    }

    private fun declares(packageName: String, permission: String) {
        val info = PermissionInfo()
        info.name = permission
        info.packageName = packageName
        shadowOf(context.packageManager).addPermissionInfo(info)
    }

    @Test
    fun aDeadListenerBoundToTheMainLooperIsStillToldBeforeTheReconnect() {
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

        // An app that collects on its UI dispatcher, which is the normal way to drive a screen from
        // the flow and is documented as choosing the thread, not the turn.
        val seen = Collections.synchronizedList(ArrayList<IBinder?>())
        scope.launch { Porter.connection.collect { seen.add(it?.binder) } }
        ShadowLooper.shadowMainLooper().idle()
        assertEquals("the collector starts from the live connection", listOf<IBinder?>(shizuku), seen)

        deathRecipientOf(shizuku).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals(
            "the app was told received before dead: collecting on the main dispatcher queues the collector, the SDK's own reaction to the death ran ahead of it, and the replacement announced itself before the queued collector saw the null, so flow-driven connection state settles on disconnected although a live binder is in hand",
            listOf(shizuku, null, providerProcessBinder), seen,
        )

        assertSame(
            "once everything has settled the provider process's binder is the published one",
            providerProcessBinder, Porter.connection.value?.binder,
        )
        assertEquals("the binder fetched after the death has to be attached to exactly once", 1, providerProcessBinder.attachCount)
    }
}
