package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import eu.darken.porter.server.IPorterServiceConnection;

/** What a failed or finished user service call leaves behind in the caller's own registrations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterUserServiceFacadeTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";

    /** Fails the user service calls a test asks it to, as a server that refused them would. */
    private static final class ThrowingService extends FakePorterService {

        RuntimeException addFailure;
        RuntimeException removeFailure;

        @Override
        public int addUserService(IPorterServiceConnection conn, Bundle args) {
            if (addFailure != null) throw addFailure;
            return super.addUserService(conn, args);
        }

        @Override
        public int removeUserService(IPorterServiceConnection conn, Bundle args) {
            if (removeFailure != null) throw removeFailure;
            return super.removeUserService(conn, args);
        }
    }

    private static final class RecordingConnection implements ServiceConnection {

        int connects;
        int disconnects;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            connects++;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            disconnects++;
        }
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private static ThrowingService attached() {
        ThrowingService fake = new ThrowingService();
        Porter.onBinderReceived(fake, PACKAGE);
        return fake;
    }

    private static Porter.UserServiceArgs args(String tag) {
        return new Porter.UserServiceArgs(new ComponentName(PACKAGE, CLASS))
                .processNameSuffix("probe")
                .tag(tag);
    }

    /** What the server would push once the binding exists, delivered to whoever is registered. */
    private static void pushConnected(Porter.UserServiceArgs args) {
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
    }

    @Test
    public void aFailedBindLeavesTheCallerUnregisteredAndRethrows() {
        ThrowingService fake = attached();
        Porter.UserServiceArgs args = args("failed-bind");
        RecordingConnection conn = new RecordingConnection();
        RuntimeException failure = new RuntimeException("the server refused");
        fake.addFailure = failure;

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> Porter.bindUserService(args, conn));

        assertSame(failure, thrown);
        pushConnected(args);
        assertEquals(0, conn.connects);
    }

    @Test
    public void aBindWithNoConnectionRollsBackTheRegistrationItMade() {
        Porter.UserServiceArgs args = args("no-session-bind");
        RecordingConnection refused = new RecordingConnection();

        assertThrows(IllegalStateException.class, () -> Porter.bindUserService(args, refused));

        attached();
        RecordingConnection accepted = new RecordingConnection();
        Porter.bindUserService(args, accepted);

        pushConnected(args);
        assertEquals(0, refused.connects);
        assertEquals(1, accepted.connects);
    }

    @Test
    public void aFailedSecondBindOfOneConnectionLeavesTheFirstRegistrationLive() {
        ThrowingService fake = attached();
        Porter.UserServiceArgs args = args("rebound");
        RecordingConnection conn = new RecordingConnection();
        Porter.bindUserService(args, conn);

        fake.addFailure = new RuntimeException("the server refused");
        assertThrows(RuntimeException.class, () -> Porter.bindUserService(args, conn));
        fake.addFailure = null;

        pushConnected(args);
        assertEquals(1, conn.connects);
    }

    @Test
    public void aFailedUnbindStillClearsTheLocalState() {
        ThrowingService fake = attached();
        Porter.UserServiceArgs args = args("failed-unbind");
        RecordingConnection conn = new RecordingConnection();
        Porter.bindUserService(args, conn);

        fake.removeFailure = new RuntimeException("the server refused");
        assertThrows(RuntimeException.class, () -> Porter.unbindUserService(args, conn, false));

        assertNull(PorterServiceConnections.peek(args));
    }

    @Test
    public void killingTheServiceLeavesTheDisconnectToTheDeathRecipient() {
        ThrowingService fake = attached();
        Porter.UserServiceArgs args = args("killed");
        RecordingConnection conn = new RecordingConnection();
        Porter.bindUserService(args, conn);

        Porter.unbindUserService(args, conn, true);

        assertNotNull(PorterServiceConnections.peek(args));
        assertNotNull(fake.userServiceArgs);
        assertTrue(fake.userServiceArgs.getBoolean(USER_SERVICE_REMOVE));
    }

    @Test
    public void aDeathIssuedBeforeARebindDisconnectsOnlyTheCallerItWasBoundTo() {
        attached();
        Porter.UserServiceArgs args = args("death-then-rebind");
        RecordingConnection first = new RecordingConnection();
        Porter.bindUserService(args, first);
        pushConnected(args);
        assertEquals(1, first.connects);

        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.died();
        RecordingConnection second = new RecordingConnection();
        Porter.bindUserService(args, second);
        ShadowLooper.shadowMainLooper().idle();

        assertEquals(1, first.disconnects);
        assertEquals(0, second.disconnects);
        assertNotNull(PorterServiceConnections.peek(args));
        assertNotSame(connection, PorterServiceConnections.peek(args));
    }
}
