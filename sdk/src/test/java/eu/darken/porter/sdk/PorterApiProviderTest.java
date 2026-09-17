package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER;
import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER;
import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER;
import static eu.darken.porter.protocol.PorterProtocol.PROVIDER_AUTHORITY_SUFFIX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Bundle;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The receiving half of binder delivery: what the provider hands to {@link Porter} and back out. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterApiProviderTest {

    private Context context;
    private PorterApiProvider provider;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        provider = attached(new PorterApiProvider(), true, false);
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    private PorterApiProvider attached(PorterApiProvider provider, boolean exported, boolean multiprocess) {
        ProviderInfo info = new ProviderInfo();
        info.authority = context.getPackageName() + PROVIDER_AUTHORITY_SUFFIX;
        info.exported = exported;
        info.multiprocess = multiprocess;
        provider.attachInfo(context, info);
        return provider;
    }

    private static Bundle delivery(android.os.IBinder binder) {
        Bundle extras = new Bundle();
        extras.putBinder(DELIVERY_EXTRA_BINDER, binder);
        return extras;
    }

    @Test
    public void sendBinderDeliversToPorter() {
        FakePorterService fake = new FakePorterService();

        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake));

        assertTrue(Porter.pingBinder());
        assertSame(fake, Porter.getBinder());
        assertEquals(1, fake.attachCount);
    }

    @Test
    public void aSecondSendBinderIsIgnoredWhileTheFirstIsAlive() {
        FakePorterService fake = new FakePorterService();
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake));

        Bundle reply = provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(new FakePorterService()));

        assertNotNull(reply);
        assertTrue(reply.isEmpty());
        assertSame(fake, Porter.getBinder());
        assertEquals(1, fake.attachCount);
    }

    @Test
    public void sendBinderWithoutTheExtraDeliversNothing() {
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, new Bundle());

        assertFalse(Porter.pingBinder());
    }

    @Test
    public void getBinderAnswersOnlyWhileABinderIsHeld() {
        assertNull(provider.call(DELIVERY_METHOD_GET_BINDER, null, new Bundle()));

        FakePorterService fake = new FakePorterService();
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake));

        Bundle reply = provider.call(DELIVERY_METHOD_GET_BINDER, null, new Bundle());

        assertNotNull(reply);
        assertSame(fake, reply.getBinder(DELIVERY_EXTRA_BINDER));
    }

    @Test
    public void sendBinderTagsTheSessionWithPortersBackend() {
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(new FakePorterService()));

        assertEquals(PorterBackend.PORTER, PorterSession.currentBackend());
    }

    @Test
    public void theProviderDeclarationIsEnforced() {
        assertThrows(IllegalStateException.class, () -> attached(new PorterApiProvider(), true, true));
        assertThrows(IllegalStateException.class, () -> attached(new PorterApiProvider(), false, false));
    }
}
