package eu.darken.porter.sdk

import android.content.Context
import eu.darken.porter.protocol.PorterProtocol
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.Dispatchers

/**
 * The provider process is connected on one backend, refused a delivery on the other, and then loses
 * its connection because that manager was uninstalled. The other backend's server pushed once and
 * will not push again while this process runs, so the binder it pushed is the only way back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedAdoptARefusedBinderOnLossTest {

    private lateinit var context: Context
    private lateinit var push: ServerPushes

    @Before
    fun setup() {
        // Server calls and the SDK's own attaches run inline, so each step below has happened when
        // the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        Porter.deliveryExecutor = Executor { it.run() }
        context = RuntimeEnvironment.getApplication()
        push = ServerPushes(context)
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun uninstallPorter() = uninstallManager(context, PorterProtocol.MANAGER_APPLICATION_ID)

    private fun connectedOnPorterWithShizukuRefused(porter: FakePorterService, shizuku: FakeShizukuService) {
        installPorter(context)
        installShizuku(context)

        push.porter(porter)
        assertSame("Porter is installed, so its delivery is the connection", porter, Porter.connection.value?.binder)

        push.shizuku(shizuku)
        assertSame("the live Porter connection stays the answer", porter, Porter.connection.value?.binder)
        assertEquals("a refused delivery must not be attached to", 0, shizuku.attachCount)
    }

    @Test
    fun aShizukuBinderRefusedWhileConnectedOnPorterIsAdoptedOnceThatConnectionDies() {
        val porter = FakePorterService()
        val shizuku = FakeShizukuService()
        connectedOnPorterWithShizukuRefused(porter, shizuku)

        val announcedBefore = announcements()
        uninstallPorter()
        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertSame(
            "no Shizuku connection published after the death: the Shizuku server pushed once, while Porter was connected, and pushes no more",
            shizuku, Porter.connection.value?.binder,
        )
        assertEquals("the kept binder has to be attached to exactly once", 1, shizuku.attachCount)
        assertEquals("the app's other processes have to be told, as after a delivery", announcedBefore + 1, announcements())
        // Also what deathRecipientOf requires: the connection's recipient is the binder's only one.
        assertSame("the adopted binder is watched by its connection alone", Porter.currentForTest()!!.deathRecipient, deathRecipientOf(shizuku))
        assertNull("an adopted binder is no longer kept", Porter.keptForTest(PorterBackend.SHIZUKU))
    }

    @Test
    fun aShizukuBinderRefusedBeforePorterPublishedIsAdoptedOnceThatConnectionDies() {
        installPorter(context)
        installShizuku(context)

        // Both servers push at process start; Shizuku's arrives while Porter's attach is in flight.
        val shizuku = FakeShizukuService()
        push.shizuku(shizuku)
        assertEquals("Porter is installed, so a Shizuku delivery is refused by selection", 0, shizuku.attachCount)

        val porter = FakePorterService()
        push.porter(porter)
        assertSame("Porter's delivery is the connection", porter, Porter.connection.value?.binder)

        val announcedBefore = announcements()
        uninstallPorter()
        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertSame(
            "no Shizuku connection published after the death: the Shizuku server pushed once, before Porter's connection published, and pushes no more",
            shizuku, Porter.connection.value?.binder,
        )
        assertEquals("the kept binder has to be attached to exactly once", 1, shizuku.attachCount)
        assertEquals("the app's other processes have to be told, as after a delivery", announcedBefore + 1, announcements())
    }

    @Test
    fun aPorterBinderRefusedWhileConnectedOnShizukuIsAdoptedOnceThatConnectionDies() {
        installShizuku(context)
        val shizuku = FakeShizukuService()
        push.shizuku(shizuku)
        assertSame("only Shizuku is installed, so its delivery is the connection", shizuku, Porter.connection.value?.binder)

        installPorter(context)
        val porter = FakePorterService()
        push.porter(porter)
        assertSame("a live connection keeps its backend across an install", shizuku, Porter.connection.value?.binder)
        assertEquals("a refused delivery must not be attached to", 0, porter.attachCount)

        val announcedBefore = announcements()
        deathRecipientOf(shizuku).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertSame("Porter is installed, so the process selects it again and takes its kept binder", porter, Porter.connection.value?.binder)
        assertEquals("the kept binder has to be attached to exactly once", 1, porter.attachCount)
        assertEquals("the app's other processes have to be told, as after a delivery", announcedBefore + 1, announcements())
    }

    @Test
    fun aPorterServerThatDiesWhilePorterStaysInstalledLeavesTheKeptShizukuBinderAlone() {
        val porter = FakePorterService()
        val shizuku = FakeShizukuService()
        connectedOnPorterWithShizukuRefused(porter, shizuku)

        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertNull("Porter is still installed, so the process waits for its server's next push", Porter.connection.value)
        assertEquals("the kept Shizuku binder must not be attached to", 0, shizuku.attachCount)
        assertEquals(Porter.Selection.PORTER, Porter.selectBackend(context))
        assertSame("still kept, for a later loss of Porter", shizuku, Porter.keptForTest(PorterBackend.SHIZUKU))
    }

    @Test
    fun aKeptBinderWhoseServerDiedBeforeTheLossIsNotAttached() {
        val porter = FakePorterService()
        val shizuku = StoppableShizukuService()
        connectedOnPorterWithShizukuRefused(porter, shizuku)

        shizuku.answering = false
        deathRecipientOf(shizuku).binderDied()
        assertNull("a kept binder's death empties its slot", Porter.keptForTest(PorterBackend.SHIZUKU))
        assertTrue(
            "and unlinks what watched it",
            (Shadow.extract(shizuku) as ShadowBinder).deathRecipients.isEmpty(),
        )

        uninstallPorter()
        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertNull(Porter.connection.value)
        assertEquals("a dead kept binder must not be attached to", 0, shizuku.attachCount)
    }

    @Test
    fun aKeptBinderThatNoLongerAnswersIsNotAttached() {
        val porter = FakePorterService()
        val shizuku = StoppableShizukuService()
        connectedOnPorterWithShizukuRefused(porter, shizuku)

        // Its server has gone, and the death notification has not arrived yet.
        shizuku.answering = false
        uninstallPorter()
        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertNull(Porter.connection.value)
        assertEquals("a kept binder that does not answer must not be attached to", 0, shizuku.attachCount)
    }

    @Test
    fun aShizukuPushArrivingAfterPorterStoppedAnsweringButBeforeItsDeathIsAdoptedOnThatDeath() {
        installPorter(context)
        installShizuku(context)
        val porter = StoppablePorterService()
        push.porter(porter)
        assertSame(porter, Porter.connection.value?.binder)

        porter.answering = false
        val shizuku = FakeShizukuService()
        push.shizuku(shizuku)
        assertSame("the published connection is Porter's until its death arrives", porter, Porter.connection.value?.binder)
        assertEquals("a refused delivery must not be attached to", 0, shizuku.attachCount)
        assertSame("the push is kept, not dropped at the provider", shizuku, Porter.keptForTest(PorterBackend.SHIZUKU))

        uninstallPorter()
        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertSame(shizuku, Porter.connection.value?.binder)
        assertEquals("the kept binder has to be attached to exactly once", 1, shizuku.attachCount)
    }
}
