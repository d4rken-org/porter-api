package rikka.shizuku.server.legacy

import android.content.ComponentName
import android.os.Bundle
import eu.darken.porter.core.UserServiceOptions
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DAEMON
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_REMOVE
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TAG
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_TOKEN
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS
import rikka.shizuku.ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE

/**
 * Reads the Shizuku option Bundle. Bind and remove have their own decoder because each reads only
 * the keys its own operation reads, and the component key first, so a Bundle that fails to unparcel
 * fails at the same point for both.
 */
object LegacyUserServiceOptions {

    @Suppress("DEPRECATION")
    fun decodeForBind(options: Bundle): UserServiceOptions {
        val component: ComponentName =
            (options.getParcelable(USER_SERVICE_ARG_COMPONENT) ?: throw NullPointerException("component is null"))
        val versionCode = options.getInt(USER_SERVICE_ARG_VERSION_CODE, 1)
        val tag = options.getString(USER_SERVICE_ARG_TAG)
        val processNameSuffix = options.getString(USER_SERVICE_ARG_PROCESS_NAME)
        val debuggable = options.getBoolean(USER_SERVICE_ARG_DEBUGGABLE, false)
        val noCreate = options.getBoolean(USER_SERVICE_ARG_NO_CREATE, false)
        val daemon = options.getBoolean(USER_SERVICE_ARG_DAEMON, true)
        val use32Bit = options.getBoolean(USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, false)

        return UserServiceOptions(
            component, tag, versionCode, processNameSuffix, debuggable, noCreate, daemon, use32Bit, true,
        )
    }

    @Suppress("DEPRECATION")
    fun decodeForRemove(options: Bundle): UserServiceOptions {
        val component: ComponentName =
            (options.getParcelable(USER_SERVICE_ARG_COMPONENT) ?: throw NullPointerException("component is null"))
        val tag = options.getString(USER_SERVICE_ARG_TAG)

        // API < 13.1.4 will not send USER_SERVICE_ARG_REMOVE, true by default
        var remove = true
        if (options.containsKey(USER_SERVICE_ARG_REMOVE)) {
            remove = options.getBoolean(USER_SERVICE_ARG_REMOVE)
        }

        return UserServiceOptions(component, tag, 1, null, false, false, true, false, remove)
    }

    fun decodeToken(options: Bundle): String =
        (options.getString(USER_SERVICE_ARG_TOKEN) ?: throw NullPointerException("token is null"))
}
