package rikka.shizuku.server

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.core.ServerPolicy
import java.util.function.IntFunction
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerTestSupport.TestPolicy
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager
import rikka.shizuku.server.ServerTestSupport.entry
import rikka.shizuku.server.ServerTestSupport.newCore
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.OsUtils

/** What a Shizuku client is told when it attaches, and what the policy gets to say about it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuLegacyEndpointAttachTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var policy: TestPolicy
    private lateinit var endpoint: ShizukuLegacyEndpoint

    /** Keeps the reply it was bound with, and can fail the way a dead client does. */
    private class RecordingApplication : IShizukuApplication.Stub() {

        var bound: Bundle? = null
        var failure: RuntimeException? = null

        override fun bindApplication(data: Bundle?) {
            bound = data
            failure?.let { throw it }
        }

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {
        }

        override fun showPermissionConfirmation(requestUid: Int, requestPid: Int, requestPackageName: String?, requestCode: Int) {
        }
    }

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        policy = TestPolicy()
        endpoint = endpointWith(policy)

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    private fun endpointWith(serverPolicy: ServerPolicy): ShizukuLegacyEndpoint = ShizukuLegacyEndpoint(
        newCore(clients, TestUserServiceManager(), config, serverPolicy, IntFunction { listOf(PACKAGE) }),
        mock(ManagerOperations::class.java),
    )

    private fun attachArgs(packageName: String, apiVersion: Int): Bundle {
        val args = Bundle()
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName)
        args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, apiVersion)
        return args
    }

    private fun connection(): IShizukuServiceConnection {
        val connection = mock(IShizukuServiceConnection::class.java)
        `when`(connection.asBinder()).thenReturn(mock(IBinder::class.java))
        return connection
    }

    @Test
    fun aV13AttachIsAnsweredWithTheSixKeyReply() {
        `when`(config.find(CLIENT_UID)).thenReturn(entry(true, false))
        val app = RecordingApplication()

        endpoint.attachApplication(app, attachArgs(PACKAGE, 13))

        val record = clients.findClient(CLIENT_UID, CLIENT_PID)
        assertNotNull(record)
        assertEquals(PACKAGE, record!!.packageName)
        assertEquals(13, record.apiVersion)

        val bound = app.bound
        assertNotNull(bound)
        assertEquals(OsUtils.uid, bound!!.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID))
        assertEquals(ShizukuApiConstants.SERVER_VERSION, bound.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION))
        assertEquals(OsUtils.seLinuxContext, bound.getString(ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT))
        assertEquals(ShizukuApiConstants.SERVER_PATCH_VERSION, bound.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION))
        assertTrue(bound.getBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED))
        assertFalse(bound.getBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE))
    }

    @Test
    fun theCode14PathAttachesAPreV13ClientAndRepliesVersion12() {
        val app = RecordingApplication()

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeStrongBinder(app)
            data.writeString(PACKAGE)
            data.setDataPosition(0)

            assertTrue(endpoint.onTransact(14, data, reply, 0))

            reply.setDataPosition(0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }

        val record = clients.findClient(CLIENT_UID, CLIENT_PID)
        assertNotNull(record)
        assertEquals(-1, record!!.apiVersion)
        assertNotNull(app.bound)
        assertEquals(12, app.bound!!.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION))
    }

    @Test
    fun thePolicyMayTakeAKeyOutOfTheReplyBeforeItLeaves() {
        `when`(config.find(CLIENT_UID)).thenReturn(entry(true, false))
        val stripping = endpointWith(object : ServerPolicy {
            override fun onAttached(record: ClientRecord, created: Boolean, reply: Bundle) {
                reply.remove(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED)
            }
        })
        val app = RecordingApplication()

        stripping.attachApplication(app, attachArgs(PACKAGE, 13))

        assertFalse(app.bound!!.containsKey(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED))
        assertTrue(app.bound!!.containsKey(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID))
    }

    @Test
    fun onBoundFollowsADeliveredReply() {
        val app = RecordingApplication()

        endpoint.attachApplication(app, attachArgs(PACKAGE, 13))

        val record = clients.findClient(CLIENT_UID, CLIENT_PID)
        assertEquals(listOf(record), policy.attachedRecords)
        assertEquals(listOf(true), policy.attachedCreated)
        assertEquals(listOf(record), policy.boundRecords)
        assertEquals(listOf(true), policy.boundCreated)
    }

    @Test
    fun aFailedBindIsLoggedAndSkipsOnBound() {
        val app = RecordingApplication()
        app.failure = RuntimeException("client is gone")

        endpoint.attachApplication(app, attachArgs(PACKAGE, 13))

        assertNotNull(clients.findClient(CLIENT_UID, CLIENT_PID))
        assertEquals(1, policy.attachedRecords.size)
        assertTrue(policy.boundRecords.isEmpty())
    }

    @Test
    fun anAttachWithoutAnApplicationPackageOrArgsRecordsNobody() {
        endpoint.attachApplication(null, attachArgs(PACKAGE, 13))
        endpoint.attachApplication(RecordingApplication(), null)
        endpoint.attachApplication(RecordingApplication(), Bundle())

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
        assertTrue(policy.attachingPackages.isEmpty())
    }

    @Test
    fun aPackageThatDoesNotBelongToTheCallerIsRefused() {
        assertThrows(SecurityException::class.java) {
            endpoint.attachApplication(RecordingApplication(), attachArgs(OTHER_PACKAGE, 13))
        }

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID))
    }

    /**
     * A refused caller is told it has no permission, not what its Bundle failed to decode to: a
     * null or component-less Bundle would raise something else if the gate ran after the decoder.
     */
    @Test
    fun theUserServiceCallsRefuseAnUnauthorizedCallerBeforeReadingItsBundle() {
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), null) }
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), Bundle()) }
        assertThrows(SecurityException::class.java) { endpoint.removeUserService(connection(), null) }
        assertThrows(SecurityException::class.java) { endpoint.removeUserService(connection(), Bundle()) }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val OTHER_PACKAGE = "eu.darken.porter.probe.other"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678
    }
}
