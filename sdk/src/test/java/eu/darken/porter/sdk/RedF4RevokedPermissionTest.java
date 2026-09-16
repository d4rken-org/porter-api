package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;
import android.os.Bundle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.darken.porter.server.IPorterApplication;

/** A permission revoked while a replacement was attaching must not survive the rollback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF4RevokedPermissionTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /** Reaches the server, waits for the test, and then refuses the client. */
    private static class BlockingRefusingService extends FakePorterService {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Bundle attach(IPorterApplication application, Bundle args) {
            super.attach(application, args);
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            throw new SecurityException("not an attached client");
        }
    }

    private static Bundle permissionState(boolean granted, boolean shouldShowRationale) {
        Bundle state = new Bundle();
        state.putBoolean(REPLY_PERMISSION_GRANTED, granted);
        state.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale);
        return state;
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void aRevocationDuringAFailedReplacementIsNotForgotten() throws Exception {
        FakePorterService serving = new FakePorterService();
        serving.attachReply = permissionState(true, false);
        serving.selfPermission = false;
        Porter.onBinderReceived(serving, PACKAGE);
        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());

        BlockingRefusingService refused = new BlockingRefusingService();
        Thread attaching = new Thread(() -> Porter.onBinderReceived(refused, PACKAGE), "porter-attach");
        attaching.setDaemon(true);
        attaching.start();
        assertTrue("the replacement never reached the server", refused.entered.await(5, TimeUnit.SECONDS));

        // Porter revokes the grant on the connection that is still serving calls.
        serving.application.dispatchPermissionStateChanged(permissionState(false, true));

        refused.release.countDown();
        attaching.join(TimeUnit.SECONDS.toMillis(5));

        assertSame("the serving connection was not restored", serving, Porter.getBinder());
        assertEquals("the revoked permission survived the rollback",
                PackageManager.PERMISSION_DENIED, Porter.checkSelfPermission());
    }
}
