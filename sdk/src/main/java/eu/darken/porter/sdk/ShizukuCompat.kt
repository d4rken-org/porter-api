package eu.darken.porter.sdk

import androidx.annotation.VisibleForTesting

/**
 * Whether the optional `shizuku-compat` artifact is on the classpath.
 *
 * The container class is named as a string and never as a type: this object is what decides
 * whether the artifact is there, so it has to load where it is not.
 */
internal object ShizukuCompat {

    private const val CLASS_NAME = "moe.shizuku.api.BinderContainer"

    /** Probed once, on first use, rather than at class load of whoever asks. */
    private val present: Boolean by lazy { probe(CLASS_NAME) }

    /** What a test says the answer is, or null to read the classpath. */
    @Volatile
    private var presentForTest: Boolean? = null

    fun isPresent(): Boolean = presentForTest ?: present

    /**
     * Pins the answer, which [present] memoizes on first use and no test can steer twice.
     * Cleared by the test reset.
     */
    @VisibleForTesting
    fun setPresentForTest(present: Boolean?) {
        presentForTest = present
    }

    /** Takes the name so a test can reach the absent branch from a classpath that has the class. */
    @VisibleForTesting
    internal fun probe(name: String): Boolean = try {
        Class.forName(name, false, ShizukuCompat::class.java.classLoader)
        true
    } catch (e: ClassNotFoundException) {
        false
    } catch (e: LinkageError) {
        false
    }
}
