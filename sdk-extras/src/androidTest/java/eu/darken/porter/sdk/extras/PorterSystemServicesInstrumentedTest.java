package eu.darken.porter.sdk.extras;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Robolectric's ServiceManager is a shadow, so only a device says whether the platform's
 * non-SDK interface enforcement permits the lookup. Nothing here needs a Porter or Shizuku
 * server: getSystemService stays inside this process.
 */
@RunWith(AndroidJUnit4.class)
public class PorterSystemServicesInstrumentedTest {

    @Test
    public void theLookupResolvesOnADevice() {
        assertNotNull(PorterSystemServices.reflectiveLookup());
    }

    @Test
    public void aRealServiceAnswersWithABinder() {
        assertNotNull(PorterSystemServices.getSystemService("package"));
    }

    @Test
    public void anUnknownNameAnswersWithNull() {
        assertNull(PorterSystemServices.getSystemService("eu.darken.porter.no.such.service"));
    }
}
