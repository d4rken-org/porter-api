package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLooper;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.darken.porter.server.IPorterApplication;

/** A sticky listener that registers while a live connection is being replaced by one that dies. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF8DeathRollbackStickyCatchUpTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /** Holds its attach reply in the server until the test lets it answer. */
    private static class BlockingService extends FakePorterService {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Bundle attach(IPorterApplication application, Bundle args) {
            Bundle reply = super.attach(application, args);
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return reply;
        }
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void aStickyListenerIsToldAboutTheConnectionADeathRolledBackTo() throws Exception {
        FakePorterService serving = new FakePorterService();
        Porter.onBinderReceived(serving, PACKAGE);

        BlockingService dying = new BlockingService();
        Thread attaching = new Thread(() -> Porter.onBinderReceived(dying, PACKAGE), "porter-attach");
        attaching.setDaemon(true);
        attaching.start();

        int[] calls = {0};
        try {
            assertTrue("the replacement never reached the server", dying.entered.await(5, TimeUnit.SECONDS));

            // Nothing tells this listener about the connection that is serving: the replacement is
            // the newest connection and it is not ready.
            Porter.addBinderReceivedListenerSticky(() -> calls[0]++);
            assertEquals("the sticky listener was told about the connection being replaced", 0, calls[0]);

            // The replacement dies before it can publish, so the serving connection stays.
            ShadowBinder shadow = Shadow.extract(dying);
            List<IBinder.DeathRecipient> recipients = shadow.getDeathRecipients();
            assertEquals("the attaching connection was not watched for death", 1, recipients.size());
            recipients.get(0).binderDied();
        } finally {
            dying.release.countDown();
            attaching.join(TimeUnit.SECONDS.toMillis(5));
        }
        ShadowLooper.shadowMainLooper().idle();

        assertSame("the serving connection is not the one Porter answers for", serving, Porter.getBinder());
        assertTrue("the serving connection does not answer", Porter.pingBinder());
        assertEquals("the sticky listener was never told about the live connection", 1, calls[0]);
    }
}
