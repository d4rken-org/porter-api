package eu.darken.porter.sdk;

import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_PERMISSION_GRANTED;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The Shizuku wire has no permission-state callback of its own, so a server announces a change by
 * re-sending the state it attached with. What that resend reports, and what it must leave alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuProtocolWirePermissionStateTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    /** The oldest server that is asked to drop a connection rather than kill the service. */
    private static final int GATED_PATCH_VERSION = 4;

    private final RecordingCallbacks callbacks = new RecordingCallbacks();

    private static final class RecordingCallbacks implements PorterWire.Callbacks {

        int states;
        boolean granted;
        boolean rationale;

        @Override
        public void onRequestPermissionResult(int requestCode, boolean allowed) {
        }

        @Override
        public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) {
            states++;
            this.granted = granted;
            this.rationale = shouldShowRationale;
        }
    }

    private static Bundle permissionState(boolean granted, boolean rationale) {
        Bundle state = new Bundle();
        state.putInt(BIND_APPLICATION_SERVER_VERSION, ShizukuProtocol.MINIMUM_VERSION);
        state.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, granted);
        state.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, rationale);
        return state;
    }

    /** A wire that has completed the handshake against a server sending {@code state}. */
    private ShizukuProtocolWire attached(FakeShizukuService fake, Bundle state) {
        fake.bindApplicationReply = state;
        ShizukuProtocolWire wire = new ShizukuProtocolWire(fake, callbacks);
        wire.attach(PACKAGE);
        return wire;
    }

    private static Porter.UserServiceArgs args(String tag) {
        return new Porter.UserServiceArgs(new ComponentName(PACKAGE, CLASS))
                .processNameSuffix("probe")
                .tag(tag);
    }

    @Test
    public void aResendCarryingAGrantReportsIt() {
        FakeShizukuService fake = new FakeShizukuService();
        attached(fake, permissionState(false, true));

        fake.pushBindApplication(permissionState(true, false));

        assertEquals(1, callbacks.states);
        assertTrue(callbacks.granted);
        assertFalse(callbacks.rationale);
    }

    @Test
    public void aResendCarryingARevocationReportsIt() {
        FakeShizukuService fake = new FakeShizukuService();
        attached(fake, permissionState(true, false));

        fake.pushBindApplication(permissionState(false, true));

        assertEquals(1, callbacks.states);
        assertFalse(callbacks.granted);
        assertTrue(callbacks.rationale);
    }

    @Test
    public void aResendWithNoStateAtAllReportsNeitherGrantNorRationale() {
        FakeShizukuService fake = new FakeShizukuService();
        attached(fake, permissionState(true, true));

        fake.pushBindApplication(null);

        assertEquals(1, callbacks.states);
        assertFalse(callbacks.granted);
        assertFalse(callbacks.rationale);
    }

    /**
     * The handshake state is what the unbind gate reads its version and patch level from, and the
     * gate is driven either side of a resend that would open it if the resend were taken.
     */
    @Test
    public void aResendDoesNotMoveTheVersionsTheUnbindGateReads() {
        FakeShizukuService fake = new FakeShizukuService();
        Bundle state = permissionState(false, false);
        state.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, GATED_PATCH_VERSION - 1);
        ShizukuProtocolWire wire = attached(fake, state);

        assertEquals(0, wire.removeUserService(null, args("kept"), false));
        assertTrue(fake.userServiceRemoves.isEmpty());

        Bundle resend = permissionState(true, false);
        resend.putInt(BIND_APPLICATION_SERVER_VERSION, 14);
        resend.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, GATED_PATCH_VERSION);
        fake.pushBindApplication(resend);

        assertEquals(1, callbacks.states);
        assertEquals(0, wire.removeUserService(null, args("still kept"), false));
        assertTrue(fake.userServiceRemoves.isEmpty());
    }

    @Test
    public void theFirstPushCompletesTheHandshakeAndReportsNothingThroughThisPath() {
        FakeShizukuService fake = new FakeShizukuService();
        fake.bindApplicationReply = permissionState(true, true);

        PorterWire.AttachReply reply = new ShizukuProtocolWire(fake, callbacks).attach(PACKAGE);

        assertNotNull(reply);
        assertTrue(reply.permissionGranted);
        assertTrue(reply.shouldShowRequestPermissionRationale);
        assertEquals(0, callbacks.states);
    }
}
