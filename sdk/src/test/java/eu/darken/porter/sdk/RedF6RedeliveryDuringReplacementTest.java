package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.darken.porter.server.IPorterApplication;

/**
 * Redelivering the binder the app already holds, while a replacement is attaching.
 *
 * <p>This asserts the attach count only. An earlier draft also asserted that the redelivered binder
 * gained no second death recipient; that assertion was never reached when the defect was reproduced,
 * and it cannot hold under any variant of the fix, because publishing the replacement unlinks the
 * connection that handed over. The double-link half of this defect is therefore not covered here.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF6RedeliveryDuringReplacementTest {

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
    public void redeliveringTheHeldBinderDuringAReplacementAttachesItOnlyOnce() throws Exception {
        FakePorterService serving = new FakePorterService();
        Porter.onBinderReceived(serving, PACKAGE);
        assertEquals(1, serving.attachCount);

        BlockingService replacement = new BlockingService();
        Thread attaching = new Thread(() -> Porter.onBinderReceived(replacement, PACKAGE), "porter-attach");
        attaching.setDaemon(true);
        attaching.start();
        assertTrue("the replacement never reached the server", replacement.entered.await(5, TimeUnit.SECONDS));

        // Porter redelivers the binder this process already holds.
        Porter.onBinderReceived(serving, PACKAGE);

        replacement.release.countDown();
        attaching.join(TimeUnit.SECONDS.toMillis(5));

        assertEquals("the binder the app already held was attached again", 1, serving.attachCount);
    }
}
