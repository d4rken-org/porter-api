package eu.darken.porter.sdk;

import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_COMPONENT;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_NO_CREATE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_REMOVE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TAG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** What the Shizuku wire puts on the wire for a user service, read back by the server's stub. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuProtocolWireUserServiceTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final ComponentName COMPONENT = new ComponentName(PACKAGE, CLASS);
    private static final String PROCESS_SUFFIX = "probe";
    /** The oldest server that is asked to drop a connection rather than kill the service. */
    private static final int GATED_PATCH_VERSION = 4;

    private final PorterWire.Callbacks callbacks = new PorterWire.Callbacks() {

        @Override
        public void onRequestPermissionResult(int requestCode, boolean allowed) {
        }

        @Override
        public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) {
        }
    };

    /** One user service binding as the SDK holds it, without the ServiceConnection plumbing. */
    private static final class RecordingCallback implements UserServiceCallback {

        private final Map<PorterBackend, IBinder> registered = new EnumMap<>(PorterBackend.class);

        IBinder connectedWith;
        int connects;
        int deaths;

        @Override
        public void connected(@NonNull IBinder binder) {
            connects++;
            connectedWith = binder;
        }

        @Override
        public void died() {
            deaths++;
        }

        @Nullable
        @Override
        public IBinder registeredBinder(@NonNull PorterBackend backend) {
            return registered.get(backend);
        }

        @Override
        public void rememberRegisteredBinder(@NonNull PorterBackend backend, @NonNull IBinder binder) {
            registered.put(backend, binder);
        }
    }

    /**
     * Holds its first lookup until the other thread reaches one too, so a wire that looks up and
     * registers without a lock creates two binders for the one callback.
     */
    private static final class GatedCallback implements UserServiceCallback {

        private final Map<PorterBackend, IBinder> registered = new ConcurrentHashMap<>();
        private final CountDownLatch lookups = new CountDownLatch(2);

        @Nullable
        @Override
        public IBinder registeredBinder(@NonNull PorterBackend backend) {
            lookups.countDown();
            try {
                lookups.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return registered.get(backend);
        }

        @Override
        public void rememberRegisteredBinder(@NonNull PorterBackend backend, @NonNull IBinder binder) {
            registered.put(backend, binder);
        }

        @Override
        public void connected(@NonNull IBinder binder) {
        }

        @Override
        public void died() {
        }
    }

    private static Porter.UserServiceArgs args(String tag) {
        return new Porter.UserServiceArgs(COMPONENT).processNameSuffix(PROCESS_SUFFIX).tag(tag);
    }

    /** A wire that has completed the handshake against a server reporting this version. */
    private ShizukuProtocolWire attached(FakeShizukuService fake, int version, int patch) {
        Bundle state = new Bundle();
        state.putInt(BIND_APPLICATION_SERVER_VERSION, version);
        state.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, patch);
        fake.bindApplicationReply = state;

        ShizukuProtocolWire wire = new ShizukuProtocolWire(fake, callbacks);
        wire.attach(PACKAGE);
        return wire;
    }

    @Test
    public void anAddCarriesTheEncodedArgumentsAndTheRegisteredBinder() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION);
        fake.addUserServiceResult = 7;
        RecordingCallback callback = new RecordingCallback();

        assertEquals(7, wire.addUserService(callback, args("added"), false));

        assertEquals(1, fake.userServiceAdds.size());
        FakeShizukuService.UserServiceCall call = fake.userServiceAdds.get(0);
        assertEquals(COMPONENT, call.args.getParcelable(USER_SERVICE_ARG_COMPONENT));
        assertEquals("added", call.args.getString(USER_SERVICE_ARG_TAG));
        assertNotNull(call.connection);
        assertSame(callback.registeredBinder(PorterBackend.SHIZUKU), call.connection);
    }

    @Test
    public void aPeekAsksForNoCreationAndABindDoesNot() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION);

        wire.addUserService(new RecordingCallback(), args("peeked"), true);
        wire.addUserService(new RecordingCallback(), args("bound"), false);

        assertTrue(fake.userServiceAdds.get(0).args.getBoolean(USER_SERVICE_ARG_NO_CREATE));
        assertFalse(fake.userServiceAdds.get(1).args.containsKey(USER_SERVICE_ARG_NO_CREATE));
    }

    @Test
    public void aRemovalCarriesTheBinderTheAddRegistered() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION);
        RecordingCallback callback = new RecordingCallback();

        wire.addUserService(callback, args("one-binding"), false);
        wire.removeUserService(callback, args("one-binding"), false);

        assertEquals(1, fake.userServiceRemoves.size());
        assertSame(fake.userServiceAdds.get(0).connection, fake.userServiceRemoves.get(0).connection);
    }

    @Test
    public void killingNamesNoConnection() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION);

        wire.removeUserService(null, args("killed"), true);

        assertEquals(1, fake.userServiceRemoves.size());
        assertNull(fake.userServiceRemoves.get(0).connection);
        assertTrue(fake.userServiceRemoves.get(0).args.getBoolean(USER_SERVICE_ARG_REMOVE));
    }

    @Test
    public void theServerPushesConnectedAndDiedToTheCallback() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION);
        RecordingCallback callback = new RecordingCallback();
        IBinder service = new Binder();

        wire.addUserService(callback, args("pushed-to"), false);
        fake.pushUserServiceConnected(service);
        fake.pushUserServiceDied();

        assertEquals(1, callback.connects);
        assertSame(service, callback.connectedWith);
        assertEquals(1, callback.deaths);
    }

    @Test
    public void twoThreadsBindingOneServiceRegisterOneBinder() throws Exception {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION);
        GatedCallback callback = new GatedCallback();
        Porter.UserServiceArgs args = args("concurrent-binding");

        Runnable bind = () -> wire.addUserService(callback, args, false);
        Thread first = new Thread(bind);
        Thread second = new Thread(bind);
        first.start();
        second.start();
        first.join();
        second.join();

        assertEquals(2, fake.userServiceAdds.size());
        assertNotNull(fake.userServiceAdds.get(0).connection);
        assertSame(fake.userServiceAdds.get(0).connection, fake.userServiceAdds.get(1).connection);
    }

    @Test
    public void aServerBelowThePatchGateIsNotAskedToDropAConnection() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION - 1);

        assertEquals(0, wire.removeUserService(new RecordingCallback(), args("kept"), false));

        assertTrue(fake.userServiceRemoves.isEmpty());
        assertFalse(fake.codes.contains(ShizukuProtocol.TRANSACTION_removeUserService));
    }

    @Test
    public void aServerAtThePatchGateIsAskedToDropAConnection() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION);
        fake.removeUserServiceResult = 3;

        assertEquals(3, wire.removeUserService(new RecordingCallback(), args("dropped"), false));

        assertEquals(1, fake.userServiceRemoves.size());
        assertFalse(fake.userServiceRemoves.get(0).args.getBoolean(USER_SERVICE_ARG_REMOVE));
    }

    @Test
    public void aLaterServerIsAskedToDropAConnection() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 14, 0);

        wire.removeUserService(new RecordingCallback(), args("dropped"), false);

        assertEquals(1, fake.userServiceRemoves.size());
    }

    @Test
    public void killingIsSentBelowThePatchGateToo() {
        FakeShizukuService fake = new FakeShizukuService();
        ShizukuProtocolWire wire = attached(fake, 13, GATED_PATCH_VERSION - 1);

        wire.removeUserService(null, args("killed"), true);

        assertEquals(1, fake.userServiceRemoves.size());
        assertTrue(fake.userServiceRemoves.get(0).args.getBoolean(USER_SERVICE_ARG_REMOVE));
    }
}
