package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ClientCallback
import eu.darken.porter.core.ServerPolicy
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES
import eu.darken.porter.protocol.PorterProtocol.REPLY_MIN_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.protocol.PorterProtocol.REPLY_UNSUPPORTED
import eu.darken.porter.server.IPorterApplication
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ClientRecord
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.OsUtils

/** Who the Porter endpoint lets attach, what it records for them and what it reports back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterEndpointAttachTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var policy: ServerTestSupport.TestPolicy
    private lateinit var endpoint: PorterEndpoint

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        policy = ServerTestSupport.TestPolicy()
        endpoint = endpointOwning(clients, policy, PACKAGE, OTHER_PACKAGE)

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    private fun endpointOwning(
        clientManager: ClientManager<ConfigManager>,
        serverPolicy: ServerPolicy,
        vararg packages: String,
    ): PorterEndpoint {
        val owned = packages.toList()
        return PorterEndpoint(
            ServerTestSupport.newCore(clientManager, ServerTestSupport.TestUserServiceManager(), config, serverPolicy) { owned },
        )
    }

    @Test
    fun attachRecordsAPorterClientAndReportsTheServerState() {
        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))

        val record = clients.findClient(CLIENT_UID, CLIENT_PID)
        assertNotNull(record)
        assertTrue(record!!.callback is PorterClientCallback)
        assertNull("no Shizuku application for a Porter client", record.client)
        assertEquals(PACKAGE, record.packageName)

        assertTrue(reply.containsKey(REPLY_PROTOCOL_VERSION))
        assertTrue(reply.containsKey(REPLY_MIN_PROTOCOL_VERSION))
        assertFalse(reply.containsKey(REPLY_UNSUPPORTED))
        assertTrue(reply.containsKey(REPLY_SERVER_UID))
        assertTrue(reply.containsKey(REPLY_SERVER_SECONTEXT))
        assertTrue(reply.containsKey(REPLY_PERMISSION_GRANTED))
        assertTrue(reply.containsKey(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE))
        assertTrue(reply.containsKey(REPLY_CAPABILITIES))

        assertEquals(PorterProtocol.VERSION, reply.getInt(REPLY_PROTOCOL_VERSION))
        assertEquals(PorterProtocol.MIN_VERSION, reply.getInt(REPLY_MIN_PROTOCOL_VERSION))
        assertEquals(PorterProtocol.VERSION, clients.findClient(CLIENT_UID, CLIENT_PID)!!.apiVersion)
        assertEquals(OsUtils.uid, reply.getInt(REPLY_SERVER_UID))
        assertEquals(OsUtils.seLinuxContext, reply.getString(REPLY_SERVER_SECONTEXT))
        assertFalse(reply.getBoolean(REPLY_PERMISSION_GRANTED))
        assertFalse(reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE))
        assertEquals(CAPABILITIES_NONE, reply.getLong(REPLY_CAPABILITIES))
    }

    @Test
    fun aPackageThatDoesNotBelongToTheCallerIsRefused() {
        val foreign = endpointOwning(clients, policy, OTHER_PACKAGE)

        assertThrows(SecurityException::class.java) {
            foreign.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))
        }

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
    }

    @Test
    fun aSecondAttachFromTheSameProcessDoesNotCreateASecondRecord() {
        endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))
        val first = clients.findClient(CLIENT_UID, CLIENT_PID)

        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))

        assertEquals(PorterProtocol.VERSION, reply.getInt(REPLY_PROTOCOL_VERSION))
        assertEquals(1, clients.findClients(CLIENT_UID).size)
        assertSame(first, clients.findClient(CLIENT_UID, CLIENT_PID))
    }

    @Test
    fun aReattachMayNotRenameTheClient() {
        endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))

        assertThrows(SecurityException::class.java) {
            endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(OTHER_PACKAGE))
        }

        assertEquals(1, clients.findClients(CLIENT_UID).size)
        assertEquals(PACKAGE, clients.findClient(CLIENT_UID, CLIENT_PID)!!.packageName)
    }

    @Test
    fun aProcessAttachedThroughTheShizukuEndpointIsRefused() {
        clients.addClient(CLIENT_UID, CLIENT_PID, ServerTestSupport.application(mock(IBinder::class.java)), PACKAGE, 13)

        assertThrows(IllegalStateException::class.java) {
            endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))
        }
    }

    @Test
    @Throws(Exception::class)
    fun aDeadCallbackBinderIsReportedAsIllegalState() {
        val dead = mock(IBinder::class.java)
        doThrow(RemoteException()).`when`(dead).linkToDeath(any(IBinder.DeathRecipient::class.java), anyInt())

        assertThrows(IllegalStateException::class.java) {
            endpoint.attach(porterApplication(dead), attachArgs(PACKAGE))
        }

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
    }

    @Test
    fun theReplyReportsTheConfiguredGrant() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))

        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))

        assertTrue(reply.getBoolean(REPLY_PERMISSION_GRANTED))
        assertFalse(reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE))
    }

    @Test
    fun theReplyReportsADenialTheUserAskedNotToBeAskedAbout() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(false, true))

        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))

        assertFalse(reply.getBoolean(REPLY_PERMISSION_GRANTED))
        assertTrue(reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE))
    }

    /** An old client is turned away before anything is created or any hook hears of it. */
    @Test
    fun aClientBelowTheFloorIsRefusedWithoutARecord() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))

        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE, PorterProtocol.MIN_VERSION - 1))

        assertTrue(reply.getBoolean(REPLY_UNSUPPORTED))
        assertEquals(PorterProtocol.VERSION, reply.getInt(REPLY_PROTOCOL_VERSION))
        assertEquals(PorterProtocol.MIN_VERSION, reply.getInt(REPLY_MIN_PROTOCOL_VERSION))
        assertEquals("nothing but the versions", 3, reply.size())

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
        assertTrue(policy.attachingCallers.isEmpty())
        assertTrue(policy.attachedRecords.isEmpty())
        assertTrue(policy.boundRecords.isEmpty())
    }

    @Test
    fun aClientWithoutAVersionIsRefused() {
        val args = Bundle().apply { putString(ATTACH_PACKAGE_NAME, PACKAGE) }

        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), args)

        assertTrue(reply.getBoolean(REPLY_UNSUPPORTED))
        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
    }

    /** Newer is never a reason on its own: the client's floor is its own business. */
    @Test
    fun aClientAboveTheServerVersionAttaches() {
        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE, PorterProtocol.VERSION + 3))

        assertFalse(reply.getBoolean(REPLY_UNSUPPORTED))
        assertEquals(PorterProtocol.VERSION + 3, clients.findClient(CLIENT_UID, CLIENT_PID)!!.apiVersion)
    }

    /** The floor is checked on every attach, not only the one that created the record. */
    @Test
    fun aReattachBelowTheFloorIsRefusedAndLeavesTheRecordAlone() {
        endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))
        val first = clients.findClient(CLIENT_UID, CLIENT_PID)

        val reply = endpoint.attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE, 1))

        assertTrue(reply.getBoolean(REPLY_UNSUPPORTED))
        assertSame(first, clients.findClient(CLIENT_UID, CLIENT_PID))
        assertEquals(PorterProtocol.VERSION, first!!.apiVersion)
    }

    /** A racing second attach cannot be scheduled deterministically; the monitor it needs can. */
    private class LockRecordingClientManager(configManager: ConfigManager) : ClientManager<ConfigManager>(configManager) {

        val lockHeldOnAttach = AtomicBoolean()

        override fun attach(identity: CallerIdentity, callback: ClientCallback, packageName: String, apiVersion: Int): ClientRecord? {
            lockHeldOnAttach.set(Thread.holdsLock(this))
            return super.attach(identity, callback, packageName, apiVersion)
        }
    }

    @Test
    fun theFindAndAttachPairRunsUnderTheClientManagerMonitor() {
        val recording = LockRecordingClientManager(config)

        endpointOwning(recording, policy, PACKAGE)
            .attach(porterApplication(mock(IBinder::class.java)), attachArgs(PACKAGE))

        assertTrue(recording.lockHeldOnAttach.get())
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val OTHER_PACKAGE = "eu.darken.porter.probe.other"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        fun porterApplication(binder: IBinder): IPorterApplication {
            val application = mock(IPorterApplication::class.java)
            `when`(application.asBinder()).thenReturn(binder)
            return application
        }

        fun attachArgs(packageName: String, version: Int = PorterProtocol.VERSION): Bundle = Bundle().apply {
            putString(ATTACH_PACKAGE_NAME, packageName)
            putInt(ATTACH_PROTOCOL_VERSION, version)
        }
    }
}
