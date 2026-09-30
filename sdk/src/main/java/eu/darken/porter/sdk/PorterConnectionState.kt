package eu.darken.porter.sdk

/**
 * What this process holds from the servers delivered to it, as [Porter.state] publishes it. Whether
 * a manager is installed at all is [Porter.availability]'s question.
 */
public sealed interface PorterConnectionState {

    /** No connection is held, and no server this process refused is still running. */
    public data object Disconnected : PorterConnectionState

    /** [connection] is held; it is the one [Porter.connection] publishes. Equal only for the same connection. */
    public class Connected internal constructor(
        public val connection: PorterConnection,
    ) : PorterConnectionState {

        override fun equals(other: Any?): Boolean = other is Connected && other.connection === connection

        override fun hashCode(): Int = System.identityHashCode(connection)

        override fun toString(): String = "Connected(${connection.generation})"
    }

    /**
     * A delivery was refused because its server shares no protocol version with this SDK, that
     * server still runs, and no connection is held. [incompatibility] says which side has to move.
     */
    public class Incompatible internal constructor(
        public val incompatibility: PorterIncompatibility,
    ) : PorterConnectionState {

        override fun equals(other: Any?): Boolean = other is Incompatible && other.incompatibility == incompatibility

        override fun hashCode(): Int = incompatibility.hashCode()

        override fun toString(): String = "Incompatible($incompatibility)"
    }
}
