package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER;
import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER;
import static eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import moe.shizuku.api.BinderContainer;

/** The Shizuku envelope at the Shizuku authority, and where this increment stops short of using it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterShizukuApiProviderTest {

    private static final String AUTHORITY_SUFFIX = ".shizuku";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    private Context context;
    private PorterShizukuApiProvider provider;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        provider = new PorterShizukuApiProvider();

        ProviderInfo info = new ProviderInfo();
        info.authority = context.getPackageName() + AUTHORITY_SUFFIX;
        info.exported = true;
        info.multiprocess = false;
        provider.attachInfo(context, info);
    }

    @After
    public void teardown() {
        Porter.resetForTest();
    }

    /**
     * Sends the extras the way the server does. A Bundle that never leaves the process hands the
     * same container back, so the CREATOR and the class loader would decide nothing.
     */
    private static Bundle throughAParcel(Bundle source) {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(source);
            parcel.setDataPosition(0);
            return parcel.readBundle();
        } finally {
            parcel.recycle();
        }
    }

    private static Bundle shizukuDelivery(IBinder binder) {
        Bundle extras = new Bundle();
        extras.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
        return throughAParcel(extras);
    }

    @Test
    public void theEnvelopeIsTheOneShizukuSends() {
        FakePorterService fake = new FakePorterService();

        assertEquals(AUTHORITY_SUFFIX, ShizukuProtocolDelivery.INSTANCE.authoritySuffix());
        assertSame(fake, ShizukuProtocolDelivery.INSTANCE.readBinder(shizukuDelivery(fake)));
    }

    @Test
    public void theWrittenEnvelopeReadsBackAsAContainer() {
        FakePorterService fake = new FakePorterService();

        Bundle reply = new Bundle();
        ShizukuProtocolDelivery.INSTANCE.writeBinder(reply, fake);
        Bundle sent = throughAParcel(reply);
        sent.setClassLoader(BinderContainer.class.getClassLoader());

        BinderContainer container = sent.getParcelable(EXTRA_BINDER);
        assertSame(fake, container.binder);
    }

    @Test
    public void aBinderDeliveredOnTheShizukuAuthorityIsNotPublished() {
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, shizukuDelivery(new FakePorterService()));

        assertFalse(Porter.pingBinder());
        assertNull(Porter.getBinder());
        assertNull(PorterSession.currentBackend());
    }

    @Test
    public void portersOwnEnvelopeIsIgnoredOnTheShizukuAuthority() {
        Bundle extras = new Bundle();
        extras.putBinder(DELIVERY_EXTRA_BINDER, new FakePorterService());

        provider.call(DELIVERY_METHOD_SEND_BINDER, null, extras);

        assertFalse(Porter.pingBinder());
        assertNull(Porter.getBinder());
    }

    @Test
    public void theBinderIsOnlyHandedOutForItsOwnBackend() {
        FakePorterService fake = new FakePorterService();
        Porter.onBinderReceived(fake, context.getPackageName());

        assertNull(PorterSession.binderFor(PorterBackend.SHIZUKU));
        assertSame(fake, PorterSession.binderFor(PorterBackend.PORTER));
    }

    @Test
    public void getBinderRefusesWhileTheLiveSessionIsPorters() {
        Porter.onBinderReceived(new FakePorterService(), context.getPackageName());
        assertTrue(Porter.pingBinder());

        assertNull(provider.call(DELIVERY_METHOD_GET_BINDER, null, new Bundle()));
    }
}
