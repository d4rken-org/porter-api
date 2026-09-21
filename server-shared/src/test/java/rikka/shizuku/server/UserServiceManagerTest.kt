package rikka.shizuku.server

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import eu.darken.porter.core.UserServiceConnection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.util.HandlerUtil

/**
 * The bind, remove and attach seam of [UserServiceManager]: return values, key derivation,
 * option defaults and the order in which a caller's authorisation is decided.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserServiceManagerTest {

    private class TestManager : UserServiceManager() {
        val created: MutableList<UserServiceRecord> = CopyOnWriteArrayList()

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
        ): String = "exit 0"

        override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
            created.add(record)
        }
    }

    private lateinit var manager: TestManager
    private lateinit var packages: MockedStatic<PackageManagerApis>

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)

        val installed = PackageInfo()
        installed.packageName = PACKAGE
        installed.applicationInfo = ApplicationInfo()
        installed.applicationInfo!!.uid = UID
        installed.applicationInfo!!.sourceDir = "/data/app/porter-probe/base.apk"

        packages = Mockito.mockStatic(PackageManagerApis::class.java)
        packages.`when`<PackageInfo> { PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()) }
            .thenReturn(installed)

        ShadowBinder.setCallingUid(UID)
        manager = TestManager()
    }

    @After
    fun teardown() {
        packages.close()
        ShadowBinder.reset()
    }

    private fun connection(): IShizukuServiceConnection {
        val connection = mock(IShizukuServiceConnection::class.java)
        `when`(connection.asBinder()).thenReturn(mock(IBinder::class.java))
        return connection
    }

    private fun options(className: String): Bundle {
        val options = Bundle()
        options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, ComponentName(PACKAGE, className))
        options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 1)
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, true)
        return options
    }

    /** Replaces every bind-only value with one of the wrong type. */
    private fun poison(options: Bundle): Bundle {
        options.putBundle(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, Bundle())
        options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME, 3)
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, "yes")
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, "yes")
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, "yes")
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, "yes")
        return options
    }

    private fun add(connection: IShizukuServiceConnection, options: Bundle): UserServiceRecord {
        val before = manager.created.size
        manager.addUserService(connection, options, ShizukuApiConstants.SERVER_VERSION)
        assertEquals(before + 1, manager.created.size)
        return manager.created[manager.created.size - 1]
    }

    private fun liveBinder(): IBinder {
        val binder = mock(IBinder::class.java)
        `when`(binder.pingBinder()).thenReturn(true)
        `when`(binder.interfaceDescriptor).thenReturn(DESCRIPTOR)
        `when`(binder.transact(anyInt(), any(), any(), anyInt())).thenReturn(true)
        return binder
    }

    private fun attach(record: UserServiceRecord, binder: IBinder) {
        val options = Bundle()
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token)
        manager.attachUserService(binder, options)
    }

    /**
     * Robolectric's `RemoteCallbackList` shadow prunes its own registration map on binder
     * death and then calls the shadow's no-op `onCallbackDied`, never a subclass override, so
     * the record's reaction has to be driven separately. The override ignores its argument.
     */
    private fun killConnection(record: UserServiceRecord, connection: IShizukuServiceConnection) {
        val recipient = ArgumentCaptor.forClass(IBinder.DeathRecipient::class.java)
        verify(connection.asBinder()).linkToDeath(recipient.capture(), eq(0))
        recipient.value.binderDied()
        record.callbacks.onCallbackDied(mock(UserServiceConnection::class.java))
    }

    /** The cleanup executor is single threaded, so a task queued behind ours has drained it. */
    private fun drainCleanup() {
        val field = UserServiceManager::class.java.getDeclaredField("cleanupExecutor")
        field.isAccessible = true
        val drained = CountDownLatch(1)
        (field.get(manager) as Executor).execute { drained.countDown() }
        assertTrue(drained.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun noCreateReturnsAreVersionConditional() {
        val noCreate = options(CLASS)
        noCreate.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, true)

        assertEquals(-1, manager.addUserService(connection(), noCreate, 13))
        assertEquals(1, manager.addUserService(connection(), noCreate, 12))

        val record = add(connection(), options(CLASS))
        attach(record, liveBinder())

        assertEquals(record.versionCode, manager.addUserService(connection(), noCreate, 13))
        assertEquals(0, manager.addUserService(connection(), noCreate, 12))
    }

    @Test
    fun bindingToALiveRecordBroadcastsConnectedImmediately() {
        val record = add(connection(), options(CLASS))
        val binder = liveBinder()
        attach(record, binder)

        val later = connection()
        assertEquals(0, manager.addUserService(later, options(CLASS), ShizukuApiConstants.SERVER_VERSION))

        verify(later).connected(binder)
    }

    @Test
    fun attachBroadcastsConnectedToEveryRegisteredConnection() {
        val first = connection()
        val second = connection()
        val record = add(first, options(CLASS))
        manager.addUserService(second, options(CLASS), ShizukuApiConstants.SERVER_VERSION)

        val binder = liveBinder()
        attach(record, binder)

        verify(first).connected(binder)
        verify(second).connected(binder)
    }

    @Test
    fun removeWithoutRemoveFlagUnregistersOnlyTheConnection() {
        val kept = connection()
        val dropped = connection()
        val record = add(kept, options(CLASS))
        manager.addUserService(dropped, options(CLASS), ShizukuApiConstants.SERVER_VERSION)

        val remove = options(CLASS)
        remove.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE, false)
        assertEquals(0, manager.removeUserService(dropped, remove))

        assertFalse(record.isRemoved)

        record.broadcastBinderDied()

        verify(kept).died()
        verify(dropped, never()).died()
    }

    @Test
    fun removeReturnsOneForAnUnknownKey() {
        assertEquals(1, manager.removeUserService(connection(), options("NeverBound")))
    }

    @Test
    fun removeDefaultsToTrueForOlderClients() {
        val record = add(connection(), options(CLASS))

        assertEquals(0, manager.removeUserService(connection(), options(CLASS)))
        drainCleanup()

        assertTrue(record.isRemoved)
    }

    @Test
    fun tagOverridesTheClassNameInTheKey() {
        val bind = options(CLASS)
        bind.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, "probe")
        add(connection(), bind)

        assertEquals(1, manager.removeUserService(connection(), options(CLASS)))

        val byTag = options("SomeOtherService")
        byTag.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, "probe")
        assertEquals(0, manager.removeUserService(connection(), byTag))
    }

    @Test
    fun versionCodeMismatchReplacesTheRecord() {
        val first = add(connection(), options(CLASS))

        val newer = options(CLASS)
        newer.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 2)
        val second = add(connection(), newer)
        drainCleanup()

        assertNotSame(first, second)
        assertTrue(first.isRemoved)
        assertFalse(second.isRemoved)
        assertEquals(2, second.versionCode)
    }

    @Test
    fun daemonFlagIsUpdatedOnReuse() {
        val record = add(connection(), options(CLASS))
        assertTrue(record.daemon)

        val nonDaemon = options(CLASS)
        nonDaemon.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, false)
        manager.addUserService(connection(), nonDaemon, ShizukuApiConstants.SERVER_VERSION)

        assertEquals(1, manager.created.size)
        assertFalse(record.daemon)
    }

    @Test
    fun lastConnectionDeathRemovesANonDaemonRecord() {
        val connection = connection()
        val nonDaemon = options(CLASS)
        nonDaemon.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, false)
        val record = add(connection, nonDaemon)

        killConnection(record, connection)

        assertTrue(record.isRemoved)
    }

    @Test
    fun daemonRecordSurvivesConnectionDeath() {
        val connection = connection()
        val record = add(connection, options(CLASS))

        killConnection(record, connection)

        assertFalse(record.isRemoved)
    }

    @Test
    fun nullConnectionIsRefusedBeforeOptions() {
        val e = assertThrows(NullPointerException::class.java) {
            manager.addUserService(null, null, ShizukuApiConstants.SERVER_VERSION)
        }

        assertEquals("connection is null", e.message)
    }

    @Test
    fun missingComponentIsRefused() {
        val e = assertThrows(NullPointerException::class.java) {
            manager.addUserService(connection(), Bundle(), ShizukuApiConstants.SERVER_VERSION)
        }

        assertEquals("component is null", e.message)
    }

    @Test
    fun foreignPackageIsRefused() {
        ShadowBinder.setCallingUid(UID + 1)

        assertThrows(SecurityException::class.java) {
            manager.addUserService(connection(), options(CLASS), ShizukuApiConstants.SERVER_VERSION)
        }
        assertThrows(SecurityException::class.java) {
            manager.removeUserService(connection(), options(CLASS))
        }
    }

    @Test
    fun foreignPackageIsRefusedBeforeMalformedOptionsAreNoticed() {
        ShadowBinder.setCallingUid(UID + 1)

        val bind = options(CLASS)
        bind.putString(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, "not-an-int")
        bind.putBundle(ShizukuApiConstants.USER_SERVICE_ARG_TAG, Bundle())
        assertThrows(SecurityException::class.java) {
            manager.addUserService(connection(), bind, ShizukuApiConstants.SERVER_VERSION)
        }

        val remove = options(CLASS)
        remove.putString(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, "not-an-int")
        remove.putBundle(ShizukuApiConstants.USER_SERVICE_ARG_TAG, Bundle())
        assertThrows(SecurityException::class.java) {
            manager.removeUserService(connection(), remove)
        }
    }

    @Test
    fun removeIgnoresBindOnlyKeys() {
        assertEquals(1, manager.removeUserService(connection(), poison(options("NeverBound"))))

        add(connection(), options(CLASS))

        assertEquals(0, manager.removeUserService(connection(), poison(options(CLASS))))
    }

    @Test
    fun missingTokenIsRefusedOnAttach() {
        val e = assertThrows(NullPointerException::class.java) {
            manager.attachUserService(mock(IBinder::class.java), Bundle())
        }

        assertEquals("token is null", e.message)
    }

    @Test
    fun missingTokenStillReadsTheDescriptor() {
        val binder = mock(IBinder::class.java)
        `when`(binder.interfaceDescriptor).thenReturn(DESCRIPTOR)

        assertThrows(NullPointerException::class.java) { manager.attachUserService(binder, Bundle()) }

        verify(binder).interfaceDescriptor
    }

    @Test
    fun unknownTokenIsRefusedOnAttach() {
        val options = Bundle()
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, "not-a-token")

        assertThrows(IllegalArgumentException::class.java) { manager.attachUserService(liveBinder(), options) }
    }

    @Test
    fun removedRecordIsRefusedOnAttach() {
        val record = add(connection(), options(CLASS))
        record.removeSelf()
        drainCleanup()

        val options = Bundle()
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token)

        assertThrows(IllegalArgumentException::class.java) { manager.attachUserService(liveBinder(), options) }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        const val DESCRIPTOR = "eu.darken.porter.probe.IProbe"
        const val APP_ID = 10123
        const val UID = APP_ID
    }
}
