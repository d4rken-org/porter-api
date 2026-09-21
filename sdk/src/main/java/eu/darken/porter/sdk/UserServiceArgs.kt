package eu.darken.porter.sdk

import android.content.ComponentName

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
     * process that bound it dies. Daemon by default.
     */
    val daemon: Boolean = true,
)
