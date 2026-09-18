package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER;
import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Receives the binder the Porter server sends when the app process starts. The SDK declares this
 * provider itself at {@code ${applicationId}.porter.api}; an app that must not expose it removes
 * the declaration with {@code tools:node="remove"}.
 *
 * <p>{@code android:permission} has to be one granted to the shell but not to normal apps, so that
 * only this app and the server can reach it; {@code android:exported} has to be true for the server
 * to reach it at all; {@code android:multiprocess} has to be false because the server reads the uid
 * once, when the app starts.
 *
 * <p>If the app runs in several processes, see {@link #enableMultiProcessSupport(boolean)}.
 */
public class PorterApiProvider extends ContentProvider {

    private static final String TAG = "PorterApiProvider";

    public static final String ACTION_BINDER_RECEIVED = "eu.darken.porter.sdk.action.BINDER_RECEIVED";

    private static boolean enableMultiProcess = false;

    private static boolean isProviderProcess = false;

    /**
     * Enables the built-in multi-process support. Call this as early as possible, for instance in a
     * static block of the Application class.
     */
    public static void enableMultiProcessSupport(boolean isProviderProcess) {
        Log.d(TAG, "Enable built-in multi-process support (from "
                + (isProviderProcess ? "provider process" : "non-provider process") + ")");

        PorterApiProvider.isProviderProcess = isProviderProcess;
        PorterApiProvider.enableMultiProcess = true;
    }

    /**
     * Asks for the binder in a process that does not host the provider;
     * {@link #enableMultiProcessSupport(boolean)} must have been called first.
     */
    public static void requestBinderForNonProviderProcess(@NonNull Context context) {
        if (isProviderProcess) {
            return;
        }

        Log.d(TAG, "request binder in non-provider process");

        // Below API 33 a registered receiver is exported, so any app can send this action to us.
        // Treat the broadcast as a notification only and read the binder from the provider, which
        // android:permission and the same-uid exemption restrict to this app and the server.
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "binder announced by broadcast");
                fetchBinderFromProvider(context);
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(ACTION_BINDER_RECEIVED));
        }

        // A delivery this process refused was refused because a connection was live. Once that
        // connection dies nothing is live, the refusal no longer applies, and the binder the
        // provider process holds can be adopted. It runs as a post-death hook rather than as a dead
        // listener so that every listener registered for that death is told before the replacement
        // announces itself: an app that registers its own listeners after this call would otherwise
        // sit behind this one.
        Context appContext = context.getApplicationContext();
        Porter.addPostBinderDeadHook(() -> fetchBinderFromProvider(appContext));

        fetchBinderFromProvider(context);
    }

    /**
     * Asks every authority a server can have delivered to, because the session this process is
     * looking for is on whichever backend the provider process attached.
     */
    static boolean fetchBinderFromProvider(@NonNull Context context) {
        if (fetchThrough(context, PorterProtocolDelivery.INSTANCE)) return true;
        // ShizukuProtocolDelivery names the container as a type, so the class cannot load at all
        // where the optional artifact is absent.
        return ShizukuCompat.isPresent() && fetchThrough(context, ShizukuProtocolDelivery.INSTANCE);
    }

    static boolean fetchThrough(@NonNull Context context, @NonNull PorterDelivery delivery) {
        Bundle reply;
        try {
            reply = context.getContentResolver().call(
                    Uri.parse("content://" + context.getPackageName() + delivery.authoritySuffix()),
                    DELIVERY_METHOD_GET_BINDER, null, new Bundle());
        } catch (Throwable tr) {
            reply = null;
        }

        if (reply == null) return false;
        IBinder binder = delivery.readBinder(reply);
        if (binder == null) return false;

        Log.i(TAG, "Binder received from other process");
        // Ungated on purpose: this is the provider process's own connection, on the backend that
        // process already selected. Rejecting it here would refuse a binder the app is using.
        PorterSession.adoptBackend(delivery.backend());
        PorterSession.onBinderReceived(binder, context.getPackageName(), delivery.backend());
        return true;
    }

    /** The envelope this provider speaks; a subclass overrides it to answer another authority. */
    @NonNull
    PorterDelivery delivery() {
        return PorterProtocolDelivery.INSTANCE;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);

        if (info.multiprocess)
            throw new IllegalStateException("android:multiprocess must be false");

        if (!info.exported)
            throw new IllegalStateException("android:exported must be true");

        isProviderProcess = true;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (extras == null) {
            return null;
        }

        Bundle reply = new Bundle();
        switch (method) {
            case DELIVERY_METHOD_SEND_BINDER: {
                handleSendBinder(extras);
                break;
            }
            case DELIVERY_METHOD_GET_BINDER: {
                if (!handleGetBinder(reply)) {
                    return null;
                }
                break;
            }
        }
        return reply;
    }

    private void handleSendBinder(@NonNull Bundle extras) {
        if (Porter.pingBinder()) {
            Log.d(TAG, "sendBinder is called when already a living binder");
            return;
        }

        IBinder binder = delivery().readBinder(extras);
        if (binder == null) {
            Log.w(TAG, "sendBinder is called without a binder");
            return;
        }

        Log.d(TAG, "binder received");

        Porter.onBinderReceived(
                getContext(), binder, getContext().getPackageName(), delivery().backend());

        if (enableMultiProcess) {
            Log.d(TAG, "broadcast binder");

            Intent intent = new Intent(ACTION_BINDER_RECEIVED)
                    .setPackage(getContext().getPackageName());
            getContext().sendBroadcast(intent);
        }
    }

    private boolean handleGetBinder(@NonNull Bundle reply) {
        // Other processes in the same app can read the provider without permission
        IBinder binder = PorterSession.binderFor(delivery().backend());
        if (binder == null) {
            return false;
        }

        delivery().writeBinder(reply, binder);
        return true;
    }

    // no other provider methods
    @Nullable
    @Override
    public final Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                              @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public final String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public final Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public final int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public final int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                            @Nullable String[] selectionArgs) {
        return 0;
    }
}
