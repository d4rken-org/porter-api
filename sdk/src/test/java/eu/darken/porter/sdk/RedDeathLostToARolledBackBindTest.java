package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

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

/** What a bind the server refuses does to a death another caller is already owed. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDeathLostToARolledBackBindTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";

    /** Refuses the binds a test tells it to, as a server that rejected them would. */
    private static final class ThrowingService extends FakePorterService {

        RuntimeException addFailure;

        @Override
        public int addUserService(IPorterServiceConnection conn, Bundle args) {
            if (addFailure != null) throw addFailure;
            return super.addUserService(conn, args);
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

    @Test
    public void aRefusedBindDoesNotSwallowAnotherCallersDeath() {
        ThrowingService fake = new ThrowingService();
        Porter.onBinderReceived(fake, PACKAGE);
        Porter.UserServiceArgs args = args("death-then-refused-bind");

        RecordingConnection first = new RecordingConnection();
        Porter.bindUserService(args, first);
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the first caller was never connected", 1, first.connects);

        connection.died();

        // A second caller's bind reaches the server and is refused, so it never becomes a binding.
        RecordingConnection refused = new RecordingConnection();
        fake.addFailure = new RuntimeException("the server refused");
        assertThrows(RuntimeException.class, () -> Porter.bindUserService(args, refused));

        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the live caller was never told the service died", 1, first.disconnects);
        assertEquals("the refused caller was told about a binding it never had", 0, refused.disconnects);
    }
}
