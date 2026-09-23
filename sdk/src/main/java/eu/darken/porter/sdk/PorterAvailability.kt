package eu.darken.porter.sdk

/**
 * How far away the manager is, from a binder in hand to nothing installed at all.
 *
 * `packageName` is the package that declares the backend's permission on this device, found by that
 * permission rather than by a package name, so a renamed fork is found too. It names the manager to
 * show or launch; it does not prove that package served the binder.
 */
public sealed interface PorterAvailability {

    /** No installed package declares the permission of a backend this app can use. */
    public data object NotInstalled : PorterAvailability

    /**
     * [packageName] declares [backend]'s permission and is not a manager this SDK knows. Do not
     * present it as the manager or launch it without your own verification.
     */
    public class InstalledUnrecognized internal constructor(
        public val backend: PorterBackend,
        public val packageName: String,
    ) : PorterAvailability {

        override fun equals(other: Any?): Boolean =
            other is InstalledUnrecognized && other.backend == backend && other.packageName == packageName

        override fun hashCode(): Int = 31 * backend.hashCode() + packageName.hashCode()

        override fun toString(): String = "InstalledUnrecognized($backend, $packageName)"
    }

    /** The manager [packageName] is installed for [backend]; this process has no binder from it. */
    public class InstalledNotConnected internal constructor(
        public val backend: PorterBackend,
        public val packageName: String,
    ) : PorterAvailability {

        override fun equals(other: Any?): Boolean =
            other is InstalledNotConnected && other.backend == backend && other.packageName == packageName

        override fun hashCode(): Int = 31 * backend.hashCode() + packageName.hashCode()

        override fun toString(): String = "InstalledNotConnected($backend, $packageName)"
    }

    /**
     * A binder arrived and answers, and the two sides do not speak a common protocol version.
     * [incompatibility] says which side is too old. [packageName] is null where no package declares
     * the permission any more, as when the manager was uninstalled while its server kept running.
     */
    public class Incompatible internal constructor(
        public val incompatibility: PorterIncompatibility,
        public val packageName: String?,
    ) : PorterAvailability {

        override fun equals(other: Any?): Boolean =
            other is Incompatible && other.incompatibility == incompatibility && other.packageName == packageName

        override fun hashCode(): Int = 31 * incompatibility.hashCode() + packageName.hashCode()

        override fun toString(): String = "Incompatible($incompatibility, $packageName)"
    }

    /**
     * A binder is held and answers, on [backend]. [packageName] is null where no package declares
     * the permission any more, as when the manager was uninstalled while its server kept running.
     */
    public class Connected internal constructor(
        public val backend: PorterBackend,
        public val packageName: String?,
    ) : PorterAvailability {

        override fun equals(other: Any?): Boolean =
            other is Connected && other.backend == backend && other.packageName == packageName

        override fun hashCode(): Int = 31 * backend.hashCode() + packageName.hashCode()

        override fun toString(): String = "Connected($backend, $packageName)"
    }
}
