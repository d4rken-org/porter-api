package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER;
import static eu.darken.porter.protocol.PorterProtocol.PROVIDER_AUTHORITY_SUFFIX;

import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Porter's envelope: the binder is a Bundle binder value, with no Parcelable in between. */
final class PorterProtocolDelivery implements PorterDelivery {

    static final PorterProtocolDelivery INSTANCE = new PorterProtocolDelivery();

    private PorterProtocolDelivery() {
    }

    @NonNull
    @Override
    public String authoritySuffix() {
        return PROVIDER_AUTHORITY_SUFFIX;
    }

    @Nullable
    @Override
    public IBinder readBinder(@NonNull Bundle extras) {
        return extras.getBinder(DELIVERY_EXTRA_BINDER);
    }

    @Override
    public void writeBinder(@NonNull Bundle reply, @NonNull IBinder binder) {
        reply.putBinder(DELIVERY_EXTRA_BINDER, binder);
    }

    @NonNull
    @Override
    public PorterBackend backend() {
        return PorterBackend.PORTER;
    }
}
