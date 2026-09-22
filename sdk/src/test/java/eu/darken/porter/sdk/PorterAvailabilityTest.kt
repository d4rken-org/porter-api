package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import eu.darken.porter.protocol.PorterProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

/** What the availability signal answers for each way a manager can be absent or present. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterAvailabilityTest {

    private val context: Context = mock(Context::class.java)
    private val packages: PackageManager = mock(PackageManager::class.java)

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        `when`(context.packageManager).thenReturn(packages)
        `when`(packages.getPermissionInfo(PorterProtocol.PERMISSION, 0))
            .thenThrow(PackageManager.NameNotFoundException())
        `when`(packages.getPermissionInfo(ShizukuProtocol.PERMISSION, 0))
            .thenThrow(PackageManager.NameNotFoundException())
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun permissionOwnedBy(packageName: String) = declared(PorterProtocol.PERMISSION, packageName)

    private fun shizukuPermissionOwnedBy(packageName: String) = declared(ShizukuProtocol.PERMISSION, packageName)

    private fun declared(permission: String, packageName: String) {
        val info = PermissionInfo()
        info.packageName = packageName
        doReturn(info).`when`(packages).getPermissionInfo(permission, 0)
    }

    @Test
    fun nobodyDeclaringThePermissionMeansNothingIsInstalled() = runBlocking<Unit> {
        assertEquals(PorterAvailability.NotInstalled, Porter.availability(context))
    }

    @Test
    fun aForeignOwnerOfThePermissionIsReportedAsUnrecognized() = runBlocking<Unit> {
        permissionOwnedBy("eu.darken.porter.impostor")

        assertEquals(PorterAvailability.InstalledUnrecognized, Porter.availability(context))
    }

    @Test
    fun theManagerWithoutABinderIsInstalledButNotConnected() = runBlocking<Unit> {
        permissionOwnedBy(PorterProtocol.MANAGER_APPLICATION_ID)

        assertEquals(PorterAvailability.InstalledNotConnected, Porter.availability(context))
    }

    @Test
    fun theShizukuManagerWithoutABinderIsInstalledButNotConnected() = runBlocking<Unit> {
        shizukuPermissionOwnedBy(ShizukuProtocol.MANAGER_APPLICATION_ID)

        assertEquals(PorterAvailability.InstalledNotConnected, Porter.availability(context))
    }

    @Test
    fun aForeignOwnerOfTheShizukuPermissionIsReportedAsUnrecognized() = runBlocking<Unit> {
        shizukuPermissionOwnedBy("moe.shizuku.impostor")

        assertEquals(PorterAvailability.InstalledUnrecognized, Porter.availability(context))
    }

    /** Porter is selected where both are installed, so the answer is about Porter's manager. */
    @Test
    fun bothManagersInstalledAnswerAboutPorter() = runBlocking<Unit> {
        permissionOwnedBy("eu.darken.porter.impostor")
        shizukuPermissionOwnedBy(ShizukuProtocol.MANAGER_APPLICATION_ID)

        assertEquals(PorterAvailability.InstalledUnrecognized, Porter.availability(context))
    }

    /** Without the compatibility artifact no Shizuku binder can arrive, so none is waited for. */
    @Test
    fun aShizukuManagerWithoutTheCompatibilityArtifactIsNotInstalled() = runBlocking<Unit> {
        shizukuPermissionOwnedBy(ShizukuProtocol.MANAGER_APPLICATION_ID)
        ShizukuCompat.setPresentForTest(false)

        assertEquals(PorterAvailability.NotInstalled, Porter.availability(context))
    }

    @Test
    fun aBinderThatAnswersIsReportedWithoutAskingThePackageManager() = runBlocking<Unit> {
        Porter.onBinderReceived(FakePorterService(), PACKAGE)

        assertEquals(PorterAvailability.Connected, Porter.availability(context))
        verifyNoInteractions(packages)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
