package eu.darken.porter.sdk

import eu.darken.porter.protocol.PorterProtocol

/**
 * Why the last delivered binder was refused: the protocol versions of both sides and the oldest
 * peer each still accepts, on the scale of [backend]. [serverTooOld] and [clientTooOld] say which
 * side has to move; a server that reported no version at all reads as too old.
 */
public class PorterIncompatibility internal constructor(
    /** The wire the versions below are numbered on. */
    public val backend: PorterBackend,
    /** What the server reported, 0 when it reported nothing. */
    public val serverVersion: Int,
    /** The oldest client the server accepts, 0 when it reported nothing or its wire names none. */
    public val serverMinVersion: Int,
) {

    /** What this SDK speaks on [backend]. */
    public val clientVersion: Int
        get() = when (backend) {
            PorterBackend.PORTER -> PorterProtocol.VERSION
            PorterBackend.SHIZUKU -> ShizukuProtocol.CLIENT_API_VERSION
        }

    /** The oldest server this SDK still speaks to on [backend]. */
    public val clientMinVersion: Int
        get() = when (backend) {
            PorterBackend.PORTER -> PorterProtocolWire.MIN_SERVER_VERSION
            PorterBackend.SHIZUKU -> ShizukuProtocol.MINIMUM_VERSION
        }

    /** The user has to update the server's manager. */
    public val serverTooOld: Boolean get() = serverVersion < clientMinVersion

    /** This app has to ship a newer SDK. */
    public val clientTooOld: Boolean get() = serverMinVersion > clientVersion

    override fun equals(other: Any?): Boolean =
        other is PorterIncompatibility &&
            other.backend == backend &&
            other.serverVersion == serverVersion &&
            other.serverMinVersion == serverMinVersion

    override fun hashCode(): Int = 31 * (31 * backend.hashCode() + serverVersion) + serverMinVersion

    override fun toString(): String =
        "PorterIncompatibility(backend=$backend, server=$serverVersion, serverMin=$serverMinVersion, " +
            "client=$clientVersion, clientMin=$clientMinVersion)"
}
