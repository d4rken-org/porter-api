package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PermissionInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

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

import java.util.List;

/**
 * A dead listener that throws is unwinding out of the death dispatch when the SDK's own reaction to
 * that death runs. That reaction reconnects, and reconnecting calls the app's received listeners,
 * which can fail too. Whatever the reconnect throws must not be what the app's caller sees: the
 * failure the app's dead listener raised is the one that escapes {@code binderDied()}, and the
 * reconnect happens on a later looper turn, where its own failure belongs to that turn.
 *
 * <p>An {@link Error} is what makes the two distinguishable. A {@link RuntimeException} out of a
 * received listener is already caught where the connection is published; an Error is not, so it is
 * the throwable that can reach the unwinding stack and take its place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDeathHookPreservesAListenerFailureTest {

    private Context context;

    /** The binder the provider process attached, which this process can only reach by fetching. */
    private final FakePorterService providerProcessBinder = new FakePorterService();

    /**
     * Stands in for the provider process at Porter's authority. A real one answers out of its own
     * session, which is not this process's static state, so it cannot be the SDK's own provider
     * here: that one would answer out of the very session this test is driving.
     */
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
    public void theReconnectDoesNotReplaceTheFailureTheAppsDeadListenerRaised() {
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
        assertEquals("a refused delivery must not be attached to", 0, providerProcessBinder.attachCount);

        RuntimeException deadFailure = new IllegalStateException("app dead listener failed");
        AssertionError receivedFailure = new AssertionError("app received listener failed");
        Porter.addBinderReceivedListener(() -> {
            throw receivedFailure;
        });
        Porter.addBinderDeadListener(() -> {
            throw deadFailure;
        });

        // The death arrives on the main thread, so a listener with no handler of its own runs inline
        // and its failure unwinds through the dispatch.
        Throwable escapedTheDeath = null;
        try {
            deathRecipientOf(shizuku).binderDied();
        } catch (Throwable tr) {
            escapedTheDeath = tr;
        }
        int attachedDuringTheDeath = providerProcessBinder.attachCount;

        // The reconnect belongs to a later turn, and its own failure is that turn's to report.
        Throwable escapedTheLooper = null;
        try {
            ShadowLooper.shadowMainLooper().idle();
        } catch (Throwable tr) {
            escapedTheLooper = tr;
        }

        assertNotNull("a dead listener that throws has to keep throwing out to its caller, not be"
                + " swallowed by the dispatch", escapedTheDeath);
        assertSame("the app's dead listener raised one failure and the reconnect that followed it"
                        + " raised another, and the second one is what reached the app's caller: the"
                        + " reconnect ran on the stack that was unwinding the first, so the failure"
                        + " the app actually has to diagnose was discarded",
                deadFailure, escapedTheDeath);

        assertEquals("the reconnect must not run on the unwinding stack: nothing may be attached to"
                        + " before the death dispatch has returned to its caller",
                0, attachedDuringTheDeath);
        assertEquals("the reconnect still has to happen, one looper turn later: nothing else fetches"
                        + " after a death, so skipping it leaves this process disconnected for as"
                        + " long as it lives while the provider process holds a binder that answers",
                1, providerProcessBinder.attachCount);
        assertNotNull("the received listener's own failure belongs to the looper turn that called"
                + " it, and has to surface there", escapedTheLooper);
        assertSame("the looper turn has to report the received listener's failure unchanged",
                receivedFailure, escapedTheLooper);
    }
}
