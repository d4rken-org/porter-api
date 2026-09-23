package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PermissionInfo
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/** Which backend a process takes a delivery on, and what a delivery on the other one does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterBackendSelectionTest {

    /** Counts the death links a session registers, which a local binder otherwise swallows. */
    private class CountingPorterService : FakePorterService() {

        var deathLinks = 0

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            deathLinks++
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    /** Counts the death links a session registers, which a local binder otherwise swallows. */
    private class CountingShizukuService : FakeShizukuService() {

        var deathLinks = 0

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            deathLinks++
        }

        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
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

    private fun porterIsInstalled() = declares(PorterProtocol.MANAGER_APPLICATION_ID, PorterProtocol.PERMISSION)

    private fun shizukuIsInstalled() = declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION)

    private fun deliverPorter(): CountingPorterService {
        val fake = CountingPorterService()
        Porter.onBinderReceived(context, fake, PACKAGE, PorterBackend.PORTER)
        return fake
    }

    private fun deliverShizuku(): CountingShizukuService {
        val fake = CountingShizukuService()
        Porter.onBinderReceived(context, fake, PACKAGE, PorterBackend.SHIZUKU)
        return fake
    }

    private fun binder(): IBinder? = Porter.connection.value?.binder

    @Test
    fun onlyPorterInstalledSelectsPorterAndIgnoresAShizukuDelivery() {
        porterIsInstalled()

        assertEquals(Porter.Selection.PORTER, Porter.selectBackend(context))

        val ignored = deliverShizuku()

        assertNull(binder())
        assertEquals(0, ignored.attachCount)
        assertEquals(0, ignored.deathLinks)
    }

    @Test
    fun onlyShizukuInstalledSelectsShizukuAndIgnoresAPorterDelivery() {
        shizukuIsInstalled()

        assertEquals(Porter.Selection.SHIZUKU, Porter.selectBackend(context))

        val ignored = deliverPorter()

        assertNull(binder())
        assertEquals(0, ignored.attachCount)
        assertEquals(0, ignored.deathLinks)
    }

    /** Shizuku+'s Plus flavor declares only its own permission and delivers as Shizuku does. */
    @Test
    fun onlyShizukuPlusInstalledSelectsShizukuAndTakesItsDelivery() {
        declares(ShizukuProtocol.PLUS_MANAGER_APPLICATION_ID, ShizukuProtocol.PLUS_PERMISSION)

        assertEquals(Porter.Selection.SHIZUKU, Porter.selectBackend(context))

        val plus = deliverShizuku()

        assertSame(plus, binder())
        assertEquals(1, plus.attachCount)
    }

    @Test
    fun bothInstalledSelectsPorter() {
        porterIsInstalled()
        shizukuIsInstalled()

        assertEquals(Porter.Selection.PORTER, Porter.selectBackend(context))

        val porter = deliverPorter()

        assertSame(porter, binder())
        assertEquals(1, porter.attachCount)
    }

    @Test
    fun neitherInstalledSelectsNothingAndIgnoresBothDeliveries() {
        assertEquals(Porter.Selection.NONE, Porter.selectBackend(context))

        val porter = deliverPorter()
        val shizuku = deliverShizuku()

        assertNull(binder())
        assertEquals(0, porter.attachCount)
        assertEquals(0, porter.deathLinks)
        assertEquals(0, shizuku.attachCount)
        assertEquals(0, shizuku.deathLinks)
    }

    @Test
    fun shizukuWithoutTheCompatibilityArtifactSelectsNothing() {
        shizukuIsInstalled()
        ShizukuCompat.setPresentForTest(false)

        assertEquals(Porter.Selection.NONE, Porter.selectBackend(context))

        val ignored = deliverShizuku()

        assertNull(binder())
        assertEquals(0, ignored.attachCount)
        assertEquals(0, ignored.deathLinks)
    }

    @Test
    fun aPackageThatIsNotTheKnownPorterManagerStillSelectsPorter() {
        declares("eu.darken.porter.fork", PorterProtocol.PERMISSION)

        assertEquals(Porter.Selection.PORTER, Porter.selectBackend(context))

        val porter = deliverPorter()

        assertSame(porter, binder())
    }

    @Test
    fun aPackageThatIsNotTheKnownShizukuManagerStillSelectsShizuku() {
        declares("moe.shizuku.fork", ShizukuProtocol.PERMISSION)

        assertEquals(Porter.Selection.SHIZUKU, Porter.selectBackend(context))

        val shizuku = deliverShizuku()

        assertSame(shizuku, binder())
    }

    @Test
    fun aSelectionMadeWithNothingConnectedIsResolvedAgainAfterAnInstall() {
        assertEquals(Porter.Selection.NONE, Porter.selectBackend(context))

        porterIsInstalled()

        assertEquals(Porter.Selection.PORTER, Porter.selectBackend(context))

        val porter = deliverPorter()

        assertSame(porter, binder())
    }

    @Test
    fun anInstallDuringALiveConnectionDoesNotMoveTheSelection() {
        shizukuIsInstalled()
        val shizuku = deliverShizuku()
        assertSame(shizuku, binder())

        porterIsInstalled()

        assertEquals(Porter.Selection.SHIZUKU, Porter.selectBackend(context))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
