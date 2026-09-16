package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.darken.porter.server.IPorterApplication;

/** One of two stacked replacements fails while the other is still attaching. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF9StaleRetainedCatchUpTest {

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

    /** Reaches the server, waits for the test, and then refuses the client. */
    private static class BlockingRefusingService extends FakePorterService {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Bundle attach(IPorterApplication application, Bundle args) {
            super.attach(application, args);
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            throw new SecurityException("not an attached client");
        }
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void aFailedReplacementBehindAnotherOneTellsAStickyListenerOnlyOnce() throws Exception {
        FakePorterService serving = new FakePorterService();
        Porter.onBinderReceived(serving, PACKAGE);

        BlockingRefusingService refused = new BlockingRefusingService();
        Thread failing = new Thread(() -> Porter.onBinderReceived(refused, PACKAGE), "porter-attach-refused");
        failing.setDaemon(true);

        BlockingService replacing = new BlockingService();
        Thread succeeding = new Thread(() -> Porter.onBinderReceived(replacing, PACKAGE), "porter-attach-replacing");
        succeeding.setDaemon(true);

        int[] calls = {0};
        try {
            failing.start();
            assertTrue("the first replacement never reached the server",
                    refused.entered.await(5, TimeUnit.SECONDS));
            succeeding.start();
            assertTrue("the second replacement never reached the server",
                    replacing.entered.await(5, TimeUnit.SECONDS));

            // The newest connection is the second replacement, which is not ready, so this listener
            // is told nothing yet.
            Porter.addBinderReceivedListenerSticky(() -> calls[0]++);
            assertEquals("the sticky listener was told while both replacements were attaching",
                    0, calls[0]);

            // The first replacement gives up; the second is still on its way in.
            refused.release.countDown();
            failing.join(TimeUnit.SECONDS.toMillis(5));

            // The second replacement publishes and announces itself.
            replacing.release.countDown();
            succeeding.join(TimeUnit.SECONDS.toMillis(5));
        } finally {
            refused.release.countDown();
            replacing.release.countDown();
            failing.join(TimeUnit.SECONDS.toMillis(5));
            succeeding.join(TimeUnit.SECONDS.toMillis(5));
        }
        ShadowLooper.shadowMainLooper().idle();

        assertSame("the second replacement is not the connection Porter answers for",
                replacing, Porter.getBinder());
        assertEquals("the sticky listener was told twice about one arriving connection", 1, calls[0]);
    }
}
