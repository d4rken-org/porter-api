package eu.darken.porter.sdk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** How the SDK decides whether the optional compatibility artifact is there. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuCompatTest {

    @Test
    public void aClassThatIsNotOnTheClasspathIsReportedAbsent() {
        assertFalse(ShizukuCompat.probe("moe.shizuku.api.NoSuchBinderContainer"));
    }

    @Test
    public void theCompatibilityClassIsReportedPresent() {
        assertTrue(ShizukuCompat.probe("moe.shizuku.api.BinderContainer"));
    }

    @Test
    public void theArtifactIsOnTheTestClasspath() {
        assertTrue(ShizukuCompat.isPresent());
    }
}
