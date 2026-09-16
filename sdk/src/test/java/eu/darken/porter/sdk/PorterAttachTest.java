package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
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

import eu.darken.porter.protocol.PorterProtocol;

/** What {@code onBinderReceived} sends, what it believes of the reply and what it forgets. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterAttachTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CONTEXT = "u:r:shell:s0";
    private static final int SERVER_UID = 2000;

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private static Bundle fullReply() {
        Bundle reply = new Bundle();
        reply.putInt(REPLY_PROTOCOL_VERSION, 1);
        reply.putInt(REPLY_SERVER_UID, SERVER_UID);
        reply.putString(REPLY_SERVER_SECONTEXT, CONTEXT);
        reply.putBoolean(REPLY_PERMISSION_GRANTED, true);
        reply.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        reply.putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE);
        return reply;
    }

    private static FakePorterService attached() {
        FakePorterService fake = new FakePorterService();
        fake.attachReply = fullReply();
        Porter.onBinderReceived(fake, PACKAGE);
        return fake;
    }

    @Test
    public void attachSendsThePackageAndVersionAndReadsTheReply() {
        FakePorterService fake = attached();

        assertEquals(PACKAGE, fake.attachArgs.getString(ATTACH_PACKAGE_NAME));
        assertEquals(PorterProtocol.VERSION, fake.attachArgs.getInt(ATTACH_PROTOCOL_VERSION));

        assertTrue(Porter.pingBinder());
        assertEquals(SERVER_UID, Porter.getUid());
        assertEquals(1, Porter.getServerProtocolVersion());
        assertEquals(CAPABILITIES_NONE, Porter.getServerCapabilities());
        assertEquals(CONTEXT, Porter.getSELinuxContext());
        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());
    }

    @Test
    public void aSparseReplyLeavesNoStaleServerState() {
        attached();

        FakePorterService sparse = new FakePorterService();
        Porter.onBinderReceived(sparse, PACKAGE);

        assertEquals(0, Porter.getServerProtocolVersion());
        assertEquals(0L, Porter.getServerCapabilities());
        assertEquals(-1, Porter.getUid());
        assertNull(Porter.getSELinuxContext());
        assertEquals(PackageManager.PERMISSION_DENIED, Porter.checkSelfPermission());
        assertEquals("the grant was asked for, not remembered", 1, sparse.selfPermissionQueries);
    }

    @Test
    public void theReceivedListenersFireAndAStickyOneCatchesUp() {
        boolean[] fired = {false, false};
        Porter.addBinderReceivedListener(() -> fired[0] = true);

        attached();

        assertTrue(fired[0]);

        Porter.addBinderReceivedListenerSticky(() -> fired[1] = true);

        assertTrue(fired[1]);
    }

    @Test
    public void anAttachThatIsRefusedLeavesNoConnection() {
        FakePorterService fake = new FakePorterService();
        fake.attachFailure = new SecurityException("not an attached client");
        boolean[] fired = {false};
        Porter.addBinderReceivedListener(() -> fired[0] = true);

        Porter.onBinderReceived(fake, PACKAGE);

        assertFalse(Porter.pingBinder());
        assertThrows(IllegalStateException.class, Porter::getUid);
        assertFalse(fired[0]);
    }

    @Test
    public void redeliveringTheSameBinderKeepsTheGrantAndANewOneDropsIt() {
        FakePorterService fake = attached();
        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());

        Porter.onBinderReceived(fake, PACKAGE);

        assertEquals(1, fake.attachCount);
        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());

        Porter.onBinderReceived(new FakePorterService(), PACKAGE);

        assertEquals(PackageManager.PERMISSION_DENIED, Porter.checkSelfPermission());
    }

    @Test
    public void binderDeathFiresTheDeadListenersAndDropsTheGrant() {
        boolean[] dead = {false};
        Porter.addBinderDeadListener(() -> dead[0] = true);
        FakePorterService fake = attached();

        ShadowBinder shadow = Shadow.extract(fake);
        List<IBinder.DeathRecipient> recipients = shadow.getDeathRecipients();
        assertEquals(1, recipients.size());
        recipients.get(0).binderDied();

        assertTrue(dead[0]);
        assertFalse(Porter.pingBinder());
        assertThrows(IllegalStateException.class, Porter::checkSelfPermission);
    }

    @Test
    public void aPermissionResultReachesTheListener() throws Exception {
        FakePorterService fake = attached();
        int[] seen = {-1, -1};
        Porter.addRequestPermissionResultListener((requestCode, grantResult) -> {
            seen[0] = requestCode;
            seen[1] = grantResult;
        });

        Bundle data = new Bundle();
        data.putBoolean(PERMISSION_RESULT_ALLOWED, true);
        fake.application.dispatchRequestPermissionResult(7, data);

        assertArrayEquals(new int[]{7, PackageManager.PERMISSION_GRANTED}, seen);
    }

    private static Bundle permissionState(boolean granted, boolean shouldShowRationale) {
        Bundle state = new Bundle();
        state.putBoolean(REPLY_PERMISSION_GRANTED, granted);
        state.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale);
        return state;
    }

    @Test
    public void aPausedGrantReplacesTheCachedAnswer() throws Exception {
        FakePorterService fake = attached();
        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());

        fake.application.dispatchPermissionStateChanged(permissionState(false, true));

        assertEquals(PackageManager.PERMISSION_DENIED, Porter.checkSelfPermission());
        assertTrue(Porter.shouldShowRequestPermissionRationale());
    }

    @Test
    public void aPauseArrivingDuringAttachOutlivesTheReply() {
        FakePorterService fake = new FakePorterService();
        fake.attachReply = fullReply();
        fake.attachTimePermissionPush = permissionState(false, true);

        Porter.onBinderReceived(fake, PACKAGE);

        assertEquals(PackageManager.PERMISSION_DENIED, Porter.checkSelfPermission());
        assertTrue(Porter.shouldShowRequestPermissionRationale());
        assertEquals("only the permission keys lose to a push", SERVER_UID, Porter.getUid());
    }

    @Test
    public void aGrantArrivingDuringACheckOutlivesTheAnswer() {
        FakePorterService fake = new FakePorterService();
        Porter.onBinderReceived(fake, PACKAGE);
        fake.selfPermission = false;
        fake.checkTimePermissionPush = permissionState(true, false);

        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());
    }

    @Test
    public void aResumedGrantComesBackThroughTheSameChannel() throws Exception {
        FakePorterService fake = attached();
        fake.application.dispatchPermissionStateChanged(permissionState(false, true));
        assertEquals(PackageManager.PERMISSION_DENIED, Porter.checkSelfPermission());

        fake.application.dispatchPermissionStateChanged(permissionState(true, false));

        assertEquals(PackageManager.PERMISSION_GRANTED, Porter.checkSelfPermission());
        assertFalse(Porter.shouldShowRequestPermissionRationale());
    }
}
