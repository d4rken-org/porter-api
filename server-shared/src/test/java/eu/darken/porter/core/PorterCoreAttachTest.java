package eu.darken.porter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.application;
import static rikka.shizuku.server.ServerTestSupport.entry;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import eu.darken.porter.endpoint.PorterClientCallback;
import eu.darken.porter.server.IPorterApplication;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.UserServiceManager;
import rikka.shizuku.server.legacy.LegacyClientCallback;
import rikka.shizuku.server.util.HandlerUtil;

/** Which process gets a record, through which endpoint, and what the policy sees on the way. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterCoreAttachTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String OTHER_PACKAGE = "eu.darken.porter.probe.other";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;
    private static final int API_LEVEL = 13;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private RecordingPolicy policy;
    private PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> core;
    private CallerIdentity caller;

    /** Whether a record was already there each time the policy was told an attach is starting. */
    private static class RecordingPolicy extends TestPolicy {

        private final ClientManager<ConfigManager> clients;
        final List<Boolean> recordExistedWhenAttaching = new ArrayList<>();

        RecordingPolicy(ClientManager<ConfigManager> clients) {
            this.clients = clients;
        }

        @Override
        public void onAttaching(CallerIdentity caller, String packageName) {
            super.onAttaching(caller, packageName);
            recordExistedWhenAttaching.add(clients.findClient(caller.uid, caller.pid) != null);
        }
    }

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        policy = new RecordingPolicy(clients);
        core = newCore(clients, new TestUserServiceManager(), config, policy,
                uid -> Arrays.asList(PACKAGE, OTHER_PACKAGE));

        caller = new CallerIdentity(CLIENT_UID, CLIENT_PID);
    }

    private static ClientCallback legacyCallback() {
        return new LegacyClientCallback(application(mock(IBinder.class)));
    }

    private static ClientCallback porterCallback() {
        IPorterApplication application = mock(IPorterApplication.class);
        when(application.asBinder()).thenReturn(mock(IBinder.class));
        return new PorterClientCallback(application);
    }

    @Test
    public void aProcessAttachedThroughShizukuCannotAttachThroughPorter() {
        core.attach(caller, PACKAGE, legacyCallback(), API_LEVEL);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> core.attach(caller, PACKAGE, porterCallback(), API_LEVEL));

        assertTrue(e.getMessage(), e.getMessage().contains("is attached through another endpoint"));
        assertEquals(1, clients.findClients(CLIENT_UID).size());
    }

    @Test
    public void aProcessAttachedThroughPorterCannotAttachThroughShizuku() {
        core.attach(caller, PACKAGE, porterCallback(), API_LEVEL);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> core.attach(caller, PACKAGE, legacyCallback(), API_LEVEL));

        assertTrue(e.getMessage(), e.getMessage().contains("is attached through another endpoint"));
        assertEquals(1, clients.findClients(CLIENT_UID).size());
    }

    @Test
    public void aReattachMayNotRenameTheClient() {
        core.attach(caller, PACKAGE, porterCallback(), API_LEVEL);

        assertThrows(SecurityException.class,
                () -> core.attach(caller, OTHER_PACKAGE, porterCallback(), API_LEVEL));

        assertEquals(1, clients.findClients(CLIENT_UID).size());
        assertEquals(PACKAGE, clients.findClient(CLIENT_UID, CLIENT_PID).packageName);
    }

    @Test
    public void theSecondAttachFromAProcessReusesTheRecordItFinds() {
        AttachResult first = core.attach(caller, PACKAGE, porterCallback(), API_LEVEL);
        AttachResult second = core.attach(caller, PACKAGE, porterCallback(), API_LEVEL);

        assertTrue(first.created);
        assertFalse(second.created);
        assertSame(first.record, second.record);

        assertEquals(Arrays.asList(PACKAGE, PACKAGE), policy.attachingPackages);
        assertEquals("the first attach is told before the record exists",
                Arrays.asList(false, true), policy.recordExistedWhenAttaching);
    }

    @Test
    public void aDeadCallbackBinderIsReportedAsIllegalState() throws Exception {
        IBinder dead = mock(IBinder.class);
        doThrow(new RemoteException()).when(dead).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
        IPorterApplication application = mock(IPorterApplication.class);
        when(application.asBinder()).thenReturn(dead);

        assertThrows(IllegalStateException.class,
                () -> core.attach(caller, PACKAGE, new PorterClientCallback(application), API_LEVEL));

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID));
    }

    @Test
    public void aPackageThatDoesNotBelongToTheCallerIsRefusedBeforeThePolicyHears() {
        PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> foreign =
                newCore(clients, new TestUserServiceManager(), config, policy,
                        uid -> Collections.singletonList(OTHER_PACKAGE));

        assertThrows(SecurityException.class,
                () -> foreign.attach(caller, PACKAGE, porterCallback(), API_LEVEL));

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID));
        assertTrue(policy.attachingPackages.isEmpty());
    }

    @Test
    public void anAttachedRecordCarriesWhatTheCallerWasGranted() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));

        ClientRecord record = core.attach(caller, PACKAGE, porterCallback(), API_LEVEL).record;

        assertTrue(record.allowed);
        assertEquals(PACKAGE, record.packageName);
        assertEquals(API_LEVEL, record.apiVersion);
    }
}
