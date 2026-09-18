package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

/** A kill is a request to the server, not a local teardown: the death recipient still delivers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedKillLeavesTheDeathToItsRecipientTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";

    /** Refuses the removals a test tells it to, as a server that rejected the kill would. */
    private static final class ThrowingService extends FakePorterService {

        RuntimeException removeFailure;

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

    private static Porter.UserServiceArgs args(String tag) {
        return new Porter.UserServiceArgs(new ComponentName(PACKAGE, CLASS))
                .processNameSuffix("probe")
                .tag(tag);
    }

    private static PorterServiceConnection boundAndConnected(
            Porter.UserServiceArgs args, RecordingConnection conn) {
        Porter.bindUserService(args, conn);
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the caller was never connected", 1, conn.connects);
        return connection;
    }

    @Test
    public void anAcceptedKillLeavesTheDisconnectToTheDeathRecipient() {
        ThrowingService fake = new ThrowingService();
        Porter.onBinderReceived(fake, PACKAGE);
        Porter.UserServiceArgs args = args("killed-then-died");
        RecordingConnection conn = new RecordingConnection();
        boundAndConnected(args, conn);

        Porter.unbindUserService(args, conn, true);

        assertNotNull(fake.userServiceArgs);
        assertTrue("the kill never reached the server", fake.userServiceArgs.getBoolean(USER_SERVICE_REMOVE));

        PorterServiceConnection afterKill = PorterServiceConnections.peek(args);
        assertNotNull("the kill tore down the registration the death still has to reach", afterKill);

        // The killed process exits and its binder dies, which is what actually ends the binding.
        afterKill.died();
        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the caller was never told the killed service died", 1, conn.disconnects);
    }

    @Test
    public void aRefusedKillLeavesTheStillRunningServiceBound() {
        ThrowingService fake = new ThrowingService();
        Porter.onBinderReceived(fake, PACKAGE);
        Porter.UserServiceArgs args = args("refused-kill");
        RecordingConnection conn = new RecordingConnection();
        PorterServiceConnection connection = boundAndConnected(args, conn);

        fake.removeFailure = new RuntimeException("the server refused");
        assertThrows(RuntimeException.class, () -> Porter.unbindUserService(args, conn, true));

        PorterServiceConnection afterRefusal = PorterServiceConnections.peek(args);
        assertNotNull("a refused kill dropped the registrations of a service that is still running",
                afterRefusal);
        assertEquals("a refused kill disconnected a caller whose service is still running",
                0, conn.disconnects);

        // Still the same live binding, so the server's next push still reaches the caller.
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the caller of a refused kill stopped hearing from its live service",
                2, conn.connects);
    }
}
