package rikka.shizuku.server

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Handler
import android.os.IBinder
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.UserServiceConnection
import eu.darken.porter.core.UserServiceOptions
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
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
import rikka.shizuku.ShizukuApiConstants
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
            manager.addUserService(
                CallerIdentity(UID + 1, PID), RecordingConnection(), bind(),
                ShizukuApiConstants.SERVER_VERSION,
            )
        }
        assertEquals(0, manager.created.size)

        assertEquals(
            0,
            manager.addUserService(
                CallerIdentity(UID, PID), RecordingConnection(), bind(),
                ShizukuApiConstants.SERVER_VERSION,
            ),
        )
        assertEquals(1, manager.created.size)
    }

    @Test
    fun attachByTokenStringBroadcastsToANeutralConnection() {
        val connection = RecordingConnection()
        manager.addUserService(CallerIdentity(UID, PID), connection, bind(), ShizukuApiConstants.SERVER_VERSION)
        val record = manager.created[0]

        val service = liveBinder()
        manager.attachUserService(service, record.token, DESCRIPTOR)

        assertEquals(listOf(service), connection.connected)
    }

    @Test
    fun removeWithoutTheRemoveFlagUnregistersTheNeutralConnection() {
        val kept = RecordingConnection()
        val dropped = RecordingConnection()
        manager.addUserService(CallerIdentity(UID, PID), kept, bind(), ShizukuApiConstants.SERVER_VERSION)
        val record = manager.created[0]
        manager.addUserService(CallerIdentity(UID, PID), dropped, bind(), ShizukuApiConstants.SERVER_VERSION)

        assertEquals(0, manager.removeUserService(CallerIdentity(UID, PID), dropped, unbindKeepingTheRecord()))

        assertFalse(record.isRemoved)

        record.broadcastBinderDied()

        assertEquals(1, kept.died)
        assertEquals(0, dropped.died)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        const val DESCRIPTOR = "eu.darken.porter.probe.IProbe"
        const val UID = 10123
        const val PID = 45678
    }
}
