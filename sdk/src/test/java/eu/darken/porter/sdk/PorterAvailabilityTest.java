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
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private void permissionOwnedBy(String packageName) throws Exception {
        PermissionInfo permission = new PermissionInfo();
        permission.packageName = packageName;
        doReturn(permission).when(packages).getPermissionInfo(PorterProtocol.PERMISSION, 0);
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
    public void aBinderThatAnswersIsReportedWithoutAskingThePackageManager() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);

        assertEquals(Porter.Availability.CONNECTED, Porter.getAvailability(context));
        verifyNoInteractions(packages);
    }
}
