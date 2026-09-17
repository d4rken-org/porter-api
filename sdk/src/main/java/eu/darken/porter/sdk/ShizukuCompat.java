package eu.darken.porter.sdk;

import androidx.annotation.VisibleForTesting;

/**
 * Whether the optional {@code shizuku-compat} artifact is on the classpath.
 *
 * <p>The container class is named as a string and never as a type: this class is what decides
 * whether the artifact is there, so it has to load where it is not.
 */
final class ShizukuCompat {

    private static final String CLASS_NAME = "moe.shizuku.api.BinderContainer";

    private ShizukuCompat() {
    }

    /** Probed once, on first use, rather than at class load of whoever asks. */
    private static final class Holder {
        static final boolean PRESENT = probe(CLASS_NAME);
    }

    static boolean isPresent() {
        return Holder.PRESENT;
    }

    /** Takes the name so a test can reach the absent branch from a classpath that has the class. */
    @VisibleForTesting
    static boolean probe(String name) {
        try {
            Class.forName(name, false, ShizukuCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
