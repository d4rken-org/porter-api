package eu.darken.porter.sdk;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/** A listener that replaces the connection it is being told about and then fails. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF11ThrowingListenerDuplicateUnlinkTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /**
     * Death links with the bookkeeping of a binder that lives in another process:
     * {@code android.os.BinderProxy.unlinkToDeath} throws {@link NoSuchElementException} when the
     * recipient is not linked. A local {@code android.os.Binder}, which is what a plain
     * {@link FakePorterService} is, ignores {@code unlinkToDeath} altogether, so a duplicate unlink
     * leaves no trace there.
     */
    private static class RemoteLikeService extends FakePorterService {

        private final List<IBinder.DeathRecipient> linked = new ArrayList<>();

        @Override
        public void linkToDeath(IBinder.DeathRecipient recipient, int flags) {
            synchronized (linked) {
                linked.add(recipient);
            }
        }

        @Override
        public boolean unlinkToDeath(IBinder.DeathRecipient recipient, int flags) {
            synchronized (linked) {
                if (!linked.remove(recipient)) {
                    throw new NoSuchElementException("Death link does not exist");
                }
            }
            return true;
        }
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    @Test
    public void aConnectionSupersededFromInsideItsOwnAnnouncementIsUnlinkedOnlyOnce() {
        RemoteLikeService first = new RemoteLikeService();
        RemoteLikeService second = new RemoteLikeService();

        boolean[] replaced = {false};
        Porter.addBinderReceivedListener(() -> {
            if (replaced[0]) return;
            replaced[0] = true;
            // Porter restarts while the app is handling the connection it just got, and the app
            // then fails on that connection.
            Porter.onBinderReceived(second, PACKAGE);
            throw new RuntimeException("the app failed while handling the connection");
        });

        Throwable escaped = null;
        try {
            Porter.onBinderReceived(first, PACKAGE);
        } catch (Throwable t) {
            escaped = t;
        }

        assertTrue("the listener never replaced the connection", replaced[0]);
        if (escaped != null) {
            throw new AssertionError(
                    "giving up on the superseded connection unlinked it a second time", escaped);
        }
        assertSame("the replacement is not the connection Porter answers for", second, Porter.getBinder());
    }
}
