package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE;

import android.content.ComponentName;
import android.os.Bundle;

import java.util.Objects;

import eu.darken.porter.core.UserServiceOptions;

/**
 * Reads the Porter option Bundle. Bind and remove have their own decoder because each reads only
 * the keys its own operation reads, and the component key first, so a Bundle that fails to unparcel
 * fails at the same point for both.
 */
public final class PorterUserServiceOptions {

    private PorterUserServiceOptions() {
    }

    public static UserServiceOptions decodeForBind(Bundle args) {
        ComponentName component =
                Objects.requireNonNull(args.getParcelable(USER_SERVICE_COMPONENT), "component is null");
        Objects.requireNonNull(component.getClassName(), "class is null");
        int versionCode = args.getInt(USER_SERVICE_VERSION_CODE, 1);
        String tag = args.getString(USER_SERVICE_TAG);
        String processNameSuffix = args.getString(USER_SERVICE_PROCESS_NAME_SUFFIX);
        boolean debuggable = args.getBoolean(USER_SERVICE_DEBUGGABLE, false);
        boolean noCreate = args.getBoolean(USER_SERVICE_NO_CREATE, false);
        boolean daemon = args.getBoolean(USER_SERVICE_DAEMON, true);
        boolean use32Bit = args.getBoolean(USER_SERVICE_USE_32_BIT, false);

        return new UserServiceOptions(
                component, tag, versionCode, processNameSuffix, debuggable, noCreate, daemon, use32Bit, true);
    }

    public static UserServiceOptions decodeForRemove(Bundle args) {
        ComponentName component =
                Objects.requireNonNull(args.getParcelable(USER_SERVICE_COMPONENT), "component is null");
        String tag = args.getString(USER_SERVICE_TAG);
        boolean remove = args.getBoolean(USER_SERVICE_REMOVE, true);

        return new UserServiceOptions(component, tag, 1, null, false, false, true, false, remove);
    }
}
