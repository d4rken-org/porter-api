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
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

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

    private static Bundle delivery(IBinder binder) {
        Bundle extras = new Bundle();
        extras.putBinder(DELIVERY_EXTRA_BINDER, binder);
        return extras;
    }

    private static Bundle shizukuDelivery(IBinder binder) {
        Bundle extras = new Bundle();
        ShizukuProtocolDelivery.INSTANCE.writeBinder(extras, binder);
        return extras;
    }

    /**
     * Both authorities answerable, as an app declaring the Shizuku provider alongside the built-in
     * one has them. A secondary process reaches whichever of the two the server delivered to.
     */
    private PorterShizukuApiProvider bothAuthorities() {
        ShadowContentResolver.registerProviderInternal(
                context.getPackageName() + PROVIDER_AUTHORITY_SUFFIX, provider);

        PorterShizukuApiProvider shizuku = new PorterShizukuApiProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = context.getPackageName() + ShizukuProtocolDelivery.INSTANCE.authoritySuffix();
        info.exported = true;
        info.multiprocess = false;
        shizuku.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, shizuku);
        return shizuku;
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

    @Test
    public void aSecondaryProcessFindsASessionOnTheShizukuAuthority() {
        PorterShizukuApiProvider shizuku = bothAuthorities();
        FakeShizukuService fake = new FakeShizukuService();
        shizuku.call(DELIVERY_METHOD_SEND_BINDER, null, shizukuDelivery(fake));

        assertTrue(PorterApiProvider.fetchBinderFromProvider(context));

        assertSame(fake, Porter.getBinder());
        assertEquals(PorterBackend.SHIZUKU, PorterSession.currentBackend());
    }

    @Test
    public void aSecondaryProcessStillFindsASessionOnPortersAuthority() {
        bothAuthorities();
        FakePorterService fake = new FakePorterService();
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake));

        assertTrue(PorterApiProvider.fetchBinderFromProvider(context));

        assertSame(fake, Porter.getBinder());
        assertEquals(PorterBackend.PORTER, PorterSession.currentBackend());
    }

    /**
     * The liveness check runs against a binder the session already resolved. A replacement that
     * lands while that check is in flight must not become the answer.
     */
    @Test
    public void aReplacementLandingDuringTheLivenessCheckIsNotTheAnswer() {
        class ReplacingPing extends FakePorterService {
            FakePorterService replacement;

            @Override
            public boolean pingBinder() {
                FakePorterService next = replacement;
                if (next != null) {
                    replacement = null;
                    Porter.onBinderReceived(next, context.getPackageName());
                }
                return true;
            }
        }

        ReplacingPing first = new ReplacingPing();
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(first));
        first.replacement = new FakePorterService();

        Bundle reply = provider.call(DELIVERY_METHOD_GET_BINDER, null, new Bundle());

        assertNotNull(reply);
        assertSame(first, reply.getBinder(DELIVERY_EXTRA_BINDER));
    }
}
