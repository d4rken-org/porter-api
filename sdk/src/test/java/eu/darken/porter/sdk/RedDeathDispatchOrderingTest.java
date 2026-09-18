package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PermissionInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A death that arrives on a binder thread is dispatched by posting each listener to the main thread, one
 * post at a time, and the main thread is free to run between two of those posts. What the SDK itself
 * does about a death therefore runs after every listener has been posted rather than from inside one of
 * them, so the app is told dead before it is told received even when the main thread runs in the middle
 * of the dispatch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDeathDispatchOrderingTest {

    private Context context;

    /** The binder the provider process attached, which this process can only reach by fetching. */
    private final FakePorterService providerProcessBinder = new FakePorterService();

    /** Stands in for the provider process at Porter's authority. */
    private static final class ProviderProcess extends ContentProvider {

        private final IBinder held;

        ProviderProcess(@NonNull IBinder held) {
            this.held = held;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Nullable
        @Override
        public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
            if (!DELIVERY_METHOD_GET_BINDER.equals(method)) return null;
            Bundle reply = new Bundle();
            PorterProtocolDelivery.INSTANCE.writeBinder(reply, held);
            return reply;
        }

        @Nullable
        @Override
        public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                            @Nullable String[] selectionArgs, @Nullable String sortOrder) {
            return null;
        }

        @Nullable
        @Override
        public String getType(@NonNull Uri uri) {
            return null;
        }

        @Nullable
        @Override
        public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
            return null;
        }

        @Override
        public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                          @Nullable String[] selectionArgs) {
            return 0;
        }
    }

    /**
     * Stops the dispatching thread inside its first delivery and holds it there, which is the one
     * place a test can stand in the middle of the dispatch loop without the SDK knowing: the loop
     * hands each listener to a Handler, and a Handler decides for itself when a post returns.
     *
     * <p>Holding it there is what lets the main thread run at a moment the dispatching thread has
     * not finished dispatching. That is a scheduling choice, not an extra step: a real main thread
     * runs whatever is queued whenever it is not busy, including between two posts of one dispatch.
     */
    private static final class GateHandler extends Handler {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private boolean gated;

        GateHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public boolean sendMessageAtTime(@NonNull Message msg, long uptimeMillis) {
            if (!gated) {
                gated = true;
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.sendMessageAtTime(msg, uptimeMillis);
        }
    }

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        // This process hosts no provider, so the SDK's built-in fetches are all it has.
        PorterApiProvider.enableMultiProcessSupport(false);
        ShadowContentResolver.registerProviderInternal(
                context.getPackageName() + PorterProtocolDelivery.INSTANCE.authoritySuffix(),
                new ProviderProcess(providerProcessBinder));
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private void declares(String packageName, String permission) {
        PermissionInfo info = new PermissionInfo();
        info.name = permission;
        info.packageName = packageName;
        shadowOf(context.getPackageManager()).addPermissionInfo(info);
    }

    private static IBinder.DeathRecipient deathRecipientOf(IBinder binder) {
        ShadowBinder shadow = Shadow.extract(binder);
        List<IBinder.DeathRecipient> recipients = shadow.getDeathRecipients();
        assertEquals("one connection links one recipient", 1, recipients.size());
        return recipients.get(0);
    }

    @Test
    public void theReplacementIsNotAnnouncedMidwayThroughTheDeathDispatch() throws Exception {
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION);
        assertEquals("the only installed manager is Shizuku's, so that is what this process selects",
                PorterSession.Selection.SHIZUKU, PorterSession.selectBackend(context));

        // The connection this process is on, as an earlier fetch from the provider left it.
        FakeShizukuService shizuku = new FakeShizukuService();
        Porter.onBinderReceived(context, shizuku, context.getPackageName(), PorterBackend.SHIZUKU);
        assertSame("the Shizuku connection has to be published before the refused fetch runs",
                shizuku, Porter.getBinder());

        // Everything this process does about binder delivery, as the SDK documents it.
        PorterApiProvider.requestBinderForNonProviderProcess(context);
        assertSame("the provider process's Porter binder replaced a live Shizuku connection, which"
                + " the session lock is supposed to refuse", shizuku, Porter.getBinder());

        // Registered between the SDK's own setup call and the app's listeners, so the dispatch can
        // be stopped after it has posted whatever the setup call registered and before it posts
        // what the app registered.
        GateHandler gate = new GateHandler();
        Porter.addBinderDeadListener(() -> {
        }, gate);

        List<String> told = Collections.synchronizedList(new ArrayList<>());
        Porter.addBinderReceivedListener(() -> told.add("received"));
        Porter.addBinderDeadListener(() -> told.add("dead"));

        IBinder.DeathRecipient recipient = deathRecipientOf(shizuku);
        Thread binderThread = new Thread(recipient::binderDied, "binder-death");
        binderThread.start();

        assertTrue("the death dispatch has to reach the gate within the timeout",
                gate.entered.await(10, TimeUnit.SECONDS));
        // The main thread, doing what a main thread does whenever it is not busy.
        ShadowLooper.shadowMainLooper().idle();
        gate.release.countDown();
        binderThread.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue("the death dispatch has to finish within the timeout", !binderThread.isAlive());
        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the app was told received before dead: the death dispatch posted its internal"
                        + " re-fetch as a listener, the main thread ran that re-fetch while the"
                        + " dispatching thread had not yet posted the app's own dead listener, and"
                        + " the replacement announced itself from inside it, so listener-driven"
                        + " connection state settles on disconnected with a live binder in hand",
                Arrays.asList("dead", "received"), told);

        assertSame("once everything has settled the provider process's binder is the published one",
                providerProcessBinder, Porter.getBinder());
        assertEquals("the binder fetched after the death has to be attached to exactly once",
                1, providerProcessBinder.attachCount);
    }
}
