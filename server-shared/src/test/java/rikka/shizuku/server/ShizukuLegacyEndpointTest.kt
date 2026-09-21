package rikka.shizuku.server

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.core.ManagerOperations
import eu.darken.porter.core.PorterCore
import java.util.function.IntFunction
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.ServerTestSupport.TestPolicy
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager
import rikka.shizuku.server.ServerTestSupport.application
import rikka.shizuku.server.ServerTestSupport.entry
import rikka.shizuku.server.ServerTestSupport.newCore
import rikka.shizuku.server.util.HandlerUtil
import rikka.shizuku.server.util.OsUtils

/**
 * What the client-facing operations on [ShizukuLegacyEndpoint] do with the caller's identity
 * and record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShizukuLegacyEndpointTest {

    private lateinit var endpoint: ShizukuLegacyEndpoint
    private lateinit var core: PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager>
    private lateinit var policy: TestPolicy
    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var packages: MockedStatic<PackageManagerApis>

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        policy = TestPolicy()
        core = newCore(clients, TestUserServiceManager(), config, policy, IntFunction { listOf(PACKAGE) })
        endpoint = ShizukuLegacyEndpoint(core, mock(ManagerOperations::class.java))

        val installed = PackageInfo()
        installed.packageName = PACKAGE
        installed.applicationInfo = ApplicationInfo()
        installed.applicationInfo!!.uid = CLIENT_UID
        installed.applicationInfo!!.sourceDir = "/data/app/porter-probe/base.apk"
        packages = Mockito.mockStatic(PackageManagerApis::class.java)
        packages.`when`<PackageInfo> { PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()) }
            .thenReturn(installed)

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        packages.close()
        ShadowBinder.reset()
    }

    private fun attach(app: IShizukuApplication, pid: Int, apiVersion: Int): ClientRecord? =
        clients.addClient(CLIENT_UID, pid, app, PACKAGE, apiVersion)

    private fun bindOptions(noCreate: Boolean): Bundle {
        val options = Bundle()
        options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, ComponentName(PACKAGE, CLASS))
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, noCreate)
        return options
    }

    private fun connection(): IShizukuServiceConnection {
        val connection = mock(IShizukuServiceConnection::class.java)
        `when`(connection.asBinder()).thenReturn(mock(IBinder::class.java))
        return connection
    }

    @Test
    fun requestPermissionFromAllowedClientRepliesTrue() {
        `when`(config.find(CLIENT_UID)).thenReturn(entry(true, false))
        val app = application(mock(IBinder::class.java))
        attach(app, CLIENT_PID, 13)

        endpoint.requestPermission(7)

        val reply = ArgumentCaptor.forClass(Bundle::class.java)
        verify(app).dispatchRequestPermissionResult(eq(7), reply.capture())
        assertTrue(reply.value.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED))
        assertFalse(policy.confirmationShown)
    }

    @Test
    fun requestPermissionWithDeniedEntryRepliesFalse() {
        `when`(config.find(CLIENT_UID)).thenReturn(entry(false, true))
        val app = application(mock(IBinder::class.java))
        attach(app, CLIENT_PID, 13)

        endpoint.requestPermission(9)

        val reply = ArgumentCaptor.forClass(Bundle::class.java)
        verify(app).dispatchRequestPermissionResult(eq(9), reply.capture())
        assertFalse(reply.value.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED))
        assertFalse(policy.confirmationShown)
    }

    @Test
    fun requestPermissionOtherwiseAsksForConfirmation() {
        val app = application(mock(IBinder::class.java))
        val record = attach(app, CLIENT_PID, 13)

        endpoint.requestPermission(11)

        assertTrue(policy.confirmationShown)
        assertEquals(11, policy.confirmationRequestCode)
        assertSame(record, policy.confirmationRecord)
        assertEquals(CLIENT_UID, policy.confirmationUid)
        assertEquals(CLIENT_PID, policy.confirmationPid)
        assertEquals(CLIENT_UID / 100000, policy.confirmationUserId)
        verify(app, never()).dispatchRequestPermissionResult(anyInt(), any(Bundle::class.java))
    }

    @Test
    fun requestPermissionFromServerUidReturnsSilently() {
        val app = application(mock(IBinder::class.java))
        attach(app, CLIENT_PID, 13)
        clearInvocations(app)
        ShadowBinder.setCallingUid(OsUtils.uid)

        endpoint.requestPermission(13)

        assertFalse(policy.confirmationShown)
        verifyNoInteractions(app)
    }

    @Test
    fun requestPermissionFromUnattachedCallerThrowsIllegalState() {
        assertThrows(IllegalStateException::class.java) { endpoint.requestPermission(15) }
    }

    @Test
    fun checkSelfPermissionReflectsTheRecord() {
        ShadowBinder.setCallingUid(OsUtils.uid)
        assertTrue(endpoint.checkSelfPermission())

        ShadowBinder.setCallingUid(CLIENT_UID)
        assertThrows(IllegalStateException::class.java) { endpoint.checkSelfPermission() }

        `when`(config.find(CLIENT_UID)).thenReturn(entry(true, false))
        val record = attach(application(mock(IBinder::class.java)), CLIENT_PID, 13)!!
        assertTrue(endpoint.checkSelfPermission())

        record.allowed = false
        assertFalse(endpoint.checkSelfPermission())
    }

    @Test
    fun rationaleFollowsTheDeniedEntry() {
        ShadowBinder.setCallingUid(OsUtils.uid)
        assertTrue(endpoint.shouldShowRequestPermissionRationale())

        ShadowBinder.setCallingUid(CLIENT_UID)
        assertThrows(IllegalStateException::class.java) { endpoint.shouldShowRequestPermissionRationale() }

        attach(application(mock(IBinder::class.java)), CLIENT_PID, 13)
        assertFalse(endpoint.shouldShowRequestPermissionRationale())

        `when`(config.find(CLIENT_UID)).thenReturn(entry(false, true))
        assertTrue(endpoint.shouldShowRequestPermissionRationale())
    }

    /**
     * Forwards one parcel laid out as a v13 client writes it (strong binder, code, flags, payload)
     * and reports what the target saw: `{flags, first int of the forwarded payload}`.
     */
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
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeStrongBinder(target)
            data.writeInt(TARGET_CODE)
            data.writeInt(IN_PARCEL_FLAGS)
            data.writeInt(PAYLOAD)
            data.setDataPosition(0)
            assertTrue(endpoint.onTransact(ShizukuApiConstants.BINDER_TRANSACTION_transact, data, reply, OUTER_FLAGS))
        } finally {
            data.recycle()
            reply.recycle()
        }
        verify(target).transact(eq(TARGET_CODE), any(Parcel::class.java), any(), anyInt())
        return seen
    }

    @Test
    fun transactRemoteReadsFlagsFromTheParcelOnlyForRecordedV13Clients() {
        `when`(config.find(CLIENT_UID)).thenReturn(entry(true, false))
        attach(application(mock(IBinder::class.java)), CLIENT_PID, 13)
        assertArrayEquals(intArrayOf(IN_PARCEL_FLAGS, PAYLOAD), forward())

        ShadowBinder.setCallingPid(CLIENT_PID + 1)
        attach(application(mock(IBinder::class.java)), CLIENT_PID + 1, -1)
        assertArrayEquals(intArrayOf(OUTER_FLAGS, IN_PARCEL_FLAGS), forward())

        ShadowBinder.setCallingPid(CLIENT_PID + 2)
        policy.callerPermission = true
        assertArrayEquals(intArrayOf(OUTER_FLAGS, IN_PARCEL_FLAGS), forward())
    }

    // transactRemoteClearsAndRestoresCallingIdentity is not written: Robolectric's ShadowBinder
    // models only the calling uid and pid it was told to report, and leaves clearCallingIdentity as
    // an unimplemented native returning 0, so a cleared identity is indistinguishable from an
    // uncleared one inside the forwarded transaction.

    @Test
    fun legacyAttachSynthesisesTheV13Bundle() {
        val bound = arrayOfNulls<Bundle>(1)
        val app = object : IShizukuApplication.Stub() {
            override fun bindApplication(data: Bundle?) {
                bound[0] = data
            }

            override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle?) {
            }

            override fun showPermissionConfirmation(requestUid: Int, requestPid: Int, requestPackageName: String?, requestCode: Int) {
            }
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR)
            data.writeStrongBinder(app)
            data.writeString(PACKAGE)
            data.setDataPosition(0)

            assertTrue(endpoint.onTransact(14, data, reply, 0))

            assertNotNull(bound[0])
            assertEquals(12, bound[0]!!.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION))
            val record = clients.findClient(CLIENT_UID, CLIENT_PID)
            assertNotNull(record)
            assertEquals(PACKAGE, record!!.packageName)
            assertEquals(-1, record.apiVersion)
            reply.setDataPosition(0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun addUserServiceUsesTheRecordedApiVersion() {
        `when`(config.find(CLIENT_UID)).thenReturn(entry(true, false))

        attach(application(mock(IBinder::class.java)), CLIENT_PID, 12)
        assertEquals(1, endpoint.addUserService(connection(), bindOptions(true)))

        ShadowBinder.setCallingPid(CLIENT_PID + 1)
        attach(application(mock(IBinder::class.java)), CLIENT_PID + 1, 13)
        assertEquals(-1, endpoint.addUserService(connection(), bindOptions(true)))

        ShadowBinder.setCallingPid(CLIENT_PID + 2)
        policy.callerPermission = true
        assertEquals(-1, endpoint.addUserService(connection(), bindOptions(true)))
    }

    @Test
    fun newProcessLinksTheHolderToTheClientBinder() {
        `when`(config.find(CLIENT_UID)).thenReturn(entry(true, false))
        val appBinder = mock(IBinder::class.java)
        attach(application(appBinder), CLIENT_PID, 13)
        clearInvocations(appBinder)

        val process = endpoint.newProcess(arrayOf("sh", "-c", "exit 0"), null, null)

        assertNotNull(process)
        verify(appBinder).linkToDeath(any(IBinder.DeathRecipient::class.java), eq(0))

        ShadowBinder.setCallingPid(CLIENT_PID + 1)
        policy.callerPermission = true
        clearInvocations(appBinder)

        assertNotNull(endpoint.newProcess(arrayOf("sh", "-c", "exit 0"), null, null))

        verify(appBinder, never()).linkToDeath(any(IBinder.DeathRecipient::class.java), anyInt())
    }

    @Test
    fun newProcessTranslatesExecFailure() {
        policy.callerPermission = true

        assertThrows(IllegalStateException::class.java) {
            endpoint.newProcess(arrayOf("/nonexistent/porter-probe"), null, null)
        }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        const val TARGET_CODE = 7
        const val IN_PARCEL_FLAGS = 42
        const val PAYLOAD = 20816
        const val OUTER_FLAGS = 17
    }
}
