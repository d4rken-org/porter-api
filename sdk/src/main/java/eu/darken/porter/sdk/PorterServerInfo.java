package eu.darken.porter.sdk;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** What the server on one connection reported about itself when that connection attached. */
public final class PorterServerInfo {

    /** Which protocol the reported numbers are on the scale of. */
    @NonNull
    public final PorterBackend backend;

    /** The version this connection's server reported at attach; 0 when it reported none. */
    public final int version;

    /** The Shizuku patch level, where the backend reports one; null where it has none. */
    @Nullable
    public final Integer patchVersion;

    PorterServerInfo(@NonNull PorterBackend backend, int version, @Nullable Integer patchVersion) {
        this.backend = backend;
        this.version = version;
        this.patchVersion = patchVersion;
    }
}
