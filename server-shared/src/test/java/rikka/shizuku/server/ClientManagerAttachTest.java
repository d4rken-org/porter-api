package rikka.shizuku.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.application;
import static rikka.shizuku.server.ServerTestSupport.entry;

import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ClientCallback;
import moe.shizuku.server.IShizukuApplication;
import rikka.shizuku.server.legacy.LegacyClientCallback;

/** The neutral attach entry point of {@link ClientManager}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ClientManagerAttachTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final int HANDED_UID = 10200;
    private static final int HANDED_PID = 45678;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;

    @Before
    public void setup() {
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        // Whatever Binder would report is a different caller, so a fallback to it would show up.
        ShadowBinder.setCallingUid(HANDED_UID + 1);
        ShadowBinder.setCallingPid(HANDED_PID + 1);
    }

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    private static ClientCallback callback(IBinder binder) {
        return new ClientCallback() {
            @Override
            public IBinder asBinder() {
                return binder;
            }

            @Override
            public void onPermissionResult(int requestCode, boolean allowed) {
            }

            @Override
            public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) {
            }
        };
    }

    @Test
    public void attachUsesTheHandedIdentityAndCallback() throws Exception {
        when(config.find(HANDED_UID)).thenReturn(entry(true, false));
        IBinder binder = mock(IBinder.class);
        ClientCallback callback = callback(binder);

        ClientRecord record = clients.attach(new CallerIdentity(HANDED_UID, HANDED_PID), callback, PACKAGE, 13);

        assertNotNull(record);
        assertSame(record, clients.findClient(HANDED_UID, HANDED_PID));
        assertNull(clients.findClient(HANDED_UID + 1, HANDED_PID + 1));
        assertNull(record.client);
        assertSame(callback, record.callback);
        assertTrue(record.allowed);

        ArgumentCaptor<IBinder.DeathRecipient> recipient =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(binder).linkToDeath(recipient.capture(), eq(0));
        recipient.getValue().binderDied();

        assertNull(clients.findClient(HANDED_UID, HANDED_PID));
    }

    @Test
    public void attachThroughTheLegacyOverloadKeepsTheApplication() {
        IShizukuApplication application = application(mock(IBinder.class));

        ClientRecord record = clients.addClient(HANDED_UID, HANDED_PID, application, PACKAGE, 13);

        assertNotNull(record);
        assertSame(application, record.client);
        assertSame(application, ((LegacyClientCallback) record.callback).application);
    }
}
