package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.core.PorterCore
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.server.IPorterApplication
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.ShizukuLegacyEndpoint
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.util.HandlerUtil

/**
 * Each endpoint owns its own transaction codes and its own interface token: a raw transaction is
 * answered by the endpoint it was addressed to, and by no other.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterEndpointTransactTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var core: PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager>
    private lateinit var endpoint: PorterEndpoint

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        core = ServerTestSupport.newCore(clients, ServerTestSupport.TestUserServiceManager(), config, ServerTestSupport.TestPolicy()) {
            listOf(PACKAGE)
        }
        endpoint = PorterEndpoint(core)

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    private fun attachAllowedClient() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        val application = mock(IPorterApplication::class.java)
        `when`(application.asBinder()).thenReturn(mock(IBinder::class.java))
        val args = Bundle()
        args.putString(ATTACH_PACKAGE_NAME, PACKAGE)
        args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION)
        endpoint.attach(application, args)
    }

    /**
     * Forwards one parcel laid out as a Porter client writes it (strong binder, code, flags,
     * payload) and reports what the target saw: `{flags, first int of the forwarded payload}`.
     */
    @Throws(Exception::class)
    private fun forward(): IntArray {
        val seen = IntArray(2)
        val target = mock(IBinder::class.java)
        `when`(target.transact(anyInt(), any(Parcel::class.java), any(), anyInt())).thenAnswer { invocation ->
            seen[0] = invocation.getArgument(3)
            val forwarded = invocation.getArgument<Parcel>(1)
            forwarded.setDataPosition(0)
            seen[1] = forwarded.readInt()
            true
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            data.writeStrongBinder(target)
            data.writeInt(TARGET_CODE)
            data.writeInt(IN_PARCEL_FLAGS)
            data.writeInt(PAYLOAD)
            data.setDataPosition(0)
            assertTrue(endpoint.onTransact(PorterProtocol.TRANSACTION_transactRemote, data, reply, OUTER_FLAGS))
        } finally {
            data.recycle()
            reply.recycle()
        }
        verify(target).transact(eq(TARGET_CODE), any(Parcel::class.java), any(), anyInt())
        return seen
    }

    @Test
    @Throws(Exception::class)
    fun transactRemoteForwardsWithTheInParcelFlags() {
        attachAllowedClient()

        assertArrayEquals(intArrayOf(IN_PARCEL_FLAGS, PAYLOAD), forward())
    }

    @Test
    @Throws(Exception::class)
    fun transactRemoteIsBoundToThePorterToken() {
        attachAllowedClient()

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeStrongBinder(mock(IBinder::class.java))
            data.writeInt(TARGET_CODE)
            data.setDataPosition(0)

            assertThrows(SecurityException::class.java) {
                endpoint.onTransact(PorterProtocol.TRANSACTION_transactRemote, data, reply, 0)
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    @Throws(Exception::class)
    fun thePorterEndpointOwnsItsOwnShellCodes() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            data.setDataPosition(0)

            assertThrows(SecurityException::class.java) {
                endpoint.onTransact(PorterProtocol.TRANSACTION_PORSH_BASE, data, reply, 0)
            }

            data.setDataPosition(0)
            assertFalse(endpoint.onTransact(LEGACY_PORSH_BASE, data, reply, 0))
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    @Throws(Exception::class)
    fun theShizukuEndpointOwnsItsOwnShellCodes() {
        val legacy = ShizukuLegacyEndpoint(core, mock(ManagerOperations::class.java))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.setDataPosition(0)

            assertFalse(legacy.onTransact(PorterProtocol.TRANSACTION_PORSH_BASE, data, reply, 0))

            data.setDataPosition(0)
            assertThrows(SecurityException::class.java) {
                legacy.onTransact(LEGACY_PORSH_BASE, data, reply, 0)
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        const val LEGACY_PORSH_BASE = 30000
        const val TARGET_CODE = 7
        const val IN_PARCEL_FLAGS = 42
        const val PAYLOAD = 20816
        const val OUTER_FLAGS = 17
    }
}
