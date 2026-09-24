package rikka.shizuku.server

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.IBinder
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.HostProcess
import eu.darken.porter.core.UserServiceBindResult
import eu.darken.porter.core.UserServiceConnection
import eu.darken.porter.core.UserServiceOptions
import eu.darken.porter.core.UserServiceRemoveResult
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.hidden.compat.PackageManagerApis
import rikka.shizuku.server.util.HandlerUtil

/** The neutral bind, remove and attach entry points of [UserServiceManager]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class UserServiceManagerNeutralTest {

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

        val hosts: MutableMap<Int, HostProcess> = HashMap()

        override fun captureHost(pid: Int): HostProcess? = hosts[pid]

        val killsArmed: MutableList<HostProcess> = CopyOnWriteArrayList()

        override fun scheduleHostKill(host: HostProcess) {
            killsArmed.add(host)
        }
    }

    /** A connection that is not a Shizuku one, so nothing can quietly unwrap it. */
    private class RecordingConnection : UserServiceConnection {
        val binder: IBinder = mock(IBinder::class.java)
        val connected: MutableList<IBinder> = CopyOnWriteArrayList()
        var died = 0

        override fun asBinder(): IBinder = binder

        override fun connected(service: IBinder) {
            connected.add(service)
        }

        override fun died() {
            died++
        }
    }

    private lateinit var manager: TestManager

    @get:Rule
    val proc = TemporaryFolder()

    /** A host process as /proc would describe it; a different [startTime] is a recycled pid. */
    private fun host(pid: Int, uid: Int = HOST_UID, startTime: Long = 918273): HostProcess {
        val dir = File(proc.root, "$pid").apply { mkdirs() }
        val filler = (3..21).joinToString(" ") { if (it == 3) "S" else "0" }
        File(dir, "stat").writeText("$pid (app_process) $filler $startTime 0\n")
        File(dir, "status").writeText("Uid:\t$uid\t$uid\t$uid\t$uid\n")
        return HostProcess.capture(pid, proc.root)!!
    }

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

        // Anything falling back on Binder would authorise against an app id that owns nothing here.
        ShadowBinder.setCallingUid(UID + 500)
        ShadowBinder.setCallingPid(PID + 500)
        manager = TestManager()
    }

    @After
    fun teardown() {
        packages.close()
        ShadowBinder.reset()
    }

    private fun bind(): UserServiceOptions =
        UserServiceOptions(ComponentName(PACKAGE, CLASS), null, 1, null, false, false, true, false, true)

    private fun unbindKeepingTheRecord(): UserServiceOptions =
        UserServiceOptions(ComponentName(PACKAGE, CLASS), null, 1, null, false, false, true, false, false)

    private fun liveBinder(): IBinder {
        val binder = mock(IBinder::class.java)
        `when`(binder.pingBinder()).thenReturn(true)
        `when`(binder.interfaceDescriptor).thenReturn(DESCRIPTOR)
        `when`(binder.transact(anyInt(), any(), any(), anyInt())).thenReturn(true)
        return binder
    }

    @Test
    fun bindAuthorisesAgainstTheHandedIdentity() {
        assertThrows(SecurityException::class.java) {
            manager.addUserService(CallerIdentity(UID + 1, PID), RecordingConnection(), bind())
        }
        assertEquals(0, manager.created.size)

        assertSame(
            UserServiceBindResult.Bound,
            manager.addUserService(CallerIdentity(UID, PID), RecordingConnection(), bind()),
        )
        assertEquals(1, manager.created.size)
    }

    @Test
    fun bindRevokedBeforeTheMonitorCreatesNoRecord() {
        assertThrows(SecurityException::class.java) {
            manager.addUserService(CallerIdentity(UID, PID), RecordingConnection(), bind()) { false }
        }
        assertEquals(0, manager.created.size)
    }

    @Test
    fun attachByTokenStringBroadcastsToANeutralConnection() {
        val connection = RecordingConnection()
        manager.addUserService(CallerIdentity(UID, PID), connection, bind())
        val record = manager.created[0]

        val service = liveBinder()
        manager.attachUserService(service, record.token, DESCRIPTOR)

        assertEquals(listOf(service), connection.connected)
    }

    @Test
    fun aSecondAttachUnderTheSameTokenIsRefused() {
        val connection = RecordingConnection()
        manager.addUserService(CallerIdentity(UID, PID), connection, bind())
        val record = manager.created[0]
        val service = liveBinder()
        manager.attachUserService(service, record.token, DESCRIPTOR)

        assertThrows(IllegalArgumentException::class.java) {
            manager.attachUserService(liveBinder(), record.token, DESCRIPTOR)
        }

        assertSame(service, record.service)
        assertEquals(listOf(service), connection.connected)
    }

    @Test
    fun removeWithoutTheRemoveFlagUnregistersTheNeutralConnection() {
        val kept = RecordingConnection()
        val dropped = RecordingConnection()
        manager.addUserService(CallerIdentity(UID, PID), kept, bind())
        val record = manager.created[0]
        manager.addUserService(CallerIdentity(UID, PID), dropped, bind())

        assertSame(UserServiceRemoveResult.Removed, manager.removeUserService(CallerIdentity(UID, PID), dropped, unbindKeepingTheRecord()))

        assertFalse(record.isRemoved)

        record.broadcastBinderDied()

        assertEquals(1, kept.died)
        assertEquals(0, dropped.died)
    }

    @Test
    fun theFirstHostToClaimALaunchKeepsIt() {
        manager.addUserService(CallerIdentity(UID, PID), RecordingConnection(), bind())
        val record = manager.created[0]
        manager.hosts[HOST_PID] = host(HOST_PID)
        manager.hosts[HOST_PID + 1] = host(HOST_PID + 1)

        assertTrue(manager.claimUserServiceLaunch(record.token, HOST_PID, HOST_UID))
        assertTrue(manager.claimUserServiceLaunch(record.token, HOST_PID, HOST_UID))
        assertFalse(manager.claimUserServiceLaunch(record.token, HOST_PID + 1, HOST_UID))
        assertEquals(HOST_PID, record.host!!.pid)
    }

    @Test
    fun aClaimIsRefusedWithoutALiveStartingRecordOrTheServerUid() {
        manager.hosts[HOST_PID] = host(HOST_PID)
        assertFalse(manager.claimUserServiceLaunch("not-a-token", HOST_PID, HOST_UID))
        assertFalse(manager.claimUserServiceLaunch(null, HOST_PID, HOST_UID))

        manager.addUserService(CallerIdentity(UID, PID), RecordingConnection(), bind())
        val record = manager.created[0]
        assertFalse(manager.claimUserServiceLaunch(record.token, HOST_PID, HOST_UID + 1))
        assertFalse(manager.claimUserServiceLaunch(record.token, HOST_PID + 7, HOST_UID))

        manager.attachUserService(liveBinder(), record.token, DESCRIPTOR)
        assertFalse(manager.claimUserServiceLaunch(record.token, HOST_PID, HOST_UID))
    }

    @Test
    fun removingAClaimedRecordArmsTheKillForItsOwnHost() {
        manager.addUserService(CallerIdentity(UID, PID), RecordingConnection(), bind())
        val record = manager.created[0]
        manager.hosts[HOST_PID] = host(HOST_PID)
        manager.claimUserServiceLaunch(record.token, HOST_PID, HOST_UID)

        record.removeSelf()

        assertTrue(record.isRemoved)
        assertFalse(manager.claimUserServiceLaunch(record.token, HOST_PID, HOST_UID))
        assertEquals(listOf(HOST_PID), manager.killsArmed.map { it.pid })
    }

    @Test
    fun removingAnUnclaimedRecordArmsNoKill() {
        manager.addUserService(CallerIdentity(UID, PID), RecordingConnection(), bind())

        manager.created[0].removeSelf()

        assertTrue(manager.killsArmed.isEmpty())
    }

    @Test
    fun removingByUidLeavesTheSamePackageInAnotherUserAlone() {
        manager.addUserService(CallerIdentity(UID, PID), RecordingConnection(), bind())
        val own = manager.created[0]

        manager.removeUserServicesForUid(UID + 100000)
        assertFalse(own.isRemoved)

        manager.removeUserServicesForUid(UID)
        assertTrue(own.isRemoved)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        const val DESCRIPTOR = "eu.darken.porter.probe.IProbe"
        const val UID = 10123
        const val PID = 45678
        const val HOST_PID = 7001
        const val HOST_UID = 2000
    }
}
