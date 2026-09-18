package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_API_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_PACKAGE_NAME;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_PERMISSION_GRANTED;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_SECONTEXT;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_UID;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_ALLOWED;
import static eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_IS_ONETIME;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TOKEN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;

/** What the Shizuku wire puts on the wire, read back by the stubs the real server is built from. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuProtocolWireTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String TOKEN = "user-service-token";
    private static final String SECONTEXT = "u:r:shell:s0";
    private static final int SERVER_UID = 2000;
    private static final int PATCH_VERSION = 6;
    private static final int REQUEST_CODE = 11;
    private static final int TARGET_UID = 1010200;
    private static final int TARGET_PID = 45678;

    private final RecordingCallbacks callbacks = new RecordingCallbacks();

    private static final class RecordingCallbacks implements PorterWire.Callbacks {

        int results;
        int requestCode = -1;
        boolean allowed;

        @Override
        public void onRequestPermissionResult(int requestCode, boolean allowed) {
            results++;
            this.requestCode = requestCode;
            this.allowed = allowed;
        }

        @Override
        public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) {
        }
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private static void assertCodes(FakeShizukuService fake, Integer... expected) {
        assertEquals(Arrays.asList(expected), fake.codes);
    }

    @Test
    public void attachSendsTheApiVersionAndPackageName() {
        FakeShizukuService fake = new FakeShizukuService();

        new ShizukuProtocolWire(fake, callbacks).attach(PACKAGE);

        assertCodes(fake, 18);
        assertNotNull(fake.attachArgs);
        assertEquals(13, fake.attachArgs.getInt(ATTACH_APPLICATION_API_VERSION));
        assertEquals(PACKAGE, fake.attachArgs.getString(ATTACH_APPLICATION_PACKAGE_NAME));
    }

    @Test
    public void attachReadsEveryFieldOfTheBindApplicationReply() {
        FakeShizukuService fake = new FakeShizukuService();
        Bundle state = new Bundle();
        state.putInt(BIND_APPLICATION_SERVER_UID, SERVER_UID);
        state.putInt(BIND_APPLICATION_SERVER_VERSION, 13);
        state.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, PATCH_VERSION);
        state.putString(BIND_APPLICATION_SERVER_SECONTEXT, SECONTEXT);
        state.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, true);
        state.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, true);
        fake.bindApplicationReply = state;

        PorterWire.AttachReply reply = new ShizukuProtocolWire(fake, callbacks).attach(PACKAGE);

        assertNotNull(reply);
        assertEquals(SERVER_UID, reply.serverUid);
        assertEquals(13, reply.protocolVersion);
        assertEquals(Integer.valueOf(PATCH_VERSION), reply.patchVersion);
        assertEquals(SECONTEXT, reply.seLinuxContext);
        assertEquals(CAPABILITIES_NONE, reply.capabilities);
        assertTrue(reply.permissionGranted);
        assertTrue(reply.shouldShowRequestPermissionRationale);
    }

    @Test
    public void aServerBelowTheFloorIsRefused() {
        FakeShizukuService fake = new FakeShizukuService();
        fake.bindApplicationReply = FakeShizukuService.replyWithVersion(12);
        ShizukuProtocolWire wire = new ShizukuProtocolWire(fake, callbacks);

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> wire.attach(PACKAGE));

        assertTrue(thrown.getMessage().contains("12"));
        assertTrue(thrown.getMessage().contains("13"));
    }

    @Test
    public void aServerThatNeverAnswersAttachTimesOut() {
        FakeShizukuService fake = new FakeShizukuService();
        fake.suppressBindApplication = true;
        ShizukuProtocolWire wire = new ShizukuProtocolWire(fake, callbacks, 50);

        assertThrows(IllegalStateException.class, () -> wire.attach(PACKAGE));
    }

    /**
     * The production path: on a device the reply is dispatched to another binder thread, so it can
     * land after the wait has begun. A local transact answers on the calling thread, which leaves
     * the latch at zero before every other test here waits on it.
     */
    @Test
    public void aReplyArrivingFromAnotherThreadCompletesTheAttach() {
        FakeShizukuService fake = new FakeShizukuService();
        fake.deferBindApplicationMs = 50;

        PorterWire.AttachReply reply =
                new ShizukuProtocolWire(fake, callbacks, 5000).attach(PACKAGE);

        assertNotNull(reply);
        assertEquals(13, reply.protocolVersion);
    }

    @Test
    public void aResentBindApplicationDoesNotDisturbACompletedAttach() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = new ShizukuProtocolWire(fake, callbacks);
        PorterWire.AttachReply first = wire.attach(PACKAGE);

        Bundle later = FakeShizukuService.replyWithVersion(99);
        later.putInt(BIND_APPLICATION_SERVER_UID, 1);
        fake.pushBindApplication(later);

        // Attaching again decodes the state the wire kept, which is the one the handshake used.
        PorterWire.AttachReply again = wire.attach(PACKAGE);
        assertNotNull(first);
        assertNotNull(again);
        assertEquals(first.protocolVersion, again.protocolVersion);
        assertEquals(first.serverUid, again.serverUid);
    }

    @Test
    public void forwardWritesTheShizukuEnvelope() {
        FakeShizukuService fake = new FakeShizukuService();
        IBinder target = new Binder();

        Parcel data = Parcel.obtain();
        try {
            data.writeInt(20816);
            new ShizukuProtocolWire(fake, callbacks).forward(target, 7, data, null, 42);
        } finally {
            data.recycle();
        }

        assertCodes(fake, 1);
        assertSame(target, fake.forwardedTarget);
        assertEquals(7, fake.forwardedCode);
        assertEquals(42, fake.forwardedFlags);
        assertEquals(20816, fake.forwardedPayload);
    }

    @Test
    public void aPermissionResultReachesTheCallbacks() {
        FakeShizukuService fake = new FakeShizukuService();
        new ShizukuProtocolWire(fake, callbacks).attach(PACKAGE);

        Bundle result = new Bundle();
        result.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, true);
        fake.pushRequestPermissionResult(REQUEST_CODE, result);

        assertEquals(1, callbacks.results);
        assertEquals(REQUEST_CODE, callbacks.requestCode);
        assertTrue(callbacks.allowed);
    }

    @Test
    public void theMechanicalCallsUseTheDocumentedCodes() {
        FakeShizukuService fake = new FakeShizukuService();
        fake.uid = SERVER_UID;
        fake.seLinuxContext = SECONTEXT;
        fake.remotePermission = PackageManager.PERMISSION_GRANTED;
        fake.systemProperty = "answered";
        fake.selfPermission = true;
        fake.rationale = true;
        fake.flags = 6;
        ShizukuProtocolWire wire = new ShizukuProtocolWire(fake, callbacks);

        assertEquals(SERVER_UID, wire.getUid());
        assertEquals(SECONTEXT, wire.getSELinuxContext());
        assertEquals(PackageManager.PERMISSION_GRANTED, wire.checkPermission("android.permission.DUMP"));
        assertEquals("answered", wire.getSystemProperty("ro.asked", "fallback"));
        wire.setSystemProperty("ro.written", "value");
        wire.requestPermission(REQUEST_CODE);
        assertTrue(wire.checkSelfPermission());
        assertTrue(wire.shouldShowRequestPermissionRationale());
        wire.exit();
        assertEquals(6, wire.getFlagsForUid(TARGET_UID, 2));
        wire.updateFlagsForUid(TARGET_UID, 2, 2);

        assertCodes(fake, 4, 9, 5, 10, 11, 15, 16, 17, 101, 106, 107);
        assertEquals("android.permission.DUMP", fake.checkedPermission);
        assertEquals("ro.asked", fake.queriedPropertyName);
        assertEquals("fallback", fake.queriedPropertyDefault);
        assertEquals("ro.written", fake.setPropertyName);
        assertEquals("value", fake.setPropertyValue);
        assertEquals(REQUEST_CODE, fake.requestedPermissionCode);
        assertEquals(1, fake.exitCalls);
        assertEquals(TARGET_UID, fake.flagsUid);
        assertEquals(2, fake.flagsMask);
        assertEquals(2, fake.flagsValue);
    }

    @Test
    public void attachUserServiceCarriesTheTokenBundle() {
        FakeShizukuService fake = new FakeShizukuService();
        IBinder host = new Binder();

        new ShizukuProtocolWire(fake, callbacks).attachUserService(host, TOKEN);

        assertCodes(fake, 102);
        assertSame(host, fake.attachedUserServiceBinder);
        assertNotNull(fake.attachedUserServiceOptions);
        assertEquals(TOKEN, fake.attachedUserServiceOptions.getString(USER_SERVICE_ARG_TOKEN));
    }

    @Test
    public void permissionConfirmationIsOneWay() {
        FakeShizukuService fake = new FakeShizukuService();

        new ShizukuProtocolWire(fake, callbacks)
                .dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, true, false);

        assertCodes(fake, 105);
        assertEquals(IBinder.FLAG_ONEWAY, fake.lastTransactFlags);
        assertFalse(fake.lastTransactExpectedReply);
        assertEquals(TARGET_UID, fake.confirmationUid);
        assertEquals(TARGET_PID, fake.confirmationPid);
        assertEquals(REQUEST_CODE, fake.confirmationRequestCode);
        assertNotNull(fake.confirmationData);
        assertTrue(fake.confirmationData.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED));
        assertFalse(fake.confirmationData.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME));
    }
}
