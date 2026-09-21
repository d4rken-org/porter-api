package eu.darken.porter.endpoint

import android.content.ComponentName
import android.os.Bundle
import eu.darken.porter.core.UserServiceOptions
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE

/**
 * Reads the Porter option Bundle. Bind and remove have their own decoder because each reads only
 * the keys its own operation reads, and the component key first, so a Bundle that fails to unparcel
 * fails at the same point for both.
 */
object PorterUserServiceOptions {

    @Suppress("DEPRECATION")
    fun decodeForBind(args: Bundle): UserServiceOptions {
        val component: ComponentName =
            (args.getParcelable<ComponentName>(USER_SERVICE_COMPONENT) ?: throw NullPointerException("component is null"))
        (component.className ?: throw NullPointerException("class is null"))
        val versionCode = args.getInt(USER_SERVICE_VERSION_CODE, 1)
        val tag = args.getString(USER_SERVICE_TAG)
        val processNameSuffix = args.getString(USER_SERVICE_PROCESS_NAME_SUFFIX)
        val debuggable = args.getBoolean(USER_SERVICE_DEBUGGABLE, false)
        val noCreate = args.getBoolean(USER_SERVICE_NO_CREATE, false)
        val daemon = args.getBoolean(USER_SERVICE_DAEMON, true)
        val use32Bit = args.getBoolean(USER_SERVICE_USE_32_BIT, false)

        return UserServiceOptions(
            component, tag, versionCode, processNameSuffix, debuggable, noCreate, daemon, use32Bit, true,
        )
    }

    @Suppress("DEPRECATION")
    fun decodeForRemove(args: Bundle): UserServiceOptions {
        val component: ComponentName =
            (args.getParcelable<ComponentName>(USER_SERVICE_COMPONENT) ?: throw NullPointerException("component is null"))
        val tag = args.getString(USER_SERVICE_TAG)
        val remove = args.getBoolean(USER_SERVICE_REMOVE, true)

        return UserServiceOptions(component, tag, 1, null, false, false, true, false, remove)
    }
}
