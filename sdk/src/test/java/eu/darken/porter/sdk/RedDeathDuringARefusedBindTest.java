package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

/** A death that arrives while a second caller's bind is still outstanding, and that bind is refused. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDeathDuringARefusedBindTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";

    /** Runs a snippet from inside the bind call, then refuses that same call. */
    private static final class DyingService extends FakePorterService {

        Runnable duringAdd;
        RuntimeException addFailure;

        @Override
        public int addUserService(IPorterServiceConnection conn, Bundle args) {
            Runnable snippet = duringAdd;
            duringAdd = null;
            RuntimeException failure = addFailure;
            addFailure = null;
            if (snippet != null) snippet.run();
            if (failure != null) throw failure;
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
    public void aDeathDuringARefusedBindStillReachesTheLiveCaller() {
        DyingService fake = new DyingService();
        Porter.onBinderReceived(fake, PACKAGE);
        Porter.UserServiceArgs args = args("death-during-refused-bind");

        RecordingConnection live = new RecordingConnection();
        Porter.bindUserService(args, live);
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the live caller was never connected", 1, live.connects);

        // The service dies while the second caller's bind is still outstanding, and that bind is
        // then refused, so its rollback must not take the death down with it.
        RecordingConnection refused = new RecordingConnection();
        fake.duringAdd = connection::died;
        fake.addFailure = new RuntimeException("the server refused");
        assertThrows(RuntimeException.class, () -> Porter.bindUserService(args, refused));

        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the live caller was never told its service died", 1, live.disconnects);
        assertNull("the dead binding was left in the cache for the next bind to reuse",
                PorterServiceConnections.peek(args));
    }
}
