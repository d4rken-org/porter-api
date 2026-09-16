package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

/** A binder that dies between its attach reply and the publication of its session. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF3DeathBeforePublicationTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /** Answers attach, then holds the reply on its way back until the test lets it through. */
    private static class SlowReplyService extends FakePorterService {

        final CountDownLatch replied = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Bundle attach(IPorterApplication application, Bundle args) {
            Bundle reply = super.attach(application, args);
            replied.countDown();
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
    public void aBinderThatDiesBeforeItIsPublishedIsNotPublished() throws Exception {
        int[] received = {0};
        Porter.addBinderReceivedListener(() -> received[0]++);
        int[] dead = {0};
        Porter.addBinderDeadListener(() -> dead[0]++);

        SlowReplyService dying = new SlowReplyService();
        Thread attaching = new Thread(() -> Porter.onBinderReceived(dying, PACKAGE), "porter-attach");
        attaching.setDaemon(true);
        attaching.start();
        assertTrue("the attach never reached the server", dying.replied.await(5, TimeUnit.SECONDS));

        // The server dies while its reply is still on its way back to the client.
        ShadowBinder shadow = Shadow.extract(dying);
        List<IBinder.DeathRecipient> recipients = shadow.getDeathRecipients();
        assertEquals("the attaching connection was not watched for death", 1, recipients.size());
        recipients.get(0).binderDied();

        dying.release.countDown();
        attaching.join(TimeUnit.SECONDS.toMillis(5));
        ShadowLooper.shadowMainLooper().idle();

        assertNull("a connection whose binder had died was published", Porter.getBinder());
        assertEquals("a dead connection announced itself ready", 0, received[0]);
    }
}
