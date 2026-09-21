package eu.darken.porter.sdk

import android.os.Bundle
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_COMPONENT
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DAEMON
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_DEBUGGABLE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_NO_CREATE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_PROCESS_NAME
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_REMOVE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TAG
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_VERSION_CODE

/** How [UserServiceArgs] is written in a Shizuku server's key space. */
internal object ShizukuUserServiceCodec {

    /** @param noCreate marks the service "do not start it", as a peek asks for */
    fun encodeUserService(args: UserServiceArgs, noCreate: Boolean): Bundle = Bundle().apply {
        putParcelable(USER_SERVICE_ARG_COMPONENT, args.componentName)
        putBoolean(USER_SERVICE_ARG_DEBUGGABLE, args.debuggable)
        putInt(USER_SERVICE_ARG_VERSION_CODE, args.version)
        putBoolean(USER_SERVICE_ARG_DAEMON, args.daemon)
        putBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, false)
        putString(USER_SERVICE_ARG_PROCESS_NAME, args.processNameSuffix)
        if (args.tag != null) {
            putString(USER_SERVICE_ARG_TAG, args.tag)
        }
        if (noCreate) {
            putBoolean(USER_SERVICE_ARG_NO_CREATE, true)
        }
    }

    fun encodeUserServiceRemoval(args: UserServiceArgs, remove: Boolean): Bundle = Bundle().apply {
        putParcelable(USER_SERVICE_ARG_COMPONENT, args.componentName)
        if (args.tag != null) {
            putString(USER_SERVICE_ARG_TAG, args.tag)
        }
        putBoolean(USER_SERVICE_ARG_REMOVE, remove)
    }
}
