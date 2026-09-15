package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.util.HandlerUtil;

/**
 * The bind, remove and attach seam of {@link UserServiceManager}: return values, key derivation,
 * option defaults and the order in which a caller's authorisation is decided.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class UserServiceManagerTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final String DESCRIPTOR = "eu.darken.porter.probe.IProbe";
    private static final int APP_ID = 10123;
    private static final int UID = APP_ID;

    private static class TestManager extends UserServiceManager {
        final List<UserServiceRecord> created = new CopyOnWriteArrayList<>();

        @Override
        public String getUserServiceStartCmd(
                UserServiceRecord record, String key, String token, String packageName,
                String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {
            return "exit 0";
        }

        @Override
        public void onUserServiceRecordCreated(UserServiceRecord record, PackageInfo packageInfo) {
            created.add(record);
        }
    }

    private TestManager manager;
    private MockedStatic<PackageManagerApis> packages;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));

        PackageInfo installed = new PackageInfo();
        installed.packageName = PACKAGE;
        installed.applicationInfo = new ApplicationInfo();
        installed.applicationInfo.uid = UID;
        installed.applicationInfo.sourceDir = "/data/app/porter-probe/base.apk";

        packages = Mockito.mockStatic(PackageManagerApis.class);
        packages.when(() -> PackageManagerApis.getPackageInfoNoThrow(eq(PACKAGE), anyLong(), anyInt()))
                .thenReturn(installed);

        ShadowBinder.setCallingUid(UID);
        manager = new TestManager();
    }

    @After
    public void teardown() {
        packages.close();
        ShadowBinder.reset();
    }

    private static IShizukuServiceConnection connection() {
        IShizukuServiceConnection connection = mock(IShizukuServiceConnection.class);
        when(connection.asBinder()).thenReturn(mock(IBinder.class));
        return connection;
    }

    private static Bundle options(String className) {
        Bundle options = new Bundle();
        options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, new ComponentName(PACKAGE, className));
        options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 1);
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, true);
        return options;
    }

    /** Replaces every bind-only value with one of the wrong type. */
    private static Bundle poison(Bundle options) {
        options.putBundle(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, new Bundle());
        options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_PROCESS_NAME, 3);
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_DEBUGGABLE, "yes");
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, "yes");
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, "yes");
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_USE_32_BIT_APP_PROCESS, "yes");
        return options;
    }

    private UserServiceRecord add(IShizukuServiceConnection connection, Bundle options) {
        int before = manager.created.size();
        manager.addUserService(connection, options, ShizukuApiConstants.SERVER_VERSION);
        assertEquals(before + 1, manager.created.size());
        return manager.created.get(manager.created.size() - 1);
    }

    private IBinder liveBinder() throws Exception {
        IBinder binder = mock(IBinder.class);
        when(binder.pingBinder()).thenReturn(true);
        when(binder.getInterfaceDescriptor()).thenReturn(DESCRIPTOR);
        when(binder.transact(anyInt(), any(), any(), anyInt())).thenReturn(true);
        return binder;
    }

    private void attach(UserServiceRecord record, IBinder binder) {
        Bundle options = new Bundle();
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token);
        manager.attachUserService(binder, options);
    }

    /**
     * Robolectric's {@code RemoteCallbackList} shadow prunes its own registration map on binder
     * death and then calls the shadow's no-op {@code onCallbackDied}, never a subclass override, so
     * the record's reaction has to be driven separately. The override ignores its argument.
     */
    private void killConnection(UserServiceRecord record, IShizukuServiceConnection connection) throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> recipient =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(connection.asBinder()).linkToDeath(recipient.capture(), eq(0));
        recipient.getValue().binderDied();
        record.callbacks.onCallbackDied(null);
    }

    /** The cleanup executor is single threaded, so a task queued behind ours has drained it. */
    private void drainCleanup() throws Exception {
        Field field = UserServiceManager.class.getDeclaredField("cleanupExecutor");
        field.setAccessible(true);
        CountDownLatch drained = new CountDownLatch(1);
        ((Executor) field.get(manager)).execute(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void noCreateReturnsAreVersionConditional() throws Exception {
        Bundle noCreate = options(CLASS);
        noCreate.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_NO_CREATE, true);

        assertEquals(-1, manager.addUserService(connection(), noCreate, 13));
        assertEquals(1, manager.addUserService(connection(), noCreate, 12));

        UserServiceRecord record = add(connection(), options(CLASS));
        attach(record, liveBinder());

        assertEquals(record.versionCode, manager.addUserService(connection(), noCreate, 13));
        assertEquals(0, manager.addUserService(connection(), noCreate, 12));
    }

    @Test
    public void bindingToALiveRecordBroadcastsConnectedImmediately() throws Exception {
        UserServiceRecord record = add(connection(), options(CLASS));
        IBinder binder = liveBinder();
        attach(record, binder);

        IShizukuServiceConnection later = connection();
        assertEquals(0, manager.addUserService(later, options(CLASS), ShizukuApiConstants.SERVER_VERSION));

        verify(later).connected(binder);
    }

    @Test
    public void attachBroadcastsConnectedToEveryRegisteredConnection() throws Exception {
        IShizukuServiceConnection first = connection();
        IShizukuServiceConnection second = connection();
        UserServiceRecord record = add(first, options(CLASS));
        manager.addUserService(second, options(CLASS), ShizukuApiConstants.SERVER_VERSION);

        IBinder binder = liveBinder();
        attach(record, binder);

        verify(first).connected(binder);
        verify(second).connected(binder);
    }

    @Test
    public void removeWithoutRemoveFlagUnregistersOnlyTheConnection() throws Exception {
        IShizukuServiceConnection kept = connection();
        IShizukuServiceConnection dropped = connection();
        UserServiceRecord record = add(kept, options(CLASS));
        manager.addUserService(dropped, options(CLASS), ShizukuApiConstants.SERVER_VERSION);

        Bundle remove = options(CLASS);
        remove.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_REMOVE, false);
        assertEquals(0, manager.removeUserService(dropped, remove));

        assertFalse(record.isRemoved());

        record.broadcastBinderDied();

        verify(kept).died();
        verify(dropped, never()).died();
    }

    @Test
    public void removeReturnsOneForAnUnknownKey() {
        assertEquals(1, manager.removeUserService(connection(), options("NeverBound")));
    }

    @Test
    public void removeDefaultsToTrueForOlderClients() throws Exception {
        UserServiceRecord record = add(connection(), options(CLASS));

        assertEquals(0, manager.removeUserService(connection(), options(CLASS)));
        drainCleanup();

        assertTrue(record.isRemoved());
    }

    @Test
    public void tagOverridesTheClassNameInTheKey() {
        Bundle bind = options(CLASS);
        bind.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, "probe");
        add(connection(), bind);

        assertEquals(1, manager.removeUserService(connection(), options(CLASS)));

        Bundle byTag = options("SomeOtherService");
        byTag.putString(ShizukuApiConstants.USER_SERVICE_ARG_TAG, "probe");
        assertEquals(0, manager.removeUserService(connection(), byTag));
    }

    @Test
    public void versionCodeMismatchReplacesTheRecord() throws Exception {
        UserServiceRecord first = add(connection(), options(CLASS));

        Bundle newer = options(CLASS);
        newer.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 2);
        UserServiceRecord second = add(connection(), newer);
        drainCleanup();

        assertNotSame(first, second);
        assertTrue(first.isRemoved());
        assertFalse(second.isRemoved());
        assertEquals(2, second.versionCode);
    }

    @Test
    public void daemonFlagIsUpdatedOnReuse() {
        UserServiceRecord record = add(connection(), options(CLASS));
        assertTrue(record.daemon);

        Bundle nonDaemon = options(CLASS);
        nonDaemon.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, false);
        manager.addUserService(connection(), nonDaemon, ShizukuApiConstants.SERVER_VERSION);

        assertEquals(1, manager.created.size());
        assertFalse(record.daemon);
    }

    @Test
    public void lastConnectionDeathRemovesANonDaemonRecord() throws Exception {
        IShizukuServiceConnection connection = connection();
        Bundle nonDaemon = options(CLASS);
        nonDaemon.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, false);
        UserServiceRecord record = add(connection, nonDaemon);

        killConnection(record, connection);

        assertTrue(record.isRemoved());
    }

    @Test
    public void daemonRecordSurvivesConnectionDeath() throws Exception {
        IShizukuServiceConnection connection = connection();
        UserServiceRecord record = add(connection, options(CLASS));

        killConnection(record, connection);

        assertFalse(record.isRemoved());
    }

    @Test
    public void nullConnectionIsRefusedBeforeOptions() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> manager.addUserService(null, null, ShizukuApiConstants.SERVER_VERSION));

        assertEquals("connection is null", e.getMessage());
    }

    @Test
    public void missingComponentIsRefused() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> manager.addUserService(connection(), new Bundle(), ShizukuApiConstants.SERVER_VERSION));

        assertEquals("component is null", e.getMessage());
    }

    @Test
    public void foreignPackageIsRefused() {
        ShadowBinder.setCallingUid(UID + 1);

        assertThrows(SecurityException.class,
                () -> manager.addUserService(connection(), options(CLASS), ShizukuApiConstants.SERVER_VERSION));
        assertThrows(SecurityException.class,
                () -> manager.removeUserService(connection(), options(CLASS)));
    }

    @Test
    public void foreignPackageIsRefusedBeforeMalformedOptionsAreNoticed() {
        ShadowBinder.setCallingUid(UID + 1);

        Bundle bind = options(CLASS);
        bind.putString(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, "not-an-int");
        bind.putBundle(ShizukuApiConstants.USER_SERVICE_ARG_TAG, new Bundle());
        assertThrows(SecurityException.class,
                () -> manager.addUserService(connection(), bind, ShizukuApiConstants.SERVER_VERSION));

        Bundle remove = options(CLASS);
        remove.putString(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, "not-an-int");
        remove.putBundle(ShizukuApiConstants.USER_SERVICE_ARG_TAG, new Bundle());
        assertThrows(SecurityException.class,
                () -> manager.removeUserService(connection(), remove));
    }

    @Test
    public void removeIgnoresBindOnlyKeys() {
        assertEquals(1, manager.removeUserService(connection(), poison(options("NeverBound"))));

        add(connection(), options(CLASS));

        assertEquals(0, manager.removeUserService(connection(), poison(options(CLASS))));
    }

    @Test
    public void missingTokenIsRefusedOnAttach() {
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> manager.attachUserService(mock(IBinder.class), new Bundle()));

        assertEquals("token is null", e.getMessage());
    }

    @Test
    public void missingTokenStillReadsTheDescriptor() throws Exception {
        IBinder binder = mock(IBinder.class);
        when(binder.getInterfaceDescriptor()).thenReturn(DESCRIPTOR);

        assertThrows(NullPointerException.class, () -> manager.attachUserService(binder, new Bundle()));

        verify(binder).getInterfaceDescriptor();
    }

    @Test
    public void unknownTokenIsRefusedOnAttach() throws Exception {
        Bundle options = new Bundle();
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, "not-a-token");

        assertThrows(IllegalArgumentException.class, () -> manager.attachUserService(liveBinder(), options));
    }

    @Test
    public void removedRecordIsRefusedOnAttach() throws Exception {
        UserServiceRecord record = add(connection(), options(CLASS));
        record.removeSelf();
        drainCleanup();

        Bundle options = new Bundle();
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token);

        assertThrows(IllegalArgumentException.class, () -> manager.attachUserService(liveBinder(), options));
    }
}
