package eu.darken.porter.sdk

import android.content.ComponentName
import eu.darken.porter.protocol.PorterProtocol

/**
 * Which user service to reach and how the server should run it, as `Intent` is to a bound service.
 */
public data class UserServiceArgs(
    val componentName: ComponentName,
    /** The final process name is `com.example:suffix`. */
    val processNameSuffix: String,
    /** Distinguishes services of one app. Set a stable tag if the service class is obfuscated. */
    val tag: String? = null,
    /** Use a new version code when the service code changes, so the server recreates it. */
    val version: Int = 1,
    /** A debuggable service process is listed when "Show all processes" is enabled. */
    val debuggable: Boolean = false,
    /**
     * A daemon service runs until it is stopped explicitly; a non-daemon one is stopped when the
     * process that bound it dies.
     */
    val daemon: Boolean = false,
) {

    public companion object {
        /**
         * The transaction code the server sends a user service to ask it to shut down, on either
         * backend: `16777114` as an aidl method id, which the generated stub offsets by one.
         */
        public const val TRANSACTION_DESTROY: Int = PorterProtocol.USER_SERVICE_TRANSACTION_destroy
    }
}
