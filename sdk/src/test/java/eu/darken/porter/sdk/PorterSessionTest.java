package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;

import java.util.List;

import eu.darken.porter.server.IPorterApplication;

/** What a connection that has been replaced or has died may still do to the one that is current. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterSessionTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private static IBinder.DeathRecipient deathRecipientOf(FakePorterService fake) {
        ShadowBinder shadow = Shadow.extract(fake);
        List<IBinder.DeathRecipient> recipients = shadow.getDeathRecipients();
        assertEquals("one connection links one recipient", 1, recipients.size());
        return recipients.get(0);
    }

    private static Bundle permissionState(boolean granted, boolean shouldShowRationale) {
        Bundle state = new Bundle();
        state.putBoolean(REPLY_PERMISSION_GRANTED, granted);
        state.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale);
        return state;
    }

    @Test
    public void aDeathNotificationForASupersededConnectionLeavesTheCurrentOne() {
        FakePorterService first = new FakePorterService();
        Porter.onBinderReceived(first, PACKAGE);
        IBinder.DeathRecipient firstDeath = deathRecipientOf(first);
        boolean[] dead = {false};
        Porter.addBinderDeadListener(() -> dead[0] = true);

        FakePorterService second = new FakePorterService();
        Porter.onBinderReceived(second, PACKAGE);
        firstDeath.binderDied();

        assertTrue(Porter.pingBinder());
        assertSame(second, Porter.getBinder());
        assertFalse(dead[0]);
    }

    @Test
    public void aDeathNotificationForTheCurrentConnectionTearsItDownOnce() {
        FakePorterService fake = new FakePorterService();
        Porter.onBinderReceived(fake, PACKAGE);
        IBinder.DeathRecipient death = deathRecipientOf(fake);
        int[] dead = {0};
        Porter.addBinderDeadListener(() -> dead[0]++);

        death.binderDied();
        death.binderDied();

        assertFalse(Porter.pingBinder());
        assertNull(Porter.getBinder());
        assertEquals(1, dead[0]);
    }

    @Test
    public void anAttachThatFailsLeavesTheConnectionItCouldNotReplace() {
        FakePorterService serving = new FakePorterService();
        serving.attachReply = permissionState(true, false);
        Porter.onBinderReceived(serving, PACKAGE);

        FakePorterService refused = new FakePorterService();
        refused.attachFailure = new SecurityException("not an attached client");
        Porter.onBinderReceived(refused, PACKAGE);

        assertTrue(Porter.pingBinder());
        assertSame(serving, Porter.getBinder());
        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());
    }

    @Test
    public void aSupersededConnectionCannotChangeThePermissionState() throws Exception {
        FakePorterService first = new FakePorterService();
        Porter.onBinderReceived(first, PACKAGE);
        IPorterApplication firstApplication = first.application;

        FakePorterService second = new FakePorterService();
        second.attachReply = permissionState(true, false);
        Porter.onBinderReceived(second, PACKAGE);
        firstApplication.dispatchPermissionStateChanged(permissionState(false, true));

        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());
        assertFalse(Porter.shouldShowRequestPermissionRationale());
        assertEquals("the current connection was never asked", 0, second.selfPermissionQueries);
    }

    @Test
    public void aSupersededConnectionCannotDeliverAPermissionResult() throws Exception {
        FakePorterService first = new FakePorterService();
        Porter.onBinderReceived(first, PACKAGE);
        IPorterApplication firstApplication = first.application;
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);
        int[] results = {0};
        Porter.addRequestPermissionResultListener((requestCode, grantResult) -> results[0]++);

        Bundle data = new Bundle();
        data.putBoolean(PERMISSION_RESULT_ALLOWED, true);
        firstApplication.dispatchRequestPermissionResult(7, data);

        assertEquals(0, results[0]);
    }
}
