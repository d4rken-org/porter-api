package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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

/** What a refused bind does to a third caller that rebound successfully while it was outstanding. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDeathRewoundByARefusedBindTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";

    /**
     * Runs a snippet from inside one bind call and then refuses that same call. Both are cleared
     * before the snippet runs, so a bind the snippet itself makes is accepted untouched.
     */
    private static final class ScriptedService extends FakePorterService {

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
    public void aRefusedBindDoesNotRewindAThirdCallersSuccessfulRebind() {
        ScriptedService fake = new ScriptedService();
        Porter.onBinderReceived(fake, PACKAGE);
        Porter.UserServiceArgs args = args("rebind-rewound-by-refused-bind");

        RecordingConnection first = new RecordingConnection();
        RecordingConnection rebinder = new RecordingConnection();
        Porter.bindUserService(args, first);
        Porter.bindUserService(args, rebinder);
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the first caller was never connected", 1, first.connects);
        assertEquals("the rebinding caller was never connected", 1, rebinder.connects);

        connection.died();

        // A third caller's bind is outstanding when the rebinder rebinds with the very instance it
        // already registered. That rebind is accepted; only the outstanding bind is refused.
        RecordingConnection refused = new RecordingConnection();
        fake.duringAdd = () -> Porter.bindUserService(args, rebinder);
        fake.addFailure = new RuntimeException("the server refused");
        assertThrows(RuntimeException.class, () -> Porter.bindUserService(args, refused));

        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the caller registered at the death was never told about it", 1, first.disconnects);
        assertEquals("the refused caller was told about a binding it never had", 0, refused.disconnects);

        PorterServiceConnection rebound = PorterServiceConnections.peek(args);
        assertNotNull("the refused bind's rollback dropped the successful rebind's binding", rebound);
        assertNotSame("the rebind was handed back the binding the death retired", connection, rebound);

        rebound.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the rebound caller never heard about its new binding", 2, rebinder.connects);
    }
}
