package rikka.shizuku.server.legacy;

import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DAEMON;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_REMOVE;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TAG;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS;
import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE;

import android.content.ComponentName;
import android.os.Bundle;

import java.util.Objects;

import eu.darken.porter.core.UserServiceOptions;

/**
 * Reads the Shizuku option Bundle. Bind and remove have their own decoder because each reads only
 * the keys its own operation reads, and the component key first, so a Bundle that fails to unparcel
 * fails at the same point for both.
 */
public final class LegacyUserServiceOptions {

    private LegacyUserServiceOptions() {
    }

    public static UserServiceOptions decodeForBind(Bundle options) {
        ComponentName component =
                Objects.requireNonNull(options.getParcelable(USER_SERVICE_ARG_COMPONENT), "component is null");
        int versionCode = options.getInt(USER_SERVICE_ARG_VERSION_CODE, 1);
        String tag = options.getString(USER_SERVICE_ARG_TAG);
        String processNameSuffix = options.getString(USER_SERVICE_ARG_PROCESS_NAME);
        boolean debuggable = options.getBoolean(USER_SERVICE_ARG_DEBUGGABLE, false);
        boolean noCreate = options.getBoolean(USER_SERVICE_ARG_NO_CREATE, false);
        boolean daemon = options.getBoolean(USER_SERVICE_ARG_DAEMON, true);
        boolean use32Bit = options.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, false);

        return new UserServiceOptions(
                component, tag, versionCode, processNameSuffix, debuggable, noCreate, daemon, use32Bit, true);
    }

    public static UserServiceOptions decodeForRemove(Bundle options) {
        ComponentName component =
                Objects.requireNonNull(options.getParcelable(USER_SERVICE_ARG_COMPONENT), "component is null");
        String tag = options.getString(USER_SERVICE_ARG_TAG);

        // API < 13.1.4 will not send USER_SERVICE_ARG_REMOVE, true by default
        boolean remove = true;
        if (options.containsKey(USER_SERVICE_ARG_REMOVE)) {
            remove = options.getBoolean(USER_SERVICE_ARG_REMOVE);
        }

        return new UserServiceOptions(component, tag, 1, null, false, false, true, false, remove);
    }

    public static String decodeToken(Bundle options) {
        return Objects.requireNonNull(options.getString(USER_SERVICE_ARG_TOKEN), "token is null");
    }
}
