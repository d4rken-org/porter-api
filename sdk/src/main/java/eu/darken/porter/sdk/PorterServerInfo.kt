package eu.darken.porter.sdk

/** What the server on one connection reported about itself when that connection attached. */
public data class PorterServerInfo(
    /** Which protocol the reported numbers are on the scale of. */
    val backend: PorterBackend,
    /** The version this connection's server reported at attach; 0 when it reported none. */
    val version: Int,
    /** The Shizuku patch level, where the backend reports one; null where it has none. */
    val patchVersion: Int?,
)
