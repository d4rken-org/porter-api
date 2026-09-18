package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
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

/**
 * What a delivery may do to a connection that is already live. Acceptance is decided under
 * {@code SESSION_LOCK}: a binder on the backend this process did not select is refused there, so it
 * never becomes the published connection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedSelectionAcceptanceTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

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

    /** Selects Shizuku and publishes a live connection on it. */
    private FakeShizukuService liveShizukuConnection() {
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION);
        assertEquals("the only installed manager is Shizuku's, so that is what this process selects",
                PorterSession.Selection.SHIZUKU, PorterSession.selectBackend(context));

        FakeShizukuService shizuku = new FakeShizukuService();
        Porter.onBinderReceived(context, shizuku, PACKAGE, PorterBackend.SHIZUKU);

        assertSame("the Shizuku delivery has to be the published connection before the route runs",
                shizuku, Porter.getBinder());
        assertEquals("the published connection has to be on Shizuku before the route runs",
                PorterBackend.SHIZUKU, PorterSession.currentBackend());
        return shizuku;
    }

    /**
     * Route C. The two-argument {@link Porter#onBinderReceived(android.os.IBinder, String)} consults no
     * selection at all, so what protects a live connection on that path is the locked live-backend check: a
     * Porter binder handed to it while a Shizuku connection is live is refused rather than attached to.
     */
    @Test
    public void theUngatedOverloadDoesNotPublishPorterOverALiveShizukuConnection() {
        FakeShizukuService shizuku = liveShizukuConnection();

        FakePorterService porter = new FakePorterService();
        Porter.onBinderReceived(porter, PACKAGE);

        assertSame("a Porter binder delivered through the ungated two-argument onBinderReceived"
                        + " replaced the live Shizuku connection this process selected",
                shizuku, Porter.getBinder());
        assertEquals("the published connection changed backend while it was live",
                PorterBackend.SHIZUKU, PorterSession.currentBackend());
        assertEquals("the Porter binder was attached to, so this process talked to a second server"
                        + " on a backend it never selected",
                0, porter.attachCount);
    }

    /**
     * Route B. A cross-process fetch on the other authority must not move this process's selection off the
     * backend it is connected on. {@link PorterSession#adoptBackend} leaves the selection alone while a
     * connection is live, so a later delivery on the other backend is still refused.
     */
    @Test
    public void adoptingABackendDoesNotMoveTheSelectionOffALiveConnection() {
        FakeShizukuService shizuku = liveShizukuConnection();

        PorterSession.adoptBackend(PorterBackend.PORTER);

        assertEquals("adoptBackend moved the selection to Porter while a Shizuku connection was"
                        + " live, so this process now answers for a backend it is not connected on",
                PorterSession.Selection.SHIZUKU, PorterSession.selectBackend(context));

        // What the moved selection then lets through: no package declares Porter's permission here,
        // so nothing but the moved selection can make this delivery acceptable.
        FakePorterService porter = new FakePorterService();
        Porter.onBinderReceived(context, porter, PACKAGE, PorterBackend.PORTER);

        assertSame("the selection adoptBackend moved let a Porter delivery replace the live Shizuku"
                        + " connection", shizuku, Porter.getBinder());
        assertEquals("the published connection changed backend while it was live",
                PorterBackend.SHIZUKU, PorterSession.currentBackend());
    }
}
