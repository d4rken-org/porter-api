package rikka.shizuku.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.application;
import static rikka.shizuku.server.ServerTestSupport.entry;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.util.Collections;

import eu.darken.porter.core.ManagerOperations;
import eu.darken.porter.core.PorterCore;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.ServerTestSupport.TestUserServiceManager;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.OsUtils;

/**
 * What the client-facing operations on {@link ShizukuLegacyEndpoint} do with the caller's identity
 * and record.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ShizukuLegacyEndpointTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private static final int TARGET_CODE = 7;
    private static final int IN_PARCEL_FLAGS = 42;
    private static final int PAYLOAD = 20816;
    private static final int OUTER_FLAGS = 17;

    private ShizukuLegacyEndpoint endpoint;
    private PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> core;
    private TestPolicy policy;
    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private MockedStatic<PackageManagerApis> packages;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        policy = new TestPolicy();
        core = newCore(clients, new TestUserServiceManager(), config, policy,
                uid -> Collections.singletonList(PACKAGE));
        endpoint = new ShizukuLegacyEndpoint(core, mock(ManagerOperations.class));

        PackageInfo installed = new PackageInfo();
        installed.packageName = PACKAGE;
        installed.applicationInfo = new ApplicationInfo();
        installed.applicationInfo.uid = CLIENT_UID;
        installed.applicationInfo.sourceDir = "/data/app/porter-probe/base.apk";
        packages = Mockito.mockStatic(PackageManagerApis.class);
        packages.when(() -> PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()))
                .thenReturn(installed);

        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After
    public void teardown() {
        packages.close();
        ShadowBinder.reset();
    }

    private ClientRecord attach(IShizukuApplication app, int pid, int apiVersion) {
        return clients.addClient(CLIENT_UID, pid, app, PACKAGE, apiVersion);
    }

    private static Bundle bindOptions(boolean noCreate) {
        Bundle options = new Bundle();
        options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, new ComponentName(PACKAGE, CLASS));
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, noCreate);
        return options;
    }

    private static IShizukuServiceConnection connection() {
        IShizukuServiceConnection connection = mock(IShizukuServiceConnection.class);
        when(connection.asBinder()).thenReturn(mock(IBinder.class));
        return connection;
    }

    @Test
    public void requestPermissionFromAllowedClientRepliesTrue() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        IShizukuApplication app = application(mock(IBinder.class));
        attach(app, CLIENT_PID, 13);

        endpoint.requestPermission(7);

        ArgumentCaptor<Bundle> reply = ArgumentCaptor.forClass(Bundle.class);
        verify(app).dispatchRequestPermissionResult(eq(7), reply.capture());
        assertTrue(reply.getValue().getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED));
        assertFalse(policy.confirmationShown);
    }

    @Test
    public void requestPermissionWithDeniedEntryRepliesFalse() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(false, true));
        IShizukuApplication app = application(mock(IBinder.class));
        attach(app, CLIENT_PID, 13);

        endpoint.requestPermission(9);

        ArgumentCaptor<Bundle> reply = ArgumentCaptor.forClass(Bundle.class);
        verify(app).dispatchRequestPermissionResult(eq(9), reply.capture());
        assertFalse(reply.getValue().getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED));
        assertFalse(policy.confirmationShown);
    }

    @Test
    public void requestPermissionOtherwiseAsksForConfirmation() throws Exception {
        IShizukuApplication app = application(mock(IBinder.class));
        ClientRecord record = attach(app, CLIENT_PID, 13);

        endpoint.requestPermission(11);

        assertTrue(policy.confirmationShown);
        assertEquals(11, policy.confirmationRequestCode);
        assertSame(record, policy.confirmationRecord);
        assertEquals(CLIENT_UID, policy.confirmationUid);
        assertEquals(CLIENT_PID, policy.confirmationPid);
        assertEquals(CLIENT_UID / 100000, policy.confirmationUserId);
        verify(app, never()).dispatchRequestPermissionResult(anyInt(), any(Bundle.class));
    }

    @Test
    public void requestPermissionFromServerUidReturnsSilently() {
        IShizukuApplication app = application(mock(IBinder.class));
        attach(app, CLIENT_PID, 13);
        clearInvocations(app);
        ShadowBinder.setCallingUid(OsUtils.getUid());

        endpoint.requestPermission(13);

        assertFalse(policy.confirmationShown);
        verifyNoInteractions(app);
    }

    @Test
    public void requestPermissionFromUnattachedCallerThrowsIllegalState() {
        assertThrows(IllegalStateException.class, () -> endpoint.requestPermission(15));
    }

    @Test
    public void checkSelfPermissionReflectsTheRecord() {
        ShadowBinder.setCallingUid(OsUtils.getUid());
        assertTrue(endpoint.checkSelfPermission());

        ShadowBinder.setCallingUid(CLIENT_UID);
        assertThrows(IllegalStateException.class, () -> endpoint.checkSelfPermission());

        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        ClientRecord record = attach(application(mock(IBinder.class)), CLIENT_PID, 13);
        assertTrue(endpoint.checkSelfPermission());

        record.allowed = false;
        assertFalse(endpoint.checkSelfPermission());
    }

    @Test
    public void rationaleFollowsTheDeniedEntry() {
        ShadowBinder.setCallingUid(OsUtils.getUid());
        assertTrue(endpoint.shouldShowRequestPermissionRationale());

        ShadowBinder.setCallingUid(CLIENT_UID);
        assertThrows(IllegalStateException.class, () -> endpoint.shouldShowRequestPermissionRationale());

        attach(application(mock(IBinder.class)), CLIENT_PID, 13);
        assertFalse(endpoint.shouldShowRequestPermissionRationale());

        when(config.find(CLIENT_UID)).thenReturn(entry(false, true));
        assertTrue(endpoint.shouldShowRequestPermissionRationale());
    }

    /**
     * Forwards one parcel laid out as a v13 client writes it (strong binder, code, flags, payload)
     * and reports what the target saw: {@code {flags, first int of the forwarded payload}}.
     */
    private int[] forward() throws Exception {
        int[] seen = new int[2];
        IBinder target = mock(IBinder.class);
        when(target.transact(anyInt(), any(Parcel.class), any(), anyInt())).thenAnswer(invocation -> {
            seen[0] = invocation.getArgument(3);
            Parcel forwarded = invocation.getArgument(1);
            forwarded.setDataPosition(0);
            seen[1] = forwarded.readInt();
            return true;
        });

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            data.writeStrongBinder(target);
            data.writeInt(TARGET_CODE);
            data.writeInt(IN_PARCEL_FLAGS);
            data.writeInt(PAYLOAD);
            data.setDataPosition(0);
            assertTrue(endpoint.onTransact(
                    ShizukuApiConstants.BINDER_TRANSACTION_transact, data, reply, OUTER_FLAGS));
        } finally {
            data.recycle();
            reply.recycle();
        }
        verify(target).transact(eq(TARGET_CODE), any(Parcel.class), any(), anyInt());
        return seen;
    }

    @Test
    public void transactRemoteReadsFlagsFromTheParcelOnlyForRecordedV13Clients() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        attach(application(mock(IBinder.class)), CLIENT_PID, 13);
        assertArrayEquals(new int[]{IN_PARCEL_FLAGS, PAYLOAD}, forward());

        ShadowBinder.setCallingPid(CLIENT_PID + 1);
        attach(application(mock(IBinder.class)), CLIENT_PID + 1, -1);
        assertArrayEquals(new int[]{OUTER_FLAGS, IN_PARCEL_FLAGS}, forward());

        ShadowBinder.setCallingPid(CLIENT_PID + 2);
        policy.callerPermission = true;
        assertArrayEquals(new int[]{OUTER_FLAGS, IN_PARCEL_FLAGS}, forward());
    }

    // transactRemoteClearsAndRestoresCallingIdentity is not written: Robolectric's ShadowBinder
    // models only the calling uid and pid it was told to report, and leaves clearCallingIdentity as
    // an unimplemented native returning 0, so a cleared identity is indistinguishable from an
    // uncleared one inside the forwarded transaction.

    @Test
    public void legacyAttachSynthesisesTheV13Bundle() throws Exception {
        Bundle[] bound = new Bundle[1];
        IShizukuApplication.Stub app = new IShizukuApplication.Stub() {
            @Override
            public void bindApplication(Bundle data) {
                bound[0] = data;
            }

            @Override
            public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            }

            @Override
            public void showPermissionConfirmation(int requestUid, int requestPid, String requestPackageName, int requestCode) {
            }
        };

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShizukuApiConstants.BINDER_DESCRIPTOR);
            data.writeStrongBinder(app);
            data.writeString(PACKAGE);
            data.setDataPosition(0);

            assertTrue(endpoint.onTransact(14, data, reply, 0));

            assertNotNull(bound[0]);
            assertEquals(12, bound[0].getInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION));
            ClientRecord record = clients.findClient(CLIENT_UID, CLIENT_PID);
            assertNotNull(record);
            assertEquals(PACKAGE, record.packageName);
            assertEquals(-1, record.apiVersion);
            reply.setDataPosition(0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Test
    public void addUserServiceUsesTheRecordedApiVersion() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));

        attach(application(mock(IBinder.class)), CLIENT_PID, 12);
        assertEquals(1, endpoint.addUserService(connection(), bindOptions(true)));

        ShadowBinder.setCallingPid(CLIENT_PID + 1);
        attach(application(mock(IBinder.class)), CLIENT_PID + 1, 13);
        assertEquals(-1, endpoint.addUserService(connection(), bindOptions(true)));

        ShadowBinder.setCallingPid(CLIENT_PID + 2);
        policy.callerPermission = true;
        assertEquals(-1, endpoint.addUserService(connection(), bindOptions(true)));
    }

    @Test
    public void newProcessLinksTheHolderToTheClientBinder() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        IBinder appBinder = mock(IBinder.class);
        attach(application(appBinder), CLIENT_PID, 13);
        clearInvocations(appBinder);

        IRemoteProcess process = endpoint.newProcess(new String[]{"sh", "-c", "exit 0"}, null, null);

        assertNotNull(process);
        verify(appBinder).linkToDeath(any(IBinder.DeathRecipient.class), eq(0));

        ShadowBinder.setCallingPid(CLIENT_PID + 1);
        policy.callerPermission = true;
        clearInvocations(appBinder);

        assertNotNull(endpoint.newProcess(new String[]{"sh", "-c", "exit 0"}, null, null));

        verify(appBinder, never()).linkToDeath(any(IBinder.DeathRecipient.class), anyInt());
    }

    @Test
    public void newProcessTranslatesExecFailure() {
        policy.callerPermission = true;

        assertThrows(IllegalStateException.class,
                () -> endpoint.newProcess(new String[]{"/nonexistent/porter-probe"}, null, null));
    }
}
