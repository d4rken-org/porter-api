package eu.darken.porter.sdk

/** How far away Porter is, from a binder in hand to nothing installed at all. */
public enum class PorterAvailability {
    /** No installed package declares the Porter permission. */
    NOT_INSTALLED,

    /** The permission belongs to a package this SDK does not recognize as the manager. */
    INSTALLED_UNRECOGNIZED,

    /** The manager is installed; this process has no binder from it. */
    INSTALLED_NOT_CONNECTED,

    /**
     * A binder arrived and answers, and the two sides do not speak a common protocol version.
     * [Porter.incompatibility] says which side is too old.
     */
    INCOMPATIBLE,

    /** A binder is held and answers. */
    CONNECTED,
}
