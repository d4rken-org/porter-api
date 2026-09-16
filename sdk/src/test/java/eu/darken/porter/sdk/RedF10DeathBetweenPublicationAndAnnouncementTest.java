package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** A connection that dies after it is published and before it is announced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF10DeathBetweenPublicationAndAnnouncementTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /**
     * The window under test is the handful of statements between publishing a connection and
     * announcing it. It holds no lock and makes no binder call, so the one point a test can reach
     * into it is the "attached" log line. Firing the death from there keeps the whole test on one
     * thread: there is no race to lose and no timeout to wait out.
     */
    private static class LogTrigger extends OutputStream {

        private final StringBuilder written = new StringBuilder();
        private final String marker;
        private final Runnable action;
        private boolean fired;

        LogTrigger(String marker, Runnable action) {
            this.marker = marker;
            this.action = action;
        }

        @Override
        public void write(int b) {
            written.append((char) (b & 0xff));
            if (!fired && written.indexOf(marker) >= 0) {
                fired = true;
                action.run();
            }
        }
    }

    @After
    public void teardown() {
        ShadowLog.stream = null;
        Porter.resetForTest();
    }

    @Test
    public void aConnectionThatDiesBeforeItIsAnnouncedIsNotAnnounced() {
        FakePorterService serving = new FakePorterService();
        Porter.onBinderReceived(serving, PACKAGE);

        List<String> events = new ArrayList<>();
        AtomicReference<IBinder> binderSeenByReceived = new AtomicReference<>();
        Porter.addBinderDeadListener(() -> events.add("dead"));
        Porter.addBinderReceivedListener(() -> {
            events.add("received");
            binderSeenByReceived.set(Porter.getBinder());
        });

        FakePorterService replacement = new FakePorterService();
        boolean[] killed = {false};
        ShadowLog.stream = new PrintStream(new LogTrigger("attached, connection", () -> {
            ShadowBinder shadow = Shadow.extract(replacement);
            List<IBinder.DeathRecipient> recipients =
                    new ArrayList<>(shadow.getDeathRecipients());
            killed[0] = !recipients.isEmpty();
            for (IBinder.DeathRecipient recipient : recipients) {
                recipient.binderDied();
            }
        }));
        try {
            Porter.onBinderReceived(replacement, PACKAGE);
        } finally {
            ShadowLog.stream = null;
        }
        ShadowLooper.shadowMainLooper().idle();

        assertTrue("the published connection was never killed, so nothing was under test", killed[0]);
        assertEquals("a connection that died before it was announced announced itself anyway"
                        + " (Porter.getBinder() inside that callback: " + binderSeenByReceived.get() + ")",
                Collections.singletonList("dead"), events);
    }
}
