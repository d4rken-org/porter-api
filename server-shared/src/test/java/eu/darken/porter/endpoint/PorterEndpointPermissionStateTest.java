package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.util.Collections;
import java.util.List;

import eu.darken.porter.core.ManagerOperations;
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;

/** What a Porter client is told when the grant behind its record changes after it attached. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterEndpointPermissionStateTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private PorterEndpoint endpoint;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        endpoint = new PorterEndpoint(
                newCore(clients, new TestUserServiceManager(), config, new TestPolicy(),
                        uid -> Collections.singletonList(PACKAGE)),
                mock(ManagerOperations.class));

        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    private static IPorterApplication porterApplication(IBinder binder) {
        IPorterApplication application = mock(IPorterApplication.class);
        when(application.asBinder()).thenReturn(binder);
        return application;
    }

    private static Bundle attachArgs(String packageName) {
        Bundle args = new Bundle();
        args.putString(ATTACH_PACKAGE_NAME, packageName);
        args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION);
        return args;
    }

    @Test
    public void anAttachedClientIsSentBothFlagsOfEveryStateChange() throws Exception {
        IPorterApplication application = porterApplication(mock(IBinder.class));
        endpoint.attach(application, attachArgs(PACKAGE));

        ClientRecord record = clients.findClient(CLIENT_UID, CLIENT_PID);
        assertNotNull(record);

        record.callback.onPermissionStateChanged(false, true);
        record.callback.onPermissionStateChanged(true, false);

        ArgumentCaptor<Bundle> state = ArgumentCaptor.forClass(Bundle.class);
        verify(application, times(2)).dispatchPermissionStateChanged(state.capture());

        List<Bundle> sent = state.getAllValues();
        assertEquals(2, sent.size());

        assertFalse(sent.get(0).getBoolean(REPLY_PERMISSION_GRANTED));
        assertTrue(sent.get(0).getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE));

        assertTrue(sent.get(1).getBoolean(REPLY_PERMISSION_GRANTED));
        assertFalse(sent.get(1).getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE));
    }
}
