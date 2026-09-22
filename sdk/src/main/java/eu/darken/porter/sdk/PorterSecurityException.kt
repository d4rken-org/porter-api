package eu.darken.porter.sdk

/**
 * The server refused a call, with its [SecurityException] as the cause. Usually this app has not
 * been granted access; the message names the check that refused it.
 */
public class PorterSecurityException internal constructor(cause: SecurityException) :
    PorterException(cause.message, cause)
