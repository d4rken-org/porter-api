package eu.darken.porter.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowLog;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import moe.shizuku.server.IShizukuApplication;

/**
 * A Shizuku server that dies while a client is waiting for the attach state it answers nothing for.
 * The wait is on a latch no reply will ever count down, and it runs on the thread the delivery
 * arrived on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedAttachWaitDeadBinderTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    /** Long enough that waiting it out cannot be mistaken for the wait being woken. */
    private static final long ATTACH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(60);
    private static final long JOIN_MS = TimeUnit.SECONDS.toMillis(5);

    private final PorterWire.Callbacks callbacks = new PorterWire.Callbacks() {

        @Override
        public void onRequestPermissionResult(int requestCode, boolean allowed) {
        }

        @Override
        public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) {
        }
    };

    /** Records the attach as its parent does, and says when the server has been asked. */
    private static class AttachSignalService extends FakeShizukuService {

        final CountDownLatch asked = new CountDownLatch(1);

        @Override
        public void attachApplication(IShizukuApplication application, Bundle args) {
            super.attachApplication(application, args);
            asked.countDown();
        }
    }

    @After
    public void teardown() {
        ShadowLog.stream = null;
        Porter.resetForTest();
    }

    private static IBinder.DeathRecipient deathRecipientOf(IBinder binder) {
        ShadowBinder shadow = Shadow.extract(binder);
        List<IBinder.DeathRecipient> recipients = shadow.getDeathRecipients();
        assertEquals("one connection links one recipient", 1, recipients.size());
        return recipients.get(0);
    }

    private static Thread attaching(Runnable body) {
        Thread thread = new Thread(body, "porter-attach");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    public void theAttachWaitIsWokenByTheDeathOfTheServerItIsWaitingOn() throws Exception {
        FakeShizukuService silent = new FakeShizukuService();
        silent.suppressBindApplication = true;
        ShizukuProtocolWire wire = new ShizukuProtocolWire(silent, callbacks, ATTACH_TIMEOUT_MS);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread attach = attaching(() -> {
            try {
                wire.attach(PACKAGE);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });

        wire.onPeerDied();

        attach.join(JOIN_MS);
        assertFalse("the attach was still waiting " + JOIN_MS + "ms after the server died, so the"
                + " death was recorded without releasing the wait", attach.isAlive());
        Throwable failure = thrown.get();
        assertNotNull("a wire whose server died answered the attach instead of failing it", failure);
        assertTrue("the failure does not name the death: " + failure.getMessage(),
                failure.getMessage().contains("died before it answered attach"));
    }

    @Test
    public void aSessionThatDiesWhileAttachingTellsTheWireItIsWaitingOn() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PorterSession.selectBackendForTest(PorterSession.Selection.SHIZUKU);
        AttachSignalService dying = new AttachSignalService();
        dying.suppressBindApplication = true;

        // The failure is swallowed into a log line on the delivery thread, so that is where it can
        // be read; a device sees the same line and nothing else.
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        ShadowLog.stream = new PrintStream(logged);

        Thread delivery = attaching(
                () -> Porter.onBinderReceived(context, dying, PACKAGE, PorterBackend.SHIZUKU));
        assertTrue("the delivery never reached the server",
                dying.asked.await(JOIN_MS, TimeUnit.MILLISECONDS));

        deathRecipientOf(dying).binderDied();

        delivery.join(JOIN_MS);
        assertFalse("the delivery thread was still attaching " + JOIN_MS + "ms after the binder"
                + " died", delivery.isAlive());
        String log = logged.toString();
        assertTrue("the death of the binder being attached to was not what ended the attach: " + log,
                log.contains("died before it answered attach"));
        assertFalse("the attach ran to its timeout although its binder had already died: " + log,
                log.contains("did not answer attach within"));
    }
}
