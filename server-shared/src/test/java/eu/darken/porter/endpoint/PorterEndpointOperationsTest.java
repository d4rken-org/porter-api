package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static rikka.shizuku.server.ServerTestSupport.entry;
import static rikka.shizuku.server.ServerTestSupport.newCore;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;

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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import eu.darken.porter.core.ManagerOperations;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterServiceConnection;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ServerTestSupport.TestPolicy;
import rikka.shizuku.server.UserServiceManager;
import rikka.shizuku.server.UserServiceRecord;
import rikka.shizuku.server.util.HandlerUtil;

/** What the Porter endpoint's operations do with the caller's record, options and gate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class PorterEndpointOperationsTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final String TAG = "probe-tag";
    private static final String PROCESS_NAME_SUFFIX = "probe";
    /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
    private static final int CLIENT_UID = 1010200;
    private static final int CLIENT_PID = 45678;

    private ConfigManager config;
    private ClientManager<ConfigManager> clients;
    private RecordingUserServiceManager userServices;
    private PorterEndpoint endpoint;
    private MockedStatic<PackageManagerApis> packages;

    /** Records what reached the manager: the record carries only part of the decoded options. */
    private static class RecordingUserServiceManager extends UserServiceManager {

        final List<UserServiceRecord> created = new CopyOnWriteArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);
        volatile String key;
        volatile String className;
        volatile String processNameSuffix;

        @Override
        public String getUserServiceStartCmd(
                UserServiceRecord record, String key, String token, String packageName,
                String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {
            this.key = key;
            this.className = classname;
            this.processNameSuffix = processNameSuffix;
            started.countDown();
            return "exit 0";
        }

        @Override
        public void onUserServiceRecordCreated(UserServiceRecord record, PackageInfo packageInfo) {
            created.add(record);
        }
    }

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        userServices = new RecordingUserServiceManager();
        endpoint = new PorterEndpoint(
                newCore(clients, userServices, config, new TestPolicy(),
                        uid -> Collections.singletonList(PACKAGE)),
                mock(ManagerOperations.class));

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

    private static IPorterApplication porterApplication(IBinder binder) {
        IPorterApplication application = mock(IPorterApplication.class);
        when(application.asBinder()).thenReturn(binder);
        return application;
    }

    private static IPorterServiceConnection connection() {
        IPorterServiceConnection connection = mock(IPorterServiceConnection.class);
        when(connection.asBinder()).thenReturn(mock(IBinder.class));
        return connection;
    }

    private IPorterApplication attach() {
        IPorterApplication application = porterApplication(mock(IBinder.class));
        Bundle args = new Bundle();
        args.putString(ATTACH_PACKAGE_NAME, PACKAGE);
        args.putInt(ATTACH_PROTOCOL_VERSION, 1);
        endpoint.attach(application, args);
        return application;
    }

    private static Bundle bindArgs(boolean noCreate) {
        Bundle args = new Bundle();
        args.putParcelable(USER_SERVICE_COMPONENT, new ComponentName(PACKAGE, CLASS));
        args.putString(USER_SERVICE_TAG, TAG);
        args.putInt(USER_SERVICE_VERSION_CODE, 3);
        args.putBoolean(USER_SERVICE_DAEMON, false);
        args.putString(USER_SERVICE_PROCESS_NAME_SUFFIX, PROCESS_NAME_SUFFIX);
        args.putBoolean(USER_SERVICE_NO_CREATE, noCreate);
        return args;
    }

    private static Bundle removeArgs(boolean remove) {
        Bundle args = new Bundle();
        args.putParcelable(USER_SERVICE_COMPONENT, new ComponentName(PACKAGE, CLASS));
        args.putString(USER_SERVICE_TAG, TAG);
        args.putBoolean(USER_SERVICE_REMOVE, remove);
        return args;
    }

    @Test
    public void requestPermissionFromAnAllowedClientRepliesTrue() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        IPorterApplication application = attach();

        endpoint.requestPermission(7);

        ArgumentCaptor<Bundle> reply = ArgumentCaptor.forClass(Bundle.class);
        verify(application).dispatchRequestPermissionResult(eq(7), reply.capture());
        assertTrue(reply.getValue().getBoolean(PERMISSION_RESULT_ALLOWED));
    }

    @Test
    public void requestPermissionWithADeniedEntryRepliesFalse() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(false, true));
        IPorterApplication application = attach();

        endpoint.requestPermission(9);

        ArgumentCaptor<Bundle> reply = ArgumentCaptor.forClass(Bundle.class);
        verify(application).dispatchRequestPermissionResult(eq(9), reply.capture());
        assertFalse(reply.getValue().getBoolean(PERMISSION_RESULT_ALLOWED));
    }

    @Test
    public void requestPermissionFromAnUnattachedCallerThrowsIllegalState() {
        assertThrows(IllegalStateException.class, () -> endpoint.requestPermission(11));
    }

    @Test
    public void addUserServiceDecodesThePorterKeys() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        attach();

        assertEquals(0, endpoint.addUserService(connection(), bindArgs(false)));

        assertEquals(1, userServices.created.size());
        UserServiceRecord record = userServices.created.get(0);
        assertEquals(3, record.versionCode);
        assertFalse(record.daemon);
        assertEquals(1, record.callbacks.getRegisteredCallbackCount());
        assertEquals(1, record.callbacks.beginBroadcast());
        try {
            assertTrue(record.callbacks.getBroadcastItem(0) instanceof PorterServiceConnection);
        } finally {
            record.callbacks.finishBroadcast();
        }

        assertTrue(userServices.started.await(5, TimeUnit.SECONDS));
        assertEquals(PACKAGE + ":" + TAG, userServices.key);
        assertEquals(CLASS, userServices.className);
        assertEquals(PROCESS_NAME_SUFFIX, userServices.processNameSuffix);
    }

    @Test
    public void peekingAtAServiceThatIsNotRunningAnswersMinusOne() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        attach();

        assertEquals(-1, endpoint.addUserService(connection(), bindArgs(true)));
        assertTrue(userServices.created.isEmpty());
    }

    @Test
    public void removeUnregistersWithoutRemovingUnlessAsked() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        attach();
        IPorterServiceConnection connection = connection();
        endpoint.addUserService(connection, bindArgs(false));
        UserServiceRecord record = userServices.created.get(0);

        assertEquals(0, endpoint.removeUserService(connection, removeArgs(false)));

        assertEquals(0, record.callbacks.getRegisteredCallbackCount());
        assertFalse(record.isRemoved());

        assertEquals(0, endpoint.removeUserService(connection, removeArgs(true)));

        assertTrue(record.isRemoved());
    }

    @Test
    public void newProcessRunsForAnAllowedClient() throws Exception {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        attach();

        IPorterRemoteProcess process = endpoint.newProcess(new String[]{"sh", "-c", "exit 3"}, null, null);

        assertNotNull(process);
        assertEquals(3, process.waitFor());
    }

    @Test
    public void everyGatedOperationRefusesACallerWithoutPermission() {
        assertThrows(SecurityException.class, () -> endpoint.getUid());
        assertThrows(SecurityException.class, () -> endpoint.checkPermission("android.permission.DUMP"));
        assertThrows(SecurityException.class, () -> endpoint.getSELinuxContext());
        assertThrows(SecurityException.class, () -> endpoint.getSystemProperty("ro.build.id", ""));
        assertThrows(SecurityException.class, () -> endpoint.setSystemProperty("ro.build.id", ""));
        assertThrows(SecurityException.class,
                () -> endpoint.newProcess(new String[]{"sh", "-c", "exit 0"}, null, null));
        assertThrows(SecurityException.class, () -> endpoint.addUserService(connection(), bindArgs(false)));
        assertThrows(SecurityException.class, () -> endpoint.removeUserService(connection(), removeArgs(true)));
    }
}
