package eu.darken.porter.sdk.extras;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.os.Binder;
import android.os.IBinder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicInteger;

/** The reflective lookup, the cache in front of it, and what each failure becomes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterSystemServicesTest {

    /** Registered by Robolectric's ShadowServiceManager, unlike "package". */
    private static final String REGISTERED = "power";
    private static final String UNREGISTERED = "eu.darken.porter.no.such.service";

    @After
    public void teardown() {
        PorterSystemServices.lookup = PorterSystemServices.reflectiveLookup();
        PorterSystemServices.clearCacheForTest();
    }

    @Test
    public void theHiddenClassAndMethodResolve() {
        assertNotNull(PorterSystemServices.reflectiveLookup());
    }

    @Test
    public void theRealLookupAnswersForAKnownService() {
        assertNotNull(PorterSystemServices.getSystemService(REGISTERED));
    }

    @Test
    public void theRealLookupAnswersNullForAnUnknownService() {
        assertNull(PorterSystemServices.getSystemService(UNREGISTERED));
    }

    @Test
    public void anUnreachableLookupThrows() {
        PorterSystemServices.lookup = null;

        assertThrows(IllegalStateException.class,
                () -> PorterSystemServices.getSystemService(REGISTERED));
    }

    @Test
    public void theBinderTheLookupAnswersIsReturned() {
        IBinder binder = new Binder();
        PorterSystemServices.lookup = name -> binder;

        assertSame(binder, PorterSystemServices.getSystemService(REGISTERED));
    }

    @Test
    public void aSecondCallIsServedFromTheCache() {
        IBinder binder = new Binder();
        AtomicInteger calls = new AtomicInteger();
        PorterSystemServices.lookup = name -> {
            calls.incrementAndGet();
            return binder;
        };

        assertSame(binder, PorterSystemServices.getSystemService(REGISTERED));
        assertSame(binder, PorterSystemServices.getSystemService(REGISTERED));
        assertEquals(1, calls.get());
    }

    @Test
    public void aMissingServiceIsAskedAboutAgain() {
        IBinder binder = new Binder();
        AtomicInteger calls = new AtomicInteger();
        PorterSystemServices.lookup = name -> calls.incrementAndGet() == 1 ? null : binder;

        assertNull(PorterSystemServices.getSystemService(REGISTERED));
        assertSame(binder, PorterSystemServices.getSystemService(REGISTERED));
    }

    @Test
    public void aFailedLookupThrowsCarryingItsCause() {
        ReflectiveOperationException failure = new ReflectiveOperationException("denied");
        PorterSystemServices.lookup = name -> {
            throw failure;
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PorterSystemServices.getSystemService(REGISTERED));
        assertSame(failure, thrown.getCause());
    }
}
