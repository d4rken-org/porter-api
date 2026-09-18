package eu.darken.porter.sdk;

import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_COMPONENT;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DAEMON;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DEBUGGABLE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_NO_CREATE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_PROCESS_NAME;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_REMOVE;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TAG;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_VERSION_CODE;

import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.Objects;

/** How {@link Porter.UserServiceArgs} is written in a Shizuku server's key space. */
final class ShizukuUserServiceCodec {

    private ShizukuUserServiceCodec() {
    }

    /** @param noCreate marks the service "do not start it", as {@code peekUserService} asks for */
    @NonNull
    static Bundle encodeUserService(@NonNull Porter.UserServiceArgs args, boolean noCreate) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(USER_SERVICE_ARG_COMPONENT, args.componentName);
        bundle.putBoolean(USER_SERVICE_ARG_DEBUGGABLE, args.debuggable);
        bundle.putInt(USER_SERVICE_ARG_VERSION_CODE, args.versionCode);
        bundle.putBoolean(USER_SERVICE_ARG_DAEMON, args.daemon);
        bundle.putBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, args.use32BitAppProcess);
        bundle.putString(USER_SERVICE_ARG_PROCESS_NAME,
                Objects.requireNonNull(args.processName, "process name suffix must not be null"));
        if (args.tag != null) {
            bundle.putString(USER_SERVICE_ARG_TAG, args.tag);
        }
        if (noCreate) {
            bundle.putBoolean(USER_SERVICE_ARG_NO_CREATE, true);
        }
        return bundle;
    }

    @NonNull
    static Bundle encodeUserServiceRemoval(@NonNull Porter.UserServiceArgs args, boolean remove) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(USER_SERVICE_ARG_COMPONENT, args.componentName);
        if (args.tag != null) {
            bundle.putString(USER_SERVICE_ARG_TAG, args.tag);
        }
        bundle.putBoolean(USER_SERVICE_ARG_REMOVE, remove);
        return bundle;
    }
}
