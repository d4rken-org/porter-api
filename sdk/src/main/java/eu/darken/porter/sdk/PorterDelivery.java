package eu.darken.porter.sdk;

import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One way a server hands a binder to {@link PorterApiProvider}: the authority it calls, the envelope
 * the binder travels in, and the backend a binder that arrived this way belongs to.
 */
interface PorterDelivery {

    /** Appended to the package name to form the provider authority. */
    @NonNull
    String authoritySuffix();

    @Nullable
    IBinder readBinder(@NonNull Bundle extras);

    void writeBinder(@NonNull Bundle reply, @NonNull IBinder binder);

    @NonNull
    PorterBackend backend();
}
