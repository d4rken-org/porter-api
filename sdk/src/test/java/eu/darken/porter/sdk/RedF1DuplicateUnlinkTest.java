package eu.darken.porter.sdk;

import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import eu.darken.porter.server.IPorterApplication;

/** A connection that is superseded while it attaches must not be unlinked from death twice. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedF1DuplicateUnlinkTest {

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

    /** Holds its attach reply in the server until the test lets it answer. */
    private static class BlockingService extends RemoteLikeService {

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
    public void anAttachSupersededWhileItRunsUnlinksItsDeathRecipientOnlyOnce() throws Throwable {
        RemoteLikeService serving = new RemoteLikeService();
        Porter.onBinderReceived(serving, PACKAGE);

        BlockingService slow = new BlockingService();
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Thread attaching = new Thread(() -> {
            try {
                Porter.onBinderReceived(slow, PACKAGE);
            } catch (Throwable t) {
                escaped.set(t);
            }
        }, "porter-attach");
        attaching.setDaemon(true);
        attaching.start();
        assertTrue("the replacement never reached the server", slow.entered.await(5, TimeUnit.SECONDS));

        // A third binder arrives and supersedes the one that is still attaching, unlinking it.
        Porter.onBinderReceived(new RemoteLikeService(), PACKAGE);

        slow.release.countDown();
        attaching.join(TimeUnit.SECONDS.toMillis(5));

        Throwable thrown = escaped.get();
        if (thrown != null) throw thrown;
    }
}
