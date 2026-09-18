package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE;

import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.Objects;

/** How {@link Porter.UserServiceArgs} is written in the Porter server's key space. */
final class PorterUserServiceCodec {

    private PorterUserServiceCodec() {
    }

    /** @param noCreate marks the service "do not start it", as {@code peekUserService} asks for */
    @NonNull
    static Bundle encodeUserService(@NonNull Porter.UserServiceArgs args, boolean noCreate) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(USER_SERVICE_COMPONENT, args.componentName);
        bundle.putBoolean(USER_SERVICE_DEBUGGABLE, args.debuggable);
        bundle.putInt(USER_SERVICE_VERSION_CODE, args.versionCode);
        bundle.putBoolean(USER_SERVICE_DAEMON, args.daemon);
        bundle.putBoolean(USER_SERVICE_USE_32_BIT, args.use32BitAppProcess);
        bundle.putString(USER_SERVICE_PROCESS_NAME_SUFFIX,
                Objects.requireNonNull(args.processName, "process name suffix must not be null"));
        if (args.tag != null) {
            bundle.putString(USER_SERVICE_TAG, args.tag);
        }
        if (noCreate) {
            bundle.putBoolean(USER_SERVICE_NO_CREATE, true);
        }
        return bundle;
    }

    @NonNull
    static Bundle encodeUserServiceRemoval(@NonNull Porter.UserServiceArgs args, boolean remove) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(USER_SERVICE_COMPONENT, args.componentName);
        if (args.tag != null) {
            bundle.putString(USER_SERVICE_TAG, args.tag);
        }
        bundle.putBoolean(USER_SERVICE_REMOVE, remove);
        return bundle;
    }
}
