package eu.darken.porter.sdk;

import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One user service binding as the SDK holds it: what a server pushes about the service, and the
 * binder each wire registered to receive those pushes.
 */
interface UserServiceCallback {

    void connected(@NonNull IBinder binder);

    void died();

    /**
     * The binder a wire registered for this callback, or null if that wire has not registered one.
     *
     * <p>One callback presents one binder identity for its whole life; see
     * {@code PorterProtocolWireUserServiceTest}, which pins it across an add and a later remove.
     */
    @Nullable
    IBinder registeredBinder(@NonNull PorterBackend backend);

    void rememberRegisteredBinder(@NonNull PorterBackend backend, @NonNull IBinder binder);
}
