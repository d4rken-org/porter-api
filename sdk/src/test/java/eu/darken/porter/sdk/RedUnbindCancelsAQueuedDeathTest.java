package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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

/**
 * An explicit unbind cancels the death that is already queued for the caller doing the unbinding:
 * a caller that has said it no longer wants the binding is not told the binding ended.
 *
 * <p>The cancellation is scoped to what the unbind names. A death queued under one tag survives an
 * unbind of another, and a rebind is not an unbind, so it still receives the death it overtook.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedUnbindCancelsAQueuedDeathTest {

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

    private static PorterServiceConnection boundAndConnected(
            Porter.UserServiceArgs args, RecordingConnection conn) {
        Porter.bindUserService(args, conn);
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull("the bind left no binding to connect", connection);
        connection.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the caller was never connected", 1, conn.connects);
        return connection;
    }

    @Test
    public void anUnbindCancelsTheDeathQueuedForTheCallerThatUnbound() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);
        Porter.UserServiceArgs args = args("death-then-unbind");
        RecordingConnection conn = new RecordingConnection();
        PorterServiceConnection connection = boundAndConnected(args, conn);

        // The service dies. The delivery is queued on the main looper and has not run yet.
        connection.died();
        assertEquals("the death was delivered before the main looper ran it",
                0, conn.disconnects);

        // The app unbinds before that delivery runs: it no longer wants this binding at all.
        Porter.unbindUserService(args, conn, false);

        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the caller received onServiceDisconnected after it had unbound",
                0, conn.disconnects);
    }

    @Test
    public void anUnbindOfAnotherTagLeavesTheQueuedDeathAlone() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);
        Porter.UserServiceArgs dying = args("death-scoped-dying");
        Porter.UserServiceArgs other = args("death-scoped-other");

        RecordingConnection dyingConn = new RecordingConnection();
        RecordingConnection otherConn = new RecordingConnection();
        PorterServiceConnection dyingConnection = boundAndConnected(dying, dyingConn);
        boundAndConnected(other, otherConn);

        dyingConnection.died();

        // An unbind of a different service must not cancel the death queued for this one.
        Porter.unbindUserService(other, otherConn, false);

        ShadowLooper.shadowMainLooper().idle();

        assertEquals("an unbind of another tag swallowed the death of the tag that died",
                1, dyingConn.disconnects);
        assertEquals("the caller that unbound was told its own live service disconnected",
                0, otherConn.disconnects);
    }

    @Test
    public void aRebindWithoutAnUnbindStillReceivesTheDeathItOvertook() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);
        Porter.UserServiceArgs args = args("death-then-rebind-no-unbind");
        RecordingConnection conn = new RecordingConnection();
        PorterServiceConnection connection = boundAndConnected(args, conn);

        connection.died();

        // A rebind is not an unbind: the caller still wants to know the binding it had ended.
        Porter.bindUserService(args, conn);

        ShadowLooper.shadowMainLooper().idle();

        assertEquals("a rebind swallowed the death of the binding it overtook",
                1, conn.disconnects);
    }
}
