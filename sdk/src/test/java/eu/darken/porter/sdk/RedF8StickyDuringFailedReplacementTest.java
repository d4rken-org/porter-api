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

/** A sticky listener that registers while a live connection is being replaced. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF8StickyDuringFailedReplacementTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

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
    public void aStickyListenerRegisteredDuringAFailedReplacementIsCalled() throws Exception {
        FakePorterService serving = new FakePorterService();
        Porter.onBinderReceived(serving, PACKAGE);

        BlockingRefusingService refused = new BlockingRefusingService();
        Thread attaching = new Thread(() -> Porter.onBinderReceived(refused, PACKAGE), "porter-attach");
        attaching.setDaemon(true);
        attaching.start();
        assertTrue("the replacement never reached the server", refused.entered.await(5, TimeUnit.SECONDS));

        int[] calls = {0};
        Porter.addBinderReceivedListenerSticky(() -> calls[0]++);

        refused.release.countDown();
        attaching.join(TimeUnit.SECONDS.toMillis(5));
        ShadowLooper.shadowMainLooper().idle();

        assertSame("the serving connection was not restored", serving, Porter.getBinder());
        assertTrue("the restored connection does not answer", Porter.pingBinder());
        assertEquals("the sticky listener was never told about the live connection", 1, calls[0]);
    }
}
