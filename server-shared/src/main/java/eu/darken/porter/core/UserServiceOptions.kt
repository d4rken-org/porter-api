package eu.darken.porter.core

import android.content.ComponentName

/**
 * What a caller asked for when binding or unbinding a user service.
 *
 * Only [component] is validated here; everything a caller can get wrong is reported by the
 * manager, in the order the wire contract fixes.
 */
class UserServiceOptions(
    component: ComponentName?,
    val tag: String?,
    val versionCode: Int,
    val processNameSuffix: String?,
    val debuggable: Boolean,
    val noCreate: Boolean,
    val daemon: Boolean,
    val use32Bit: Boolean,
    val remove: Boolean,
) {

    val component: ComponentName = (component ?: throw NullPointerException("component is null"))

    fun packageName(): String = component.packageName

    fun className(): String = component.className

    /**
     * `10:eu.darken.porter.probe:ProbeService`, or the tag in place of the class name. The
     * caller's Android user leads, so each user's installation of a package gets its own service
     * process, which runs as the server's uid like every other.
     */
    fun key(userId: Int): String = userId.toString() + ":" + packageName() + ":" + (tag ?: className())
}
