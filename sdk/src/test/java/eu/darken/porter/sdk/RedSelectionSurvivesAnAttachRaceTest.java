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
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.darken.porter.protocol.PorterProtocol;

import moe.shizuku.server.IShizukuApplication;

/**
 * A cross-process fetch seeds the selection with {@link PorterSession#adoptBackend} and then attaches
 * outside {@code SESSION_LOCK}, so the published connection does not exist yet while the attach runs. A
 * {@link Porter#getAvailability} that lands in that window resolves for itself and writes its answer,
 * because the guard that defers to a live connection only fires once one is published. The connection
 * that publishes afterwards must not be left answering for the other backend.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RedSelectionSurvivesAnAttachRaceTest {

    private static final String PACKAGE = "eu.darken.porter.probe";

    /**
     * Stops the fetching thread inside the attach handshake and holds it there. The handshake is the
     * one part of a delivery that runs outside {@code SESSION_LOCK}, so this is where a test can stand
     * while the session it is creating is not published yet.
     */
    private static final class GatedShizukuService extends FakeShizukuService {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void attachApplication(IShizukuApplication application, Bundle args) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.attachApplication(application, args);
        }
    }

    /** Stands in for the provider process, which is connected on Shizuku and holds that binder. */
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
            ShizukuProtocolDelivery.INSTANCE.writeBinder(reply, held);
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

    private Context context;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
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

    @Test
    public void anAvailabilityCallDuringTheFetchAttachDoesNotMoveTheSelectionOffThePublishedConnection()
            throws Exception {
        // The provider process is connected on Shizuku. Porter was installed afterwards, so resolving
        // again now answers PORTER, which is what makes the two answers differ at all.
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION);
        declares(PorterProtocol.MANAGER_APPLICATION_ID, PorterProtocol.PERMISSION);

        GatedShizukuService providerProcessBinder = new GatedShizukuService();
        ShadowContentResolver.registerProviderInternal(
                context.getPackageName() + ShizukuProtocolDelivery.INSTANCE.authoritySuffix(),
                new ProviderProcess(providerProcessBinder));

        // This process fetches the provider process's Shizuku binder and gets stuck in the handshake.
        Thread fetcher = new Thread(
                () -> PorterApiProvider.fetchThrough(context, ShizukuProtocolDelivery.INSTANCE),
                "fetch-binder");
        fetcher.start();
        assertTrue("the fetch has to reach the attach handshake within the timeout",
                providerProcessBinder.entered.await(10, TimeUnit.SECONDS));

        // What this process does whenever it is asked. Nothing is published yet, so this resolves for
        // itself rather than reading the connection, and records the answer it resolved.
        Porter.getAvailability(context);

        providerProcessBinder.release.countDown();
        fetcher.join(TimeUnit.SECONDS.toMillis(10));
        assertTrue("the fetch has to finish within the timeout", !fetcher.isAlive());
        ShadowLooper.shadowMainLooper().idle();

        PorterSession.Selection selection = PorterSession.selectBackend(context);
        assertSame("the fetched Shizuku binder has to be the published connection",
                providerProcessBinder, Porter.getBinder());
        assertEquals("the published connection has to be the Shizuku one that was fetched",
                PorterBackend.SHIZUKU, PorterSession.currentBackend());

        // What the disagreement then costs: the process refuses its own server's next delivery.
        FakeShizukuService replacement = new FakeShizukuService();
        Porter.onBinderReceived(context, replacement, PACKAGE, PorterBackend.SHIZUKU);

        assertEquals("this process published a Shizuku connection but answers for Porter: the"
                        + " getAvailability that ran while the fetched binder was still attaching"
                        + " resolved PORTER and wrote it over the SHIZUKU that adoptBackend seeded,"
                        + " and publishing the connection never put the selection back",
                PorterSession.Selection.SHIZUKU, selection);

        assertEquals("a later Shizuku delivery from this process's own server was refused, because"
                        + " the selection check compared it against a selection naming the other"
                        + " backend; nothing can replace this connection until it dies",
                1, replacement.attachCount);
        assertSame("the later Shizuku delivery never became the published connection",
                replacement, Porter.getBinder());
    }
}
