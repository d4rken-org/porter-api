package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.PermissionInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import eu.darken.porter.protocol.PorterProtocol;

/** Which backend a process takes a delivery on, and what a delivery on the other one does. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterBackendSelectionTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /** Counts the death links a session registers, which a local binder otherwise swallows. */
    private static final class CountingPorterService extends FakePorterService {

        int deathLinks;

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) {
            deathLinks++;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return true;
        }
    }

    /** Counts the death links a session registers, which a local binder otherwise swallows. */
    private static final class CountingShizukuService extends FakeShizukuService {

        int deathLinks;

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) {
            deathLinks++;
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return true;
        }
    }

    private Context context;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private void declares(String packageName, String permission) {
        PermissionInfo info = new PermissionInfo();
        info.name = permission;
        info.packageName = packageName;
        shadowOf(context.getPackageManager()).addPermissionInfo(info);
    }

    private void porterIsInstalled() {
        declares(PorterProtocol.MANAGER_APPLICATION_ID, PorterProtocol.PERMISSION);
    }

    private void shizukuIsInstalled() {
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION);
    }

    private CountingPorterService deliverPorter() {
        CountingPorterService fake = new CountingPorterService();
        Porter.onBinderReceived(context, fake, PACKAGE, PorterBackend.PORTER);
        return fake;
    }

    private CountingShizukuService deliverShizuku() {
        CountingShizukuService fake = new CountingShizukuService();
        Porter.onBinderReceived(context, fake, PACKAGE, PorterBackend.SHIZUKU);
        return fake;
    }

    @Test
    public void onlyPorterInstalledSelectsPorterAndIgnoresAShizukuDelivery() {
        porterIsInstalled();

        assertEquals(PorterSession.Selection.PORTER, PorterSession.selectBackend(context));

        CountingShizukuService ignored = deliverShizuku();

        assertNull(Porter.getBinder());
        assertEquals(0, ignored.attachCount);
        assertEquals(0, ignored.deathLinks);
    }

    @Test
    public void onlyShizukuInstalledSelectsShizukuAndIgnoresAPorterDelivery() {
        shizukuIsInstalled();

        assertEquals(PorterSession.Selection.SHIZUKU, PorterSession.selectBackend(context));

        CountingPorterService ignored = deliverPorter();

        assertNull(Porter.getBinder());
        assertEquals(0, ignored.attachCount);
        assertEquals(0, ignored.deathLinks);
    }

    @Test
    public void bothInstalledSelectsPorter() {
        porterIsInstalled();
        shizukuIsInstalled();

        assertEquals(PorterSession.Selection.PORTER, PorterSession.selectBackend(context));

        CountingPorterService porter = deliverPorter();

        assertSame(porter, Porter.getBinder());
        assertEquals(1, porter.attachCount);
    }

    @Test
    public void neitherInstalledSelectsNothingAndIgnoresBothDeliveries() {
        assertEquals(PorterSession.Selection.NONE, PorterSession.selectBackend(context));

        CountingPorterService porter = deliverPorter();
        CountingShizukuService shizuku = deliverShizuku();

        assertNull(Porter.getBinder());
        assertEquals(0, porter.attachCount);
        assertEquals(0, porter.deathLinks);
        assertEquals(0, shizuku.attachCount);
        assertEquals(0, shizuku.deathLinks);
    }

    @Test
    public void shizukuWithoutTheCompatibilityArtifactSelectsNothing() {
        shizukuIsInstalled();
        ShizukuCompat.setPresentForTest(false);

        assertEquals(PorterSession.Selection.NONE, PorterSession.selectBackend(context));

        CountingShizukuService ignored = deliverShizuku();

        assertNull(Porter.getBinder());
        assertEquals(0, ignored.attachCount);
        assertEquals(0, ignored.deathLinks);
    }

    @Test
    public void aPackageThatIsNotTheKnownPorterManagerStillSelectsPorter() {
        declares("eu.darken.porter.fork", PorterProtocol.PERMISSION);

        assertEquals(PorterSession.Selection.PORTER, PorterSession.selectBackend(context));

        CountingPorterService porter = deliverPorter();

        assertSame(porter, Porter.getBinder());
    }

    @Test
    public void aPackageThatIsNotTheKnownShizukuManagerStillSelectsShizuku() {
        declares("moe.shizuku.fork", ShizukuProtocol.PERMISSION);

        assertEquals(PorterSession.Selection.SHIZUKU, PorterSession.selectBackend(context));

        CountingShizukuService shizuku = deliverShizuku();

        assertSame(shizuku, Porter.getBinder());
    }

    @Test
    public void aSelectionMadeWithNothingConnectedIsResolvedAgainAfterAnInstall() {
        assertEquals(PorterSession.Selection.NONE, PorterSession.selectBackend(context));

        porterIsInstalled();

        assertEquals(PorterSession.Selection.PORTER, PorterSession.selectBackend(context));

        CountingPorterService porter = deliverPorter();

        assertSame(porter, Porter.getBinder());
    }

    @Test
    public void anInstallDuringALiveConnectionDoesNotMoveTheSelection() {
        shizukuIsInstalled();
        CountingShizukuService shizuku = deliverShizuku();
        assertSame(shizuku, Porter.getBinder());

        porterIsInstalled();

        assertEquals(PorterSession.Selection.SHIZUKU, PorterSession.selectBackend(context));
    }
}
