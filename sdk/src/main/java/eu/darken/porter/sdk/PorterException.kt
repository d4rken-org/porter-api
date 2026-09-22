package eu.darken.porter.sdk

/** Every failure the SDK reports from a call on a [PorterConnection]. */
public sealed class PorterException(message: String?, cause: Throwable?) : RuntimeException(message, cause)
