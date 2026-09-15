package eu.darken.porter.core;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import rikka.shizuku.server.ClientRecord;

/**
 * The decisions a server makes for itself. Every method has a default, so a server that grants
 * nothing is a valid one and a test implements only the hooks it drives.
 */
public interface ServerPolicy {

    default boolean checkCallerPermission(
            @NonNull String func, @NonNull CallerIdentity caller, @Nullable ClientRecord record) {
        return false;
    }

    default boolean checkCallerManagerPermission(@NonNull String func, @NonNull CallerIdentity caller) {
        return false;
    }

    default void showPermissionConfirmation(
            int requestCode, @NonNull ClientRecord record, @NonNull CallerIdentity caller, int userId) {
        throw new UnsupportedOperationException("no permission confirmation UI");
    }

    /** Before a record exists for the caller. */
    default void onAttaching(@NonNull CallerIdentity caller, @NonNull String packageName) {
    }

    /** The reply the endpoint is about to send, still open to shaping. */
    default void onAttached(@NonNull ClientRecord record, boolean created, @NonNull Bundle reply) {
    }

    /** After the reply reached the client. */
    default void onBound(@NonNull ClientRecord record, boolean created) {
    }
}
