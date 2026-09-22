package eu.darken.porter.sdk

import android.os.Bundle
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
 * How [UserServiceArgs] is written in the Porter server's key space. Reachable from the server's
 * own tests, which decode what this writes.
 */
internal object PorterUserServiceCodec {

    /** @param noCreate marks the service "do not start it", as a peek asks for */
    fun encodeUserService(args: UserServiceArgs, noCreate: Boolean): Bundle = Bundle().apply {
        putParcelable(USER_SERVICE_COMPONENT, args.componentName)
        putBoolean(USER_SERVICE_DEBUGGABLE, args.debuggable)
        putInt(USER_SERVICE_VERSION_CODE, args.version)
        putBoolean(USER_SERVICE_DAEMON, args.daemon)
        putBoolean(USER_SERVICE_USE_32_BIT, false)
        putString(USER_SERVICE_PROCESS_NAME_SUFFIX, args.processNameSuffix)
        if (args.tag != null) {
            putString(USER_SERVICE_TAG, args.tag)
        }
        if (noCreate) {
            putBoolean(USER_SERVICE_NO_CREATE, true)
        }
    }

    fun encodeUserServiceRemoval(args: UserServiceArgs, remove: Boolean): Bundle = Bundle().apply {
        putParcelable(USER_SERVICE_COMPONENT, args.componentName)
        if (args.tag != null) {
            putString(USER_SERVICE_TAG, args.tag)
        }
        putBoolean(USER_SERVICE_REMOVE, remove)
    }
}
