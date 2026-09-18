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

import java.util.ArrayList;
import java.util.List;

/** One binder carries a death recipient per "connected" push, so its death is signalled repeatedly. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDuplicateDeathRecipientTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";

    /** Keeps every recipient linked to it, so a test can signal the death one recipient at a time. */
    private static final class RecordingBinder extends Binder {

        final List<DeathRecipient> recipients = new ArrayList<>();

        @Override
        public void linkToDeath(DeathRecipient recipient, int flags) {
            recipients.add(recipient);
        }

        @Override
        public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
            return recipients.remove(recipient);
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
    public void aSecondDeathSignalOnOneBinderLeavesALaterBindAlone() {
        Porter.onBinderReceived(new FakePorterService(), PACKAGE);
        Porter.UserServiceArgs args = args("duplicate-death-recipient");
        RecordingBinder binder = new RecordingBinder();

        // The server pushes "connected" for each accepted add while the service is alive, and each
        // push links a recipient of its own to the one binder.
        RecordingConnection first = new RecordingConnection();
        Porter.bindUserService(args, first);
        PorterServiceConnection connection = PorterServiceConnections.peek(args);
        assertNotNull(connection);
        connection.connected(binder);
        ShadowLooper.shadowMainLooper().idle();

        RecordingConnection second = new RecordingConnection();
        Porter.bindUserService(args, second);
        connection.connected(binder);
        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the second connected push did not link a recipient of its own",
                2, binder.recipients.size());
        assertEquals("the first caller was never connected", 2, first.connects);
        assertEquals("the second caller was never connected", 1, second.connects);

        binder.recipients.get(0).binderDied();

        // A bind lands between the two signals of the same binder's single death.
        RecordingConnection late = new RecordingConnection();
        Porter.bindUserService(args, late);

        binder.recipients.get(1).binderDied();
        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the caller that bound after the death was told its new binding died",
                0, late.disconnects);
        assertEquals("the first caller was never told its service died", 1, first.disconnects);
        assertEquals("the second caller was never told its service died", 1, second.disconnects);

        PorterServiceConnection rebound = PorterServiceConnections.peek(args);
        assertNotNull("the second death signal dropped the binding that arrived after the death",
                rebound);
        assertNotSame("the later bind was handed back the binding the death retired",
                connection, rebound);

        rebound.connected(new Binder());
        ShadowLooper.shadowMainLooper().idle();
        assertEquals("the later caller never heard about its own binding", 1, late.connects);
    }
}
