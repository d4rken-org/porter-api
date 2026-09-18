package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER;
import static org.junit.Assert.assertEquals;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An app that registers its listeners after
 * {@link PorterApiProvider#requestBinderForNonProviderProcess(Context)} is behind whatever that call
 * registered for itself. A transition that ends connected still reaches the app in that order, dead and
 * then received, so listener-driven connection state does not settle on disconnected while a live
 * replacement is in hand.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedDeathBeforeReconnectTest {

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
    public void theDeathReachesTheAppBeforeTheReconnectThatFollowsIt() {
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

        // Registered after the multi-process setup call, which is where an app that sets the SDK up
        // in its Application and observes it from a screen registers them. That puts them behind
        // the dead listener the setup call registered for itself.
        List<String> told = new ArrayList<>();
        Porter.addBinderReceivedListener(() -> told.add("received"));
        Porter.addBinderDeadListener(() -> told.add("dead"));

        deathRecipientOf(shizuku).binderDied();
        ShadowLooper.shadowMainLooper().idle();

        assertEquals("the app was told received before dead: the connection it was on died and was"
                        + " replaced, and the replacement was announced inside the death dispatch,"
                        + " ahead of the app's own dead listener, so listener-driven connection"
                        + " state settles on disconnected although a live binder is in hand",
                Arrays.asList("dead", "received"), told);

        assertSame("once everything has settled the provider process's binder is the published one",
                providerProcessBinder, Porter.getBinder());
        assertEquals("the binder fetched after the death has to be attached to exactly once",
                1, providerProcessBinder.attachCount);
    }
}
