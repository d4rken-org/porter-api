package eu.darken.porter.endpoint

import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.OsUtils

/**
 * A caller running as the server's own uid, or from its own process, is a client like any other on
 * the Porter wire: it needs a record and a grant, and it is answered from that record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterEndpointServerUidTest {

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
        endpoint = PorterEndpoint(
            ServerTestSupport.newCore(clients, ServerTestSupport.TestUserServiceManager(), config, policy) { listOf(PACKAGE) },
        )

        ShadowBinder.setCallingUid(OsUtils.uid)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    private fun attach(): IPorterApplication {
        val application = mock(IPorterApplication::class.java)
        `when`(application.asBinder()).thenReturn(mock(IBinder::class.java))
        val args = Bundle()
        args.putString(ATTACH_PACKAGE_NAME, PACKAGE)
        args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION)
        endpoint.attach(application, args)
        return application
    }

    private fun transactRemote() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            data.writeStrongBinder(mock(IBinder::class.java))
            data.writeInt(1)
            data.writeInt(0)
            data.setDataPosition(0)
            endpoint.onTransact(PorterProtocol.TRANSACTION_transactRemote, data, reply, 0)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun assertEveryGatedOperationIsRefused() {
        assertThrows(SecurityException::class.java) { endpoint.uid }
        assertThrows(SecurityException::class.java) { endpoint.checkPermission("android.permission.DUMP") }
        assertThrows(SecurityException::class.java) { endpoint.seLinuxContext }
        assertThrows(SecurityException::class.java) { endpoint.getSystemProperty("ro.build.id", "") }
        assertThrows(SecurityException::class.java) { endpoint.setSystemProperty("ro.build.id", "") }
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), bindArgs()) }
        assertThrows(SecurityException::class.java) { endpoint.removeUserService(connection(), removeArgs()) }
        assertThrows(SecurityException::class.java) { transactRemote() }
    }

    @Test
    fun anUnattachedServerUidIsRefusedEverywhere() {
        assertEveryGatedOperationIsRefused()
        assertThrows(IllegalStateException::class.java) { endpoint.checkSelfPermission() }
        assertThrows(IllegalStateException::class.java) { endpoint.requestPermission(3) }
        assertThrows(IllegalStateException::class.java) { endpoint.shouldShowRequestPermissionRationale() }
        assertFalse(policy.confirmationShown)
    }

    @Test
    fun anUnattachedCallerFromTheServerProcessIsRefusedEverywhere() {
        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(OsUtils.pid)

        assertEveryGatedOperationIsRefused()
        assertThrows(IllegalStateException::class.java) { endpoint.checkSelfPermission() }
        assertThrows(IllegalStateException::class.java) { endpoint.requestPermission(3) }
        assertThrows(IllegalStateException::class.java) { endpoint.shouldShowRequestPermissionRationale() }
        assertFalse(policy.confirmationShown)
    }

    @Test
    fun anAttachedServerUidWithoutAGrantIsRefusedAndAskedLikeAnyClient() {
        `when`(config.find(OsUtils.uid)).thenReturn(ServerTestSupport.entry(false, false))
        val application = attach()
        val record = clients.findClient(OsUtils.uid, CLIENT_PID)

        assertEveryGatedOperationIsRefused()
        assertFalse(endpoint.checkSelfPermission())
        assertFalse(endpoint.shouldShowRequestPermissionRationale())

        endpoint.requestPermission(5)

        assertTrue(policy.confirmationShown)
        assertEquals(5, policy.confirmationRequestCode)
        assertSame(record, policy.confirmationRecord)
        assertEquals(OsUtils.uid, policy.confirmationUid)
        assertEquals(CLIENT_PID, policy.confirmationPid)
        verify(application, never()).dispatchRequestPermissionResult(anyInt(), any(Bundle::class.java))
    }

    @Test
    fun anAttachedServerUidWithADeniedEntryIsToldSo() {
        `when`(config.find(OsUtils.uid)).thenReturn(ServerTestSupport.entry(false, true))
        val application = attach()

        assertTrue(endpoint.shouldShowRequestPermissionRationale())

        endpoint.requestPermission(7)

        assertFalse(policy.confirmationShown)
        verify(application).dispatchRequestPermissionResult(anyInt(), any(Bundle::class.java))
    }

    @Test
    fun anAttachedServerUidWithAGrantIsAnsweredFromItsRecord() {
        `when`(config.find(OsUtils.uid)).thenReturn(ServerTestSupport.entry(true, false))
        attach()

        assertTrue(endpoint.checkSelfPermission())
        assertEquals(OsUtils.uid, endpoint.uid)

        clients.findClient(OsUtils.uid, CLIENT_PID)!!.allowed = false

        assertFalse(endpoint.checkSelfPermission())
        assertThrows(SecurityException::class.java) { endpoint.uid }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        fun connection(): IPorterServiceConnection {
            val connection = mock(IPorterServiceConnection::class.java)
            `when`(connection.asBinder()).thenReturn(mock(IBinder::class.java))
            return connection
        }

        fun bindArgs(): Bundle = Bundle().apply {
            putParcelable(USER_SERVICE_COMPONENT, ComponentName(PACKAGE, CLASS))
        }

        fun removeArgs(): Bundle = Bundle().apply {
            putParcelable(USER_SERVICE_COMPONENT, ComponentName(PACKAGE, CLASS))
            putBoolean(USER_SERVICE_REMOVE, true)
        }
    }
}
