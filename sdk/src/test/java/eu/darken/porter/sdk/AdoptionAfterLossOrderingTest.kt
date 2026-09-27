package eu.darken.porter.sdk

import android.content.Context
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import java.util.Collections
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Where taking a kept binder sits relative to the death that makes it the answer, and to a real
 * delivery that arrives in the meantime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class AdoptionAfterLossOrderingTest {

    private lateinit var context: Context
    private lateinit var push: ServerPushes
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The SDK's own attaches, held until a test runs them. */
    private val queued = Collections.synchronizedList(ArrayList<Runnable>())

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
        push = ServerPushes(context)
        installPorter(context)
        installShizuku(context)
    }

    @After
    fun teardown() {
        scope.cancel()
        Porter.resetForTest()
    }

    private fun runQueued() {
        val tasks = synchronized(queued) { queued.toList().also { queued.clear() } }
        tasks.forEach { it.run() }
    }

    private fun porterLostWithShizukuKept(porter: FakePorterService, shizuku: FakeShizukuService) {
        push.porter(porter)
        push.shizuku(shizuku)
        assertSame(porter, Porter.connection.value?.binder)
        assertSame(shizuku, Porter.keptForTest(PorterBackend.SHIZUKU))

        uninstallManager(context, PorterProtocol.MANAGER_APPLICATION_ID)
        deathRecipientOf(porter).binderDied()
    }

    @Test
    fun theAppSeesTheDeathBeforeTheAdoptionAndTheDeadConnectionIsLostBeforeItAttaches() {
        Porter.deliveryExecutor = Executor { it.run() }
        val porter = FakePorterService()
        push.porter(porter)
        val porterConnection = Porter.connection.value!!

        // Pending on the Porter connection until it is marked lost, which fails it.
        val waiting = CoroutineScope(Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) {
            porterConnection.requestPermission()
        }
        var lostBeforeAttach: Boolean? = null
        val shizuku = ObservedAttachShizukuService { lostBeforeAttach = waiting.isCompleted }
        push.shizuku(shizuku)
        assertEquals("a refused delivery must not be attached to", 0, shizuku.attachCount)

        // An app that collects on its UI dispatcher.
        val seen = Collections.synchronizedList(ArrayList<IBinder?>())
        scope.launch { Porter.connection.collect { seen.add(it?.binder) } }
        ShadowLooper.shadowMainLooper().idle()
        assertEquals(listOf<IBinder?>(porter), seen)

        uninstallManager(context, PorterProtocol.MANAGER_APPLICATION_ID)
        deathRecipientOf(porter).binderDied()
        val attachedDuringTheDeath = shizuku.attachCount
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("nothing may be attached to before the death dispatch has returned", 0, attachedDuringTheDeath)
        assertEquals("the dead connection has to be marked lost before its replacement attaches", true, lostBeforeAttach)
        assertEquals(
            "a collector on the main dispatcher has to see the death, and then the adopted connection",
            listOf(porter, null, shizuku), seen,
        )
        assertEquals(1, shizuku.attachCount)
    }

    @Test
    fun aDeliveryPublishedBeforeTheAdoptionRunsIsTheConnection() {
        Porter.deliveryExecutor = Executor { queued.add(it) }
        val porter = FakePorterService()
        val kept = FakeShizukuService()
        porterLostWithShizukuKept(porter, kept)
        ShadowLooper.shadowMainLooper().idle()
        assertEquals("the adoption waits on the SDK's own thread", 1, queued.size)

        // The Shizuku server restarted and pushed again.
        val fresh = FakeShizukuService()
        push.shizuku(fresh)
        assertSame(fresh, Porter.connection.value?.binder)

        runQueued()

        assertSame("the delivery that arrived is the connection", fresh, Porter.connection.value?.binder)
        assertEquals("the kept binder must not be attached to", 0, kept.attachCount)
        assertEquals(1, fresh.attachCount)
        assertSame("an adoption that stood down leaves the slot as it was", kept, Porter.keptForTest(PorterBackend.SHIZUKU))
    }

    @Test
    fun aDeliveryStillAttachingWhenTheAdoptionRunsIsTheConnection() {
        Porter.deliveryExecutor = Executor { queued.add(it) }
        val porter = FakePorterService()
        val kept = FakeShizukuService()
        porterLostWithShizukuKept(porter, kept)
        ShadowLooper.shadowMainLooper().idle()
        assertEquals("the adoption waits on the SDK's own thread", 1, queued.size)

        val fresh = BlockingShizukuService()
        val delivering = thread { push.shizuku(fresh) }
        try {
            assertTrue("the fresh delivery never reached its attach", fresh.entered.await(10, TimeUnit.SECONDS))

            runQueued()
            assertEquals("the kept binder must not be attached to while a delivery is attaching", 0, kept.attachCount)
        } finally {
            fresh.release.countDown()
            delivering.join(10_000)
        }

        assertSame("the delivery that arrived is the connection", fresh, Porter.connection.value?.binder)
        assertEquals("the kept binder must not be attached to", 0, kept.attachCount)
        assertEquals(1, fresh.attachCount)
    }
}
