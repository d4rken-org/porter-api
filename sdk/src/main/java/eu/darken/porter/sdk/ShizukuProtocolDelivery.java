package eu.darken.porter.sdk;

import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import moe.shizuku.api.BinderContainer;

/** Shizuku's envelope: the binder travels inside a {@link BinderContainer} Parcelable. */
final class ShizukuProtocolDelivery implements PorterDelivery {

    static final ShizukuProtocolDelivery INSTANCE = new ShizukuProtocolDelivery();

    private static final String AUTHORITY_SUFFIX = ".shizuku";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    private ShizukuProtocolDelivery() {
    }

    @NonNull
    @Override
    public String authoritySuffix() {
        return AUTHORITY_SUFFIX;
    }

    @Nullable
    @Override
    public IBinder readBinder(@NonNull Bundle extras) {
        // The extras arrive from another process, so the container is still parcelled and nothing on
        // the framework class loader can name it.
        extras.setClassLoader(BinderContainer.class.getClassLoader());
        BinderContainer container = extras.getParcelable(EXTRA_BINDER);
        return container == null ? null : container.binder;
    }

    @Override
    public void writeBinder(@NonNull Bundle reply, @NonNull IBinder binder) {
        reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
    }

    @NonNull
    @Override
    public PorterBackend backend() {
        return PorterBackend.SHIZUKU;
    }
}
