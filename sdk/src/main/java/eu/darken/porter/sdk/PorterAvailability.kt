package eu.darken.porter.sdk

/** How far away Porter is, from a binder in hand to nothing installed at all. */
public sealed interface PorterAvailability {

    /** No installed package declares the permission of the selected backend. */
    public data object NotInstalled : PorterAvailability

    /** The permission belongs to a package this SDK does not recognize as the manager. */
    public data object InstalledUnrecognized : PorterAvailability

    /** The manager is installed; this process has no binder from it. */
    public data object InstalledNotConnected : PorterAvailability

    /**
     * A binder arrived and answers, and the two sides do not speak a common protocol version.
     * [incompatibility] says which side is too old.
     */
    public class Incompatible internal constructor(public val incompatibility: PorterIncompatibility) : PorterAvailability {

        override fun equals(other: Any?): Boolean = other is Incompatible && other.incompatibility == incompatibility

        override fun hashCode(): Int = incompatibility.hashCode()

        override fun toString(): String = "Incompatible($incompatibility)"
    }

    /** A binder is held and answers. */
    public data object Connected : PorterAvailability
}
