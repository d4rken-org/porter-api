package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.entry;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.util.Collections;

import eu.darken.porter.core.ManagerOperations;
import eu.darken.porter.core.ServerPolicy;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.OsUtils;

/** What a Shizuku client is told when it attaches, and what the policy gets to say about it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuLegacyEndpointAttachTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String OTHER_PACKAGE = "eu.darken.porter.probe.other";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private TestPolicy policy;
    private ShizukuLegacyEndpoint endpoint;

    /** Keeps the reply it was bound with, and can fail the way a dead client does. */
    private static class RecordingApplication extends IShizukuApplication.Stub {

        Bundle bound;
        RuntimeException failure;

        @Override
        public void bindApplication(Bundle data) {
            bound = data;
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
        }

        @Override
        public void showPermissionConfirmation(
                int requestUid, int requestPid, String requestPackageName, int requestCode) {
        }
    }

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        policy = new TestPolicy();
        endpoint = endpointWith(policy);

        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    private ShizukuLegacyEndpoint endpointWith(ServerPolicy serverPolicy) {
        return new ShizukuLegacyEndpoint(
                newCore(clients, new TestUserServiceManager(), config, serverPolicy,
                        uid -> Collections.singletonList(PACKAGE)),
                mock(ManagerOperations.class));
    }

    private static Bundle attachArgs(String packageName, int apiVersion) {
        Bundle args = new Bundle();
        args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName);
        args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, apiVersion);
        return args;
    }

    private static IShizukuServiceConnection connection() {
        IShizukuServiceConnection connection = mock(IShizukuServiceConnection.class);
        when(connection.asBinder()).thenReturn(mock(IBinder.class));
        return connection;
    }

    @Test
    public void aV13AttachIsAnsweredWithTheSixKeyReply() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        RecordingApplication app = new RecordingApplication();

        endpoint.attachApplication(app, attachArgs(PACKAGE, 13));

        ClientRecord record = clients.findClient(CLIENT_UID, CLIENT_PID);
        assertNotNull(record);
        assertEquals(PACKAGE, record.packageName);
        assertEquals(13, record.apiVersion);

        assertNotNull(app.bound);
        assertEquals(OsUtils.getUid(), app.bound.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID));
        assertEquals(ShizukuApiConstants.SERVER_VERSION,
                app.bound.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION));
        assertEquals(OsUtils.getSELinuxContext(),
                app.bound.getString(ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT));
        assertEquals(ShizukuApiConstants.SERVER_PATCH_VERSION,
                app.bound.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION));
        assertTrue(app.bound.getBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED));
        assertFalse(app.bound.getBoolean(
                ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE));
    }

    @Test
    public void theCode14PathAttachesAPreV13ClientAndRepliesVersion12() throws Exception {
        RecordingApplication app = new RecordingApplication();

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            data.writeStrongBinder(app);
            data.writeString(PACKAGE);
            data.setDataPosition(0);

            assertTrue(endpoint.onTransact(14, data, reply, 0));

            reply.setDataPosition(0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }

        ClientRecord record = clients.findClient(CLIENT_UID, CLIENT_PID);
        assertNotNull(record);
        assertEquals(-1, record.apiVersion);
        assertNotNull(app.bound);
        assertEquals(12, app.bound.getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION));
    }

    @Test
    public void thePolicyMayTakeAKeyOutOfTheReplyBeforeItLeaves() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        ShizukuLegacyEndpoint stripping = endpointWith(new ServerPolicy() {
            @Override
            public void onAttached(ClientRecord record, boolean created, Bundle reply) {
                reply.remove(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED);
            }
        });
        RecordingApplication app = new RecordingApplication();

        stripping.attachApplication(app, attachArgs(PACKAGE, 13));

        assertFalse(app.bound.containsKey(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED));
        assertTrue(app.bound.containsKey(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID));
    }

    @Test
    public void onBoundFollowsADeliveredReply() {
        RecordingApplication app = new RecordingApplication();

        endpoint.attachApplication(app, attachArgs(PACKAGE, 13));

        ClientRecord record = clients.findClient(CLIENT_UID, CLIENT_PID);
        assertEquals(Collections.singletonList(record), policy.attachedRecords);
        assertEquals(Collections.singletonList(true), policy.attachedCreated);
        assertEquals(Collections.singletonList(record), policy.boundRecords);
        assertEquals(Collections.singletonList(true), policy.boundCreated);
    }

    @Test
    public void aFailedBindIsLoggedAndSkipsOnBound() {
        RecordingApplication app = new RecordingApplication();
        app.failure = new RuntimeException("client is gone");

        endpoint.attachApplication(app, attachArgs(PACKAGE, 13));

        assertNotNull(clients.findClient(CLIENT_UID, CLIENT_PID));
        assertEquals(1, policy.attachedRecords.size());
        assertTrue(policy.boundRecords.isEmpty());
    }

    @Test
    public void anAttachWithoutAnApplicationPackageOrArgsRecordsNobody() {
        endpoint.attachApplication(null, attachArgs(PACKAGE, 13));
        endpoint.attachApplication(new RecordingApplication(), null);
        endpoint.attachApplication(new RecordingApplication(), new Bundle());

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID));
        assertTrue(policy.attachingPackages.isEmpty());
    }

    @Test
    public void aPackageThatDoesNotBelongToTheCallerIsRefused() {
        assertThrows(SecurityException.class,
                () -> endpoint.attachApplication(new RecordingApplication(), attachArgs(OTHER_PACKAGE, 13)));

        assertNull(clients.findClient(CLIENT_UID, CLIENT_PID));
    }

    /**
     * A refused caller is told it has no permission, not what its Bundle failed to decode to: a
     * null or component-less Bundle would raise something else if the gate ran after the decoder.
     */
    @Test
    public void theUserServiceCallsRefuseAnUnauthorizedCallerBeforeReadingItsBundle() {
        assertThrows(SecurityException.class, () -> endpoint.addUserService(connection(), null));
        assertThrows(SecurityException.class, () -> endpoint.addUserService(connection(), new Bundle()));
        assertThrows(SecurityException.class, () -> endpoint.removeUserService(connection(), null));
        assertThrows(SecurityException.class, () -> endpoint.removeUserService(connection(), new Bundle()));
    }
}
