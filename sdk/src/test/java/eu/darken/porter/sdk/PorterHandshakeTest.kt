package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.REPLY_UNSUPPORTED
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

/** What the version pair in an attach reply decides: a connection, or a refusal that says why. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterHandshakeTest {

    private val context: Context = mock(Context::class.java)
    private val packages: PackageManager = mock(PackageManager::class.java)

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        `when`(context.packageManager).thenReturn(packages)
        `when`(packages.getPermissionInfo(ShizukuProtocol.PERMISSION, 0))
            .thenThrow(PackageManager.NameNotFoundException())
        val info = PermissionInfo()
        info.packageName = PorterProtocol.MANAGER_APPLICATION_ID
        doReturn(info).`when`(packages).getPermissionInfo(PorterProtocol.PERMISSION, 0)
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun aMatchedPairConnects() = runBlocking<Unit> {
        val fake = FakePorterService()

        Porter.onBinderReceived(fake, PACKAGE)

        assertSame(fake, Porter.connection.value!!.binder)
        assertNull(Porter.incompatibility())
        assertEquals(PorterAvailability.Connected(PorterBackend.PORTER, PorterProtocol.MANAGER_APPLICATION_ID), Porter.availability(context))
    }

    /** The server accepts this client but is itself older than what this SDK still speaks. */
    @Test
    fun aServerBelowTheClientFloorIsRefusedAsTooOld() = runBlocking<Unit> {
        val fake = FakePorterService()
        fake.protocolVersion = PorterProtocolWire.MIN_SERVER_VERSION - 1
        fake.minProtocolVersion = 1

        Porter.onBinderReceived(fake, PACKAGE)

        assertNull(Porter.connection.value)
        // The reason travels with the answer, so no second read can find it gone.
        val why = (Porter.availability(context) as PorterAvailability.Incompatible).incompatibility
        assertEquals(assertRefused(), why)
        assertEquals(PorterBackend.PORTER, why.backend)
        assertTrue(why.serverTooOld)
        assertFalse(why.clientTooOld)
        assertEquals(PorterProtocolWire.MIN_SERVER_VERSION - 1, why.serverVersion)
        assertEquals(1, why.serverMinVersion)
        assertEquals(PorterProtocol.VERSION, why.clientVersion)
    }

    /** The server refused this client outright: its floor is above what this SDK speaks. */
    @Test
    fun aServerThatRefusesThisClientIsReportedAsClientTooOld() = runBlocking<Unit> {
        val fake = FakePorterService()
        fake.protocolVersion = PorterProtocol.VERSION + 2
        fake.minProtocolVersion = PorterProtocol.VERSION + 1
        fake.attachReply.putBoolean(REPLY_UNSUPPORTED, true)

        Porter.onBinderReceived(fake, PACKAGE)

        assertNull(Porter.connection.value)
        assertTrue(Porter.availability(context) is PorterAvailability.Incompatible)
        val why = assertRefused()
        assertTrue(why.clientTooOld)
        assertFalse(why.serverTooOld)
        assertEquals(PorterProtocol.VERSION + 1, why.serverMinVersion)
    }

    /** A newer server is not a reason on its own: what counts is whether its floor admits us. */
    @Test
    fun aNewerServerWhoseFloorAdmitsThisClientConnects() {
        val fake = FakePorterService()
        fake.protocolVersion = PorterProtocol.VERSION + 5

        Porter.onBinderReceived(fake, PACKAGE)

        assertNotNull(Porter.connection.value)
        assertEquals(PorterProtocol.VERSION + 5, Porter.connection.value!!.serverInfo.version)
    }

    @Test
    fun aReplyWithoutAVersionIsRefused() {
        val fake = FakePorterService()
        fake.protocolVersion = null
        fake.minProtocolVersion = null

        Porter.onBinderReceived(fake, PACKAGE)

        assertNull(Porter.connection.value)
        assertEquals(0, assertRefused().serverVersion)
    }

    @Test
    fun aNullReplyIsRefused() = runBlocking<Unit> {
        val fake = object : FakePorterService() {
            override fun attach(application: eu.darken.porter.server.IPorterApplication, args: Bundle): Bundle? = null
        }

        Porter.onBinderReceived(fake, PACKAGE)

        assertNull(Porter.connection.value)
        assertTrue(Porter.availability(context) is PorterAvailability.Incompatible)
    }

    @Test
    fun aRefusedBinderIsNotWatchedForDeath() {
        val fake = FakePorterService()
        fake.protocolVersion = 1

        Porter.onBinderReceived(fake, PACKAGE)

        val shadow: ShadowBinder = Shadow.extract(fake)
        assertTrue(shadow.deathRecipients.isEmpty())
    }

    /** The refusal describes the last delivery only; the next one that connects supersedes it. */
    @Test
    fun aCompatibleDeliveryClearsTheRefusal() = runBlocking<Unit> {
        val old = FakePorterService()
        old.protocolVersion = 1
        Porter.onBinderReceived(old, PACKAGE)
        assertTrue(Porter.availability(context) is PorterAvailability.Incompatible)

        Porter.onBinderReceived(FakePorterService(), PACKAGE)

        assertNull(Porter.incompatibility())
        assertEquals(PorterAvailability.Connected(PorterBackend.PORTER, PorterProtocol.MANAGER_APPLICATION_ID), Porter.availability(context))
    }

    @Test
    fun aNullDeliveryClearsTheRefusal() = runBlocking<Unit> {
        val old = FakePorterService()
        old.protocolVersion = 1
        Porter.onBinderReceived(old, PACKAGE)

        Porter.onBinderReceived(null, PACKAGE)

        assertNull(Porter.incompatibility())
        assertEquals(PorterAvailability.InstalledNotConnected(PorterBackend.PORTER, PorterProtocol.MANAGER_APPLICATION_ID), Porter.availability(context))
    }

    /** A refused server that has since stopped is no longer running, incompatible or not. */
    @Test
    fun aRefusedBinderThatDiedReadsAsNotConnected() = runBlocking<Unit> {
        val old = object : FakePorterService() {
            var alive = true
            override fun pingBinder(): Boolean = alive
        }
        old.protocolVersion = 1
        Porter.onBinderReceived(old, PACKAGE)
        assertTrue(Porter.availability(context) is PorterAvailability.Incompatible)

        old.alive = false

        assertNull(Porter.incompatibility())
        assertEquals(PorterAvailability.InstalledNotConnected(PorterBackend.PORTER, PorterProtocol.MANAGER_APPLICATION_ID), Porter.availability(context))
    }

    /** A newcomer that cannot be spoken to must not silence the connection still serving. */
    @Test
    fun aRefusedDeliveryLeavesTheConnectionItCouldNotReplace() = runBlocking<Unit> {
        val serving = FakePorterService()
        Porter.onBinderReceived(serving, PACKAGE)

        val old = FakePorterService()
        old.protocolVersion = 1
        Porter.onBinderReceived(old, PACKAGE)

        assertSame(serving, Porter.connection.value!!.binder)
        assertNull(Porter.incompatibility())
        assertEquals(PorterAvailability.Connected(PorterBackend.PORTER, PorterProtocol.MANAGER_APPLICATION_ID), Porter.availability(context))
    }

    private fun assertRefused(): PorterIncompatibility {
        val why = Porter.incompatibility()
        assertNotNull(why)
        return why!!
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
