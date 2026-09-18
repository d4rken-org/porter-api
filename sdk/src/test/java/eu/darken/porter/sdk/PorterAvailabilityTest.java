package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import eu.darken.porter.protocol.PorterProtocol;

/** What the availability signal answers for each way a manager can be absent or present. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterAvailabilityTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    private final Context context = mock(Context.class);
    private final PackageManager packages = mock(PackageManager.class);

    @Before
    public void setup() throws Exception {
        when(context.getPackageManager()).thenReturn(packages);
        when(packages.getPermissionInfo(PorterProtocol.PERMISSION, 0))
                .thenThrow(new PackageManager.NameNotFoundException());
        when(packages.getPermissionInfo(ShizukuProtocol.PERMISSION, 0))
                .thenThrow(new PackageManager.NameNotFoundException());
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private void permissionOwnedBy(String packageName) throws Exception {
        declared(PorterProtocol.PERMISSION, packageName);
    }

    private void shizukuPermissionOwnedBy(String packageName) throws Exception {
        declared(ShizukuProtocol.PERMISSION, packageName);
    }

    private void declared(String permission, String packageName) throws Exception {
        PermissionInfo info = new PermissionInfo();
        info.packageName = packageName;
        doReturn(info).when(packages).getPermissionInfo(permission, 0);
    }

    @Test
    public void nobodyDeclaringThePermissionMeansNothingIsInstalled() {
        assertEquals(Porter.Availability.NOT_INSTALLED, Porter.getAvailability(context));
    }

    @Test
    public void aForeignOwnerOfThePermissionIsReportedAsUnrecognized() throws Exception {
        permissionOwnedBy("eu.darken.porter.impostor");

        assertEquals(Porter.Availability.INSTALLED_UNRECOGNIZED, Porter.getAvailability(context));
    }

    @Test
    public void theManagerWithoutABinderIsInstalledButNotConnected() throws Exception {
        permissionOwnedBy(PorterProtocol.MANAGER_APPLICATION_ID);

        assertEquals(Porter.Availability.INSTALLED_NOT_CONNECTED, Porter.getAvailability(context));
    }

    @Test
    public void theShizukuManagerWithoutABinderIsInstalledButNotConnected() throws Exception {
        shizukuPermissionOwnedBy(ShizukuProtocol.MANAGER_APPLICATION_ID);

        assertEquals(Porter.Availability.INSTALLED_NOT_CONNECTED, Porter.getAvailability(context));
    }

    @Test
    public void aForeignOwnerOfTheShizukuPermissionIsReportedAsUnrecognized() throws Exception {
        shizukuPermissionOwnedBy("moe.shizuku.impostor");

        assertEquals(Porter.Availability.INSTALLED_UNRECOGNIZED, Porter.getAvailability(context));
    }

    /** Porter is selected where both are installed, so the answer is about Porter's manager. */
    @Test
    public void bothManagersInstalledAnswerAboutPorter() throws Exception {
        permissionOwnedBy("eu.darken.porter.impostor");
        shizukuPermissionOwnedBy(ShizukuProtocol.MANAGER_APPLICATION_ID);

        assertEquals(Porter.Availability.INSTALLED_UNRECOGNIZED, Porter.getAvailability(context));
    }

    /** Without the compatibility artifact no Shizuku binder can arrive, so none is waited for. */
    @Test
    public void aShizukuManagerWithoutTheCompatibilityArtifactIsNotInstalled() throws Exception {
        shizukuPermissionOwnedBy(ShizukuProtocol.MANAGER_APPLICATION_ID);
        ShizukuCompat.setPresentForTest(false);

        assertEquals(Porter.Availability.NOT_INSTALLED, Porter.getAvailability(context));
    }

    @Test
    public void aBinderThatAnswersIsReportedWithoutAskingThePackageManager() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);

        assertEquals(Porter.Availability.CONNECTED, Porter.getAvailability(context));
        verifyNoInteractions(packages);
    }
}
