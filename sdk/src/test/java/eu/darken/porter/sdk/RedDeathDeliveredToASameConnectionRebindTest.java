package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** A death overtaken by a rebind that reuses the same ServiceConnection instance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDeathDeliveredToASameConnectionRebindTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";

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
    public void aRebindOfTheSameConnectionDoesNotSwallowTheDeathItOvertook() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);
        Porter.UserServiceArgs args = args("death-then-same-connection-rebind");

        RecordingConnection conn = new RecordingConnection();
        Porter.bindUserService(args, conn);
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the caller was never connected", 1, conn.connects);

        connection.died();

        // The same caller rebinds with the instance it already registered, and the server accepts.
        Porter.bindUserService(args, conn);

        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the caller was never told the binder it was connected to died",
                1, conn.disconnects);

        PorterServiceConnection rebound = PorterServiceConnections.peek(args);
        assertNotNull("the rebind was left without a binding", rebound);
        assertNotSame("the rebind was handed back the binding the death retired",
                connection, rebound);

        rebound.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the rebound caller never heard about its new binding", 2, conn.connects);
    }
}
