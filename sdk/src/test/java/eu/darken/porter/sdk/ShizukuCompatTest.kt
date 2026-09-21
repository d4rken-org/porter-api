package eu.darken.porter.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the SDK decides whether the optional compatibility artifact is there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class ShizukuCompatTest {

    @Test
    fun aClassThatIsNotOnTheClasspathIsReportedAbsent() {
        assertFalse(ShizukuCompat.probe("moe.shizuku.api.NoSuchBinderContainer"))
    }

    @Test
    fun theCompatibilityClassIsReportedPresent() {
        assertTrue(ShizukuCompat.probe("moe.shizuku.api.BinderContainer"))
    }

    @Test
    fun theArtifactIsOnTheTestClasspath() {
        assertTrue(ShizukuCompat.isPresent())
    }
}
