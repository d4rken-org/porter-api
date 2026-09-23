package eu.darken.porter.sdk

import android.content.Context
import androidx.annotation.VisibleForTesting

/**
 * Whether the optional `shizuku-compat` artifact is on the classpath.
 *
 * The container class is named as a string and never as a type: this object is what decides
 * whether the artifact is there, so it has to load where it is not.
 */
internal object ShizukuCompat {

    private const val CLASS_NAME = "moe.shizuku.api.BinderContainer"

    /** The authority a Shizuku server delivers its binder to, after the package name. */
    internal const val AUTHORITY_SUFFIX = ".shizuku"

    /** Probed once, on first use, rather than at class load of whoever asks. */
    private val present: Boolean by lazy { probe(CLASS_NAME) }

    /** What a test says the answer is, or null to read the classpath. */
    @Volatile
    private var presentForTest: Boolean? = null

    fun isPresent(): Boolean = presentForTest ?: present

    /**
     * Whether a Shizuku server's binder can reach this SDK: the container class is on the classpath
     * and this app declares [PorterShizukuApiProvider] at the authority the server delivers to. An
     * app that keeps `dev.rikka.shizuku:provider` has the class and gives the authority to that
     * library's provider, so the binder never arrives here.
     */
    fun canReceive(context: Context): Boolean = isPresent() && declaresProvider(context)

    /** Read once: the manifest does not change while the process runs. */
    @Volatile
    private var declared: Boolean? = null

    private fun declaresProvider(context: Context): Boolean {
        declared?.let { return it }
        val info = context.packageManager.resolveContentProvider(context.packageName + AUTHORITY_SUFFIX, 0)
        return (info?.packageName == context.packageName && info.name == PorterShizukuApiProvider::class.java.name)
            .also { declared = it }
    }

    /**
     * Pins the answer, which [present] memoizes on first use and no test can steer twice.
     * Cleared by the test reset.
     */
    @VisibleForTesting
    fun setPresentForTest(present: Boolean?) {
        presentForTest = present
    }

    /** Forgets the provider lookup, so a test can declare the provider and have it read. */
    @VisibleForTesting
    fun resetForTest() {
        presentForTest = null
        declared = null
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
