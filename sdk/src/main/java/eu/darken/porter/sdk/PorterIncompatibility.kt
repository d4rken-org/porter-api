package eu.darken.porter.sdk

import eu.darken.porter.protocol.PorterProtocol

/**
 * Why the last delivered Porter binder was refused: the protocol versions of both sides and the
 * oldest peer each still accepts. [serverTooOld] and [clientTooOld] say which side has to move; a
 * server that reported no version at all reads as too old.
 */
public class PorterIncompatibility internal constructor(
    /** What the server reported, 0 when it reported nothing. */
    public val serverVersion: Int,
    /** The oldest client the server accepts, 0 when it reported nothing. */
    public val serverMinVersion: Int,
) {

    /** What this SDK speaks. */
    public val clientVersion: Int get() = PorterProtocol.VERSION

    /** The oldest server this SDK still speaks to. */
    public val clientMinVersion: Int get() = PorterProtocolWire.MIN_SERVER_VERSION

    /** The user has to update Porter. */
    public val serverTooOld: Boolean get() = serverVersion < clientMinVersion

    /** This app has to ship a newer SDK. */
    public val clientTooOld: Boolean get() = serverMinVersion > clientVersion

    override fun equals(other: Any?): Boolean =
        other is PorterIncompatibility && other.serverVersion == serverVersion && other.serverMinVersion == serverMinVersion

    override fun hashCode(): Int = 31 * serverVersion + serverMinVersion

    override fun toString(): String =
        "PorterIncompatibility(server=$serverVersion, serverMin=$serverMinVersion, client=$clientVersion, clientMin=$clientMinVersion)"
}
