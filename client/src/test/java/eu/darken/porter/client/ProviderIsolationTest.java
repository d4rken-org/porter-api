package eu.darken.porter.client;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.IBinder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;
import rikka.sui.Sui;
import moe.shizuku.api.BinderContainer;
import java.lang.reflect.Field;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ProviderIsolationTest {
    private static final String EXTRA = "moe.shizuku.privileged.api.intent.extra.BINDER";
    private Context context;
    private MockedStatic<Shizuku> shizuku;
    private MockedStatic<Sui> sui;
    private IBinder binder;

    @Before public void setup() throws Exception {
        for (String name : new String[]{"activeBackend", "porterOnly"}) {
            Field field = PorterClient.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, null);
        }
        Field enableSui = ShizukuProvider.class.getDeclaredField("enableSuiInitialization");
        enableSui.setAccessible(true);
        enableSui.set(null, true);
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("porter.client", 0).edit().clear().commit();
        shizuku = mockStatic(Shizuku.class);
        sui = mockStatic(Sui.class);
        binder = mock(IBinder.class);
        when(binder.pingBinder()).thenReturn(true);
    }

    @After public void close() { sui.close(); shizuku.close(); }

    private <T extends ContentProvider> T attach(T provider, String suffix) {
        ProviderInfo info = new ProviderInfo();
        info.authority = context.getPackageName() + suffix;
        info.exported = true;
        info.readPermission = "android.permission.INTERACT_ACROSS_USERS_FULL";
        provider.attachInfo(context, info);
        return provider;
    }

    private Bundle incoming() {
        Bundle extras = new Bundle();
        extras.putParcelable(EXTRA, new BinderContainer(binder));
        return extras;
    }

    @Test public void porterSelectionRejectsShizukuDeliveryAndAcceptsPorterDelivery() {
        PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER);
        SelectedShizukuProvider legacy = attach(new SelectedShizukuProvider(), ".shizuku");
        PorterProvider porter = attach(new PorterProvider(), ".porter");
        assertNull(legacy.call(ShizukuProvider.METHOD_SEND_BINDER, null, incoming()));
        shizuku.verifyNoInteractions();
        assertNotNull(porter.call(ShizukuProvider.METHOD_SEND_BINDER, null, incoming()));
        shizuku.verify(() -> Shizuku.onBinderReceived(binder, context.getPackageName()));
        sui.verify(() -> Sui.init(anyString()), never());
    }

    @Test public void shizukuSelectionRejectsPorterDeliveryAndAcceptsShizukuDelivery() {
        PorterClient.setBackendForNextProcess(context, PorterClient.Backend.SHIZUKU);
        PorterProvider porter = attach(new PorterProvider(), ".porter");
        SelectedShizukuProvider legacy = attach(new SelectedShizukuProvider(), ".shizuku");
        assertNull(porter.call(ShizukuProvider.METHOD_SEND_BINDER, null, incoming()));
        shizuku.verifyNoInteractions();
        assertNotNull(legacy.call(ShizukuProvider.METHOD_SEND_BINDER, null, incoming()));
        shizuku.verify(() -> Shizuku.onBinderReceived(binder, context.getPackageName()));
    }

    @Test public void preferenceChangeCannotAdmitOtherBackendIntoRunningProcess() {
        PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER);
        PorterProvider porter = attach(new PorterProvider(), ".porter");
        SelectedShizukuProvider legacy = attach(new SelectedShizukuProvider(), ".shizuku");
        PorterClient.setBackendForNextProcess(context, PorterClient.Backend.SHIZUKU);
        assertNull(legacy.call(ShizukuProvider.METHOD_SEND_BINDER, null, incoming()));
        shizuku.verifyNoInteractions();
        assertNotNull(porter.call(ShizukuProvider.METHOD_SEND_BINDER, null, incoming()));
        shizuku.verify(() -> Shizuku.onBinderReceived(binder, context.getPackageName()));
    }

    @Test public void porterBinderCanStillBeSharedThroughLegacyMultiprocessEndpoint() {
        PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER);
        SelectedShizukuProvider legacy = attach(new SelectedShizukuProvider(), ".shizuku");
        shizuku.when(Shizuku::getBinder).thenReturn(binder);
        Bundle reply = legacy.call(ShizukuProvider.METHOD_GET_BINDER, null, new Bundle());
        assertNotNull(reply);
        assertSame(binder, ((BinderContainer) reply.getParcelable(EXTRA)).binder);
        shizuku.verify(() -> Shizuku.onBinderReceived(any(), anyString()), never());
    }

    @Test public void porterProviderFirstAlsoSuppressesAutomaticSuiInitialization() {
        PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER);
        attach(new PorterProvider(), ".porter");
        attach(new SelectedShizukuProvider(), ".shizuku");
        sui.verify(() -> Sui.init(anyString()), never());
    }

    @Test public void unavailableBinderIsNotShared() {
        PorterClient.setBackendForNextProcess(context, PorterClient.Backend.PORTER);
        SelectedShizukuProvider legacy = attach(new SelectedShizukuProvider(), ".shizuku");
        shizuku.when(Shizuku::getBinder).thenReturn(binder);
        when(binder.pingBinder()).thenReturn(false);
        assertNull(legacy.call(ShizukuProvider.METHOD_GET_BINDER, null, new Bundle()));
    }
}
