package rikka.shizuku.server

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import moe.shizuku.server.IShizukuServiceConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.mockito.ArgumentMatchers.longThat
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.ShizukuApiConstants
import rikka.shizuku.server.legacy.LegacyServiceConnection
import rikka.shizuku.server.util.HandlerUtil

/** Removal, detach and launch-cancellation behaviour of [UserServiceManager]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserServiceLifecycleTest {

    private lateinit var manager: TestManager
    private lateinit var packages: MockedStatic<PackageManagerApis>
    private lateinit var installed: PackageInfo
    private val pendingTimeout = AtomicReference<Runnable>()

    private class TestManager : UserServiceManager() {
        val created: MutableList<UserServiceRecord> = CopyOnWriteArrayList()
        val detached: MutableList<UserServiceRecord> = CopyOnWriteArrayList()
        val spawned: MutableList<String> = CopyOnWriteArrayList()
        var startCmd: (String) -> String = { "exit 0" }

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
            spawned.add(key)
            return startCmd(key)
        }

        override fun onUserServiceRecordCreated(record: UserServiceRecord, packageInfo: PackageInfo) {
            created.add(record)
        }

        override fun onUserServiceRecordRemoved(record: UserServiceRecord) {
            detached.add(record)
        }
    }

    @Before
    fun setup() {
        // A real handler would drop the timeout callback on removal, hiding the guard inside it.
        val handler = mock(Handler::class.java)
        `when`(handler.postDelayed(any(Runnable::class.java), anyLong())).thenAnswer { invocation ->
            pendingTimeout.set(invocation.getArgument(0))
            true
        }
        HandlerUtil.mainHandler = handler

        installed = PackageInfo()
        installed.packageName = PACKAGE
        installed.applicationInfo = ApplicationInfo()
        installed.applicationInfo!!.uid = UID
        installed.applicationInfo!!.sourceDir = "/data/app/porter-probe/base.apk"
        installed.signatures = arrayOf(Signature("0a0b"))

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

    private fun connection(): IShizukuServiceConnection = object : IShizukuServiceConnection.Stub() {
        override fun connected(service: IBinder?) {}
        override fun died() {}
    }

    private fun options(className: String): Bundle {
        val options = Bundle()
        options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, ComponentName(PACKAGE, className))
        options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 1)
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, true)
        return options
    }

    private fun add(className: String): UserServiceRecord {
        val before = manager.created.size
        manager.addUserService(connection(), options(className), ShizukuApiConstants.SERVER_VERSION)
        assertEquals(before + 1, manager.created.size)
        return manager.created[manager.created.size - 1]
    }

    /** Publishes [binder] for [record] the way the starter's attach path does. */
    private fun attach(record: UserServiceRecord, binder: IBinder) {
        val options = Bundle()
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token)
        manager.attachUserService(binder, options)
    }

    private fun liveBinder(transactions: MutableList<Int>): IBinder {
        val binder = mock(IBinder::class.java)
        `when`(binder.pingBinder()).thenReturn(true)
        `when`(binder.interfaceDescriptor).thenReturn(DESCRIPTOR)
        `when`(binder.transact(anyInt(), any(), any(), anyInt())).thenAnswer { invocation ->
            transactions.add(invocation.getArgument(0))
            true
        }
        return binder
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> index(name: String): T = try {
        val field = UserServiceManager::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(manager) as T
    } catch (e: ReflectiveOperationException) {
        throw AssertionError(e)
    }

    private fun byKey(): Map<String, UserServiceRecord> = HashMap(index<Map<String, UserServiceRecord>>("userServiceRecords"))

    private fun byPackage(): Map<String, List<UserServiceRecord>> =
        HashMap(index<Map<String, List<UserServiceRecord>>>("packageUserServiceRecords"))

    /** The cleanup executor is single threaded, so a task queued behind ours has drained it. */
    private fun drainCleanup() {
        val drained = CountDownLatch(1)
        index<Executor>("cleanupExecutor").execute { drained.countDown() }
        assertTrue(drained.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun aSecondRemovalDoesNotDestroyTheServiceTwice() {
        val transactions = CopyOnWriteArrayList<Int>()
        val record = add("ProbeService")
        attach(record, liveBinder(transactions))

        record.removeSelf()
        record.removeSelf()
        drainCleanup()

        assertEquals(listOf(ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy), transactions)
        assertEquals(1, manager.detached.size)
    }

    @Test
    fun removingAPackageRemovesEveryRecordAndTheListItself() {
        val first = add("ProbeService")
        val second = add("OtherService")

        manager.removeUserServicesForPackage(PACKAGE)
        drainCleanup()

        assertEquals(listOf(first, second), ArrayList(manager.detached))
        assertTrue(byKey().isEmpty())
        assertFalse(byPackage().containsKey(PACKAGE))
    }

    @Test
    fun binderDeathPrunesBothIndexes() {
        val record = add("ProbeService")
        val binder = liveBinder(CopyOnWriteArrayList())
        attach(record, binder)

        val recipient = ArgumentCaptor.forClass(IBinder.DeathRecipient::class.java)
        Mockito.verify(binder).linkToDeath(recipient.capture(), eq(0))
        recipient.value.binderDied()
        drainCleanup()

        assertTrue(record.isRemoved)
        assertTrue(byKey().isEmpty())
        assertFalse(byPackage().containsKey(PACKAGE))
    }

    @Test
    fun aStartTimeoutThatFiresAfterDetachIsANoOp() {
        val record = add("ProbeService")
        val timeout = pendingTimeout.get()
        assertNotNull(timeout)

        record.removeSelf()
        drainCleanup()
        assertEquals(1, manager.detached.size)

        timeout.run()

        assertEquals(1, manager.detached.size)
    }

    @Test
    fun aRemovalWhileTheStartTaskWaitsPreventsTheSpawn() {
        val blocked = CountDownLatch(1)
        val sentinel = CountDownLatch(1)
        manager.startCmd = { key ->
            if (key.endsWith(":Blocker")) {
                try {
                    assertTrue(blocked.await(5, TimeUnit.SECONDS))
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            } else if (key.endsWith(":Sentinel")) {
                sentinel.countDown()
            }
            // Never reaching Runtime.exec keeps a real shell out of the test; the executor replaces
            // the worker and drains what is still queued.
            throw IllegalStateException("no shell in tests")
        }

        add("Blocker")
        val victim = add("Victim")
        add("Sentinel")

        victim.removeSelf()
        blocked.countDown()
        assertTrue(sentinel.await(5, TimeUnit.SECONDS))

        assertFalse(manager.spawned.contains("$USER_ID:$PACKAGE:Victim"))
        assertTrue(manager.spawned.contains("$USER_ID:$PACKAGE:Sentinel"))
    }

    @Test
    fun destroyAgainstADeadRemoteStillKillsTheCallbacks() {
        val record = add("ProbeService")
        val binder = mock(IBinder::class.java)
        `when`(binder.pingBinder()).thenReturn(true)
        `when`(binder.interfaceDescriptor).thenReturn(DESCRIPTOR)
        `when`(binder.transact(anyInt(), any(), any(), anyInt())).thenThrow(DeadObjectException())
        attach(record, binder)

        record.removeSelf()
        drainCleanup()

        assertFalse(record.callbacks.register(LegacyServiceConnection(connection())))
    }

    @Test
    fun aStartTimeoutRecordIsDestroyedWithoutABinder() {
        val record = add("ProbeService")

        record.removeSelf()
        drainCleanup()

        assertFalse(record.callbacks.register(LegacyServiceConnection(connection())))
    }

    @Test
    fun tokenLivenessFollowsTheRecord() {
        val record = add("ProbeService")
        assertTrue(manager.isUserServiceTokenLive(record.token))
        assertFalse(manager.isUserServiceTokenLive("not-a-token"))
        assertFalse(manager.isUserServiceTokenLive(null))

        record.removeSelf()
        drainCleanup()

        assertFalse(manager.isUserServiceTokenLive(record.token))
    }

    @Test
    fun theAuthorisingLookupAlsoCarriesSignatures() {
        val packageInfo = manager.ensureCallingPackageForUserService(PACKAGE, APP_ID, 0)

        assertEquals(installed, packageInfo)
        packages.verify {
            PackageManagerApis.getPackageInfoNoThrow(
                eq(PACKAGE),
                longThat { flags ->
                    (flags and PackageManager.GET_SIGNING_CERTIFICATES.toLong()) != 0L &&
                        (flags and 0x00002000L) != 0L
                },
                eq(0),
            )
        }
    }

    @Test
    fun theAuthorisingLookupStillRejectsAForeignPackage() {
        assertThrows(SecurityException::class.java) {
            manager.ensureCallingPackageForUserService(PACKAGE, APP_ID + 1, 0)
        }
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val APP_ID = 10123
        const val UID = APP_ID
        const val USER_ID = 0
        const val DESCRIPTOR = "eu.darken.porter.probe.IProbe"
    }
}
