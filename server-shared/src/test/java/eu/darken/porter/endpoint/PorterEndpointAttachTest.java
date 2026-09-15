package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServiceCallerGateTest.application;
import static rikka.shizuku.server.ServiceCallerGateTest.entry;
import static rikka.shizuku.server.ServiceCallerGateTest.newService;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ClientCallback;
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ServiceCallerGateTest.TestService;
import rikka.shizuku.server.ServiceCallerGateTest.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.OsUtils;

/** Who the Porter endpoint lets attach, what it records for them and what it reports back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterEndpointAttachTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String OTHER_PACKAGE = "eu.darken.porter.probe.other";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private TestService service;
    private PorterEndpoint endpoint;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        service = newService(clients, new TestUserServiceManager(), config);
        endpoint = endpointOwning(service, PACKAGE, OTHER_PACKAGE);

        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    private static PorterEndpoint endpointOwning(TestService service, String... packages) {
        List<String> owned = Arrays.asList(packages);
        return new PorterEndpoint(service, uid -> owned);
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
    public void attachRecordsAPorterClientAndReportsTheServerState() {
        Bundle reply = endpoint.attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE));

        ClientRecord record = clients.findClient(CLIENT_UID, CLIENT_PID);
        assertNotNull(record);
        assertTrue(record.callback instanceof PorterClientCallback);
        assertNull("no Shizuku application for a Porter client", record.client);
        assertEquals(PACKAGE, record.packageName);

        assertTrue(reply.containsKey(REPLY_PROTOCOL_VERSION));
        assertTrue(reply.containsKey(REPLY_SERVER_UID));
        assertTrue(reply.containsKey(REPLY_SERVER_SECONTEXT));
        assertTrue(reply.containsKey(REPLY_PERMISSION_GRANTED));
        assertTrue(reply.containsKey(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE));
        assertTrue(reply.containsKey(REPLY_CAPABILITIES));

        assertEquals(1, reply.getInt(REPLY_PROTOCOL_VERSION));
        assertEquals(OsUtils.getUid(), reply.getInt(REPLY_SERVER_UID));
        assertEquals(OsUtils.getSELinuxContext(), reply.getString(REPLY_SERVER_SECONTEXT));
        assertFalse(reply.getBoolean(REPLY_PERMISSION_GRANTED));
        assertFalse(reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE));
        assertEquals(CAPABILITIES_NONE, reply.getLong(REPLY_CAPABILITIES));
    }

    @Test
    public void aPackageThatDoesNotBelongToTheCallerIsRefused() {
        PorterEndpoint foreign = endpointOwning(service, OTHER_PACKAGE);

        assertThrows(SecurityException.class,
                () -> foreign.attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE)));

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID));
    }

    @Test
    public void aSecondAttachFromTheSameProcessDoesNotCreateASecondRecord() {
        endpoint.attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE));
        ClientRecord first = clients.findClient(CLIENT_UID, CLIENT_PID);

        Bundle reply = endpoint.attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE));

        assertEquals(1, reply.getInt(REPLY_PROTOCOL_VERSION));
        assertEquals(1, clients.findClients(CLIENT_UID).size());
        assertSame(first, clients.findClient(CLIENT_UID, CLIENT_PID));
    }

    @Test
    public void aReattachMayNotRenameTheClient() {
        endpoint.attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE));

        assertThrows(SecurityException.class,
                () -> endpoint.attach(porterApplication(mock(IBinder.class)), attachArgs(OTHER_PACKAGE)));

        assertEquals(1, clients.findClients(CLIENT_UID).size());
        assertEquals(PACKAGE, clients.findClient(CLIENT_UID, CLIENT_PID).packageName);
    }

    @Test
    public void aProcessAttachedThroughTheShizukuEndpointIsRefused() {
        clients.addClient(CLIENT_UID, CLIENT_PID, application(mock(IBinder.class)), PACKAGE, 13);

        assertThrows(IllegalStateException.class,
                () -> endpoint.attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE)));
    }

    @Test
    public void aDeadCallbackBinderIsReportedAsIllegalState() throws Exception {
        IBinder dead = mock(IBinder.class);
        doThrow(new RemoteException()).when(dead).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());

        assertThrows(IllegalStateException.class,
                () -> endpoint.attach(porterApplication(dead), attachArgs(PACKAGE)));

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID));
    }

    @Test
    public void theReplyReportsTheConfiguredGrant() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));

        Bundle reply = endpoint.attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE));

        assertTrue(reply.getBoolean(REPLY_PERMISSION_GRANTED));
    }

    /** A racing second attach cannot be scheduled deterministically; the monitor it needs can. */
    private static class LockRecordingClientManager extends ClientManager<ConfigManager> {

        final AtomicBoolean lockHeldOnAttach = new AtomicBoolean();

        LockRecordingClientManager(ConfigManager configManager) {
            super(configManager);
        }

        @Override
        public ClientRecord attach(
                CallerIdentity identity, ClientCallback callback, String packageName, int apiVersion) {
            lockHeldOnAttach.set(Thread.holdsLock(this));
            return super.attach(identity, callback, packageName, apiVersion);
        }
    }

    @Test
    public void theFindAndAttachPairRunsUnderTheClientManagerMonitor() {
        LockRecordingClientManager recording = new LockRecordingClientManager(config);
        TestService recordingService = newService(recording, new TestUserServiceManager(), config);

        endpointOwning(recordingService, PACKAGE)
                .attach(porterApplication(mock(IBinder.class)), attachArgs(PACKAGE));

        assertTrue(recording.lockHeldOnAttach.get());
    }
}
