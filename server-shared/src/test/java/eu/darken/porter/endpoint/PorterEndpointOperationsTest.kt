package eu.darken.porter.endpoint

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.UserServiceManager
import rikka.shizuku.server.UserServiceRecord
import rikka.shizuku.server.util.HandlerUtil

/** What the Porter endpoint's operations do with the caller's record, options and gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterEndpointOperationsTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var userServices: RecordingUserServiceManager
    private lateinit var endpoint: PorterEndpoint
    private lateinit var packages: MockedStatic<PackageManagerApis>

    /** Records what reached the manager: the record carries only part of the decoded options. */
    private class RecordingUserServiceManager : UserServiceManager() {

        val created = CopyOnWriteArrayList<UserServiceRecord>()
        val started = CountDownLatch(1)

        @Volatile
        var key: String? = null

        @Volatile
        var className: String? = null

        @Volatile
        var processNameSuffix: String? = null

        override fun getUserServiceStartCmd(
            record: UserServiceRecord,
            key: String,
            token: String,
            packageName: String,
            classname: String,
            processNameSuffix: String?,
            callingUid: Int,
            use32Bits: Boolean,
            debug: Boolean,
        ): String {
            this.key = key
            this.className = classname
            this.processNameSuffix = processNameSuffix
            started.countDown()
            return "exit 0"
        }

        override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
            created.add(record)
        }
    }

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        userServices = RecordingUserServiceManager()
        endpoint = PorterEndpoint(
            ServerTestSupport.newCore(clients, userServices, config, ServerTestSupport.TestPolicy()) { listOf(PACKAGE) },
        )

        val installed = PackageInfo()
        installed.packageName = PACKAGE
        installed.applicationInfo = ApplicationInfo().apply {
            uid = CLIENT_UID
            sourceDir = "/data/app/porter-probe/base.apk"
        }
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

    private fun attach(): IPorterApplication {
        val application = porterApplication(mock(IBinder::class.java))
        val args = Bundle()
        args.putString(ATTACH_PACKAGE_NAME, PACKAGE)
        args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION)
        endpoint.attach(application, args)
        return application
    }

    @Test
    @Throws(Exception::class)
    fun requestPermissionFromAnAllowedClientRepliesTrue() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        val application = attach()

        endpoint.requestPermission(7)

        val reply = ArgumentCaptor.forClass(Bundle::class.java)
        verify(application).dispatchRequestPermissionResult(eq(7), reply.capture())
        assertTrue(reply.value.getBoolean(PERMISSION_RESULT_ALLOWED))
    }

    @Test
    @Throws(Exception::class)
    fun requestPermissionWithADeniedEntryRepliesFalse() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(false, true))
        val application = attach()

        endpoint.requestPermission(9)

        val reply = ArgumentCaptor.forClass(Bundle::class.java)
        verify(application).dispatchRequestPermissionResult(eq(9), reply.capture())
        assertFalse(reply.value.getBoolean(PERMISSION_RESULT_ALLOWED))
    }

    @Test
    fun requestPermissionFromAnUnattachedCallerThrowsIllegalState() {
        assertThrows(IllegalStateException::class.java) { endpoint.requestPermission(11) }
    }

    @Test
    @Throws(Exception::class)
    fun addUserServiceDecodesThePorterKeys() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        attach()

        assertEquals(PorterProtocol.USER_SERVICE_RESULT_BOUND, endpoint.addUserService(connection(), bindArgs(false)))

        assertEquals(1, userServices.created.size)
        val record = userServices.created[0]
        assertEquals(3, record.versionCode)
        assertFalse(record.daemon)
        assertEquals(1, record.callbacks.registeredCallbackCount)
        assertEquals(1, record.callbacks.beginBroadcast())
        try {
            assertTrue(record.callbacks.getBroadcastItem(0) is PorterServiceConnection)
        } finally {
            record.callbacks.finishBroadcast()
        }

        assertTrue(userServices.started.await(5, TimeUnit.SECONDS))
        // Led by the caller's Android user, so a profile's copy of the app gets its own process.
        assertEquals("10:$PACKAGE:$TAG", userServices.key)
        assertEquals(CLASS, userServices.className)
        assertEquals(PROCESS_NAME_SUFFIX, userServices.processNameSuffix)
    }

    @Test
    fun peekingAtAServiceThatIsNotRunningAnswersMinusOne() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        attach()

        assertEquals(PorterProtocol.USER_SERVICE_RESULT_NOT_RUNNING, endpoint.addUserService(connection(), bindArgs(true)))
        assertTrue(userServices.created.isEmpty())
    }

    @Test
    fun removingAServiceNobodyStartedSaysSo() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        attach()

        assertEquals(PorterProtocol.USER_SERVICE_RESULT_NO_SUCH_SERVICE, endpoint.removeUserService(connection(), removeArgs(true)))
    }

    /** Unregistering names what to unregister; without a connection only a removal makes sense. */
    @Test
    fun unregisteringWithoutAConnectionIsRefused() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        attach()
        endpoint.addUserService(connection(), bindArgs(false))

        assertThrows(IllegalArgumentException::class.java) { endpoint.removeUserService(null, removeArgs(false)) }

        assertFalse(userServices.created[0].isRemoved)
        assertEquals(0, endpoint.removeUserService(null, removeArgs(true)))
        assertTrue(userServices.created[0].isRemoved)
    }

    /**
     * A refused caller is told it has no permission, not what its Bundle failed to decode to: a
     * null or component-less Bundle would raise something else if the gate ran after the decoder.
     */
    @Test
    fun theUserServiceCallsRefuseAnUnauthorizedCallerBeforeReadingItsBundle() {
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), null) }
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), Bundle()) }
        assertThrows(SecurityException::class.java) { endpoint.removeUserService(connection(), Bundle()) }
    }

    /**
     * Through the generated interface, whose parameter is a platform type: the wire can carry a
     * null Bundle whatever the Kotlin signature says, and the gate has to run before it is read.
     */
    @Test
    fun removeUserServiceRefusesAnUnauthorizedCallerBeforeReadingANullBundle() {
        assertThrows(SecurityException::class.java) { (endpoint as IPorterService).removeUserService(connection(), null) }
    }

    @Test
    @Throws(Exception::class)
    fun removeUnregistersWithoutRemovingUnlessAsked() {
        `when`(config.find(CLIENT_UID)).thenReturn(ServerTestSupport.entry(true, false))
        attach()
        val connection = connection()
        endpoint.addUserService(connection, bindArgs(false))
        val record = userServices.created[0]

        assertEquals(0, endpoint.removeUserService(connection, removeArgs(false)))

        assertEquals(0, record.callbacks.registeredCallbackCount)
        assertFalse(record.isRemoved)

        assertEquals(0, endpoint.removeUserService(connection, removeArgs(true)))

        assertTrue(record.isRemoved)
    }

    @Test
    fun everyGatedOperationRefusesACallerWithoutPermission() {
        assertThrows(SecurityException::class.java) { endpoint.uid }
        assertThrows(SecurityException::class.java) { endpoint.checkPermission("android.permission.DUMP") }
        assertThrows(SecurityException::class.java) { endpoint.seLinuxContext }
        assertThrows(SecurityException::class.java) { endpoint.getSystemProperty("ro.build.id", "") }
        assertThrows(SecurityException::class.java) { endpoint.setSystemProperty("ro.build.id", "") }
        assertThrows(SecurityException::class.java) { endpoint.addUserService(connection(), bindArgs(false)) }
        assertThrows(SecurityException::class.java) { endpoint.removeUserService(connection(), removeArgs(true)) }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        const val TAG = "probe-tag"
        const val PROCESS_NAME_SUFFIX = "probe"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        fun porterApplication(binder: IBinder): IPorterApplication {
            val application = mock(IPorterApplication::class.java)
            `when`(application.asBinder()).thenReturn(binder)
            return application
        }

        fun connection(): IPorterServiceConnection {
            val connection = mock(IPorterServiceConnection::class.java)
            `when`(connection.asBinder()).thenReturn(mock(IBinder::class.java))
            return connection
        }

        fun bindArgs(noCreate: Boolean): Bundle = Bundle().apply {
            putParcelable(USER_SERVICE_COMPONENT, ComponentName(PACKAGE, CLASS))
            putString(USER_SERVICE_TAG, TAG)
            putInt(USER_SERVICE_VERSION_CODE, 3)
            putBoolean(USER_SERVICE_DAEMON, false)
            putString(USER_SERVICE_PROCESS_NAME_SUFFIX, PROCESS_NAME_SUFFIX)
            putBoolean(USER_SERVICE_NO_CREATE, noCreate)
        }

        fun removeArgs(remove: Boolean): Bundle = Bundle().apply {
            putParcelable(USER_SERVICE_COMPONENT, ComponentName(PACKAGE, CLASS))
            putString(USER_SERVICE_TAG, TAG)
            putBoolean(USER_SERVICE_REMOVE, remove)
        }
    }
}
