package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.util.HandlerUtil;

/** Removal, detach and launch-cancellation behaviour of {@link UserServiceManager}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class UserServiceLifecycleTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final int APP_ID = 10123;
    private static final int UID = APP_ID;
    private static final String DESCRIPTOR = "eu.darken.porter.probe.IProbe";

    private TestManager manager;
    private MockedStatic<PackageManagerApis> packages;
    private PackageInfo installed;
    private final AtomicReference<Runnable> pendingTimeout = new AtomicReference<>();

    private static class TestManager extends UserServiceManager {
        final List<UserServiceRecord> created = new CopyOnWriteArrayList<>();
        final List<UserServiceRecord> detached = new CopyOnWriteArrayList<>();
        final List<String> spawned = new CopyOnWriteArrayList<>();
        Function<String, String> startCmd = key -> "exit 0";

        @Override
        public String getUserServiceStartCmd(
                UserServiceRecord record, String key, String token, String packageName,
                String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {
            spawned.add(key);
            return startCmd.apply(key);
        }

        @Override
        public void onUserServiceRecordCreated(UserServiceRecord record, PackageInfo packageInfo) {
            created.add(record);
        }

        @Override
        public void onUserServiceRecordRemoved(UserServiceRecord record) {
            detached.add(record);
        }
    }

    @Before
    public void setup() {
        // A real handler would drop the timeout callback on removal, hiding the guard inside it.
        Handler handler = mock(Handler.class);
        when(handler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            pendingTimeout.set(invocation.getArgument(0));
            return true;
        });
        HandlerUtil.setMainHandler(handler);

        installed = new PackageInfo();
        installed.packageName = PACKAGE;
        installed.applicationInfo = new ApplicationInfo();
        installed.applicationInfo.uid = UID;
        installed.applicationInfo.sourceDir = "/data/app/porter-probe/base.apk";
        installed.signatures = new Signature[]{new Signature("0a0b")};

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
        return new IShizukuServiceConnection.Stub() {
            @Override public void connected(IBinder service) {}
            @Override public void died() {}
        };
    }

    private Bundle options(String className) {
        Bundle options = new Bundle();
        options.putParcelable(ShizukuApiConstants.USER_SERVICE_ARG_COMPONENT, new ComponentName(PACKAGE, className));
        options.putInt(ShizukuApiConstants.USER_SERVICE_ARG_VERSION_CODE, 1);
        options.putBoolean(ShizukuApiConstants.USER_SERVICE_ARG_DAEMON, true);
        return options;
    }

    private UserServiceRecord add(String className) {
        int before = manager.created.size();
        manager.addUserService(connection(), options(className), ShizukuApiConstants.SERVER_VERSION);
        assertEquals(before + 1, manager.created.size());
        return manager.created.get(manager.created.size() - 1);
    }

    /** Publishes {@code binder} for {@code record} the way the starter's attach path does. */
    private void attach(UserServiceRecord record, IBinder binder) {
        Bundle options = new Bundle();
        options.putString(ShizukuApiConstants.USER_SERVICE_ARG_TOKEN, record.token);
        manager.attachUserService(binder, options);
    }

    private IBinder liveBinder(List<Integer> transactions) throws Exception {
        IBinder binder = mock(IBinder.class);
        when(binder.pingBinder()).thenReturn(true);
        when(binder.getInterfaceDescriptor()).thenReturn(DESCRIPTOR);
        when(binder.transact(anyInt(), any(), any(), anyInt())).thenAnswer(invocation -> {
            transactions.add(invocation.getArgument(0));
            return true;
        });
        return binder;
    }

    @SuppressWarnings("unchecked")
    private <T> T index(String name) {
        try {
            Field field = UserServiceManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Map<String, UserServiceRecord> byKey() {
        return new HashMap<>(this.<Map<String, UserServiceRecord>>index("userServiceRecords"));
    }

    private Map<String, List<UserServiceRecord>> byPackage() {
        return new HashMap<>(this.<Map<String, List<UserServiceRecord>>>index("packageUserServiceRecords"));
    }

    /** The cleanup executor is single threaded, so a task queued behind ours has drained it. */
    private void drainCleanup() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        ((java.util.concurrent.Executor) index("cleanupExecutor")).execute(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void aSecondRemovalDoesNotDestroyTheServiceTwice() throws Exception {
        List<Integer> transactions = new CopyOnWriteArrayList<>();
        UserServiceRecord record = add("ProbeService");
        attach(record, liveBinder(transactions));

        record.removeSelf();
        record.removeSelf();
        drainCleanup();

        assertEquals(List.of(ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy), transactions);
        assertEquals(1, manager.detached.size());
    }

    @Test
    public void removingAPackageRemovesEveryRecordAndTheListItself() throws Exception {
        UserServiceRecord first = add("ProbeService");
        UserServiceRecord second = add("OtherService");

        manager.removeUserServicesForPackage(PACKAGE);
        drainCleanup();

        assertEquals(List.of(first, second), new ArrayList<>(manager.detached));
        assertTrue(byKey().isEmpty());
        assertFalse(byPackage().containsKey(PACKAGE));
    }

    @Test
    public void binderDeathPrunesBothIndexes() throws Exception {
        UserServiceRecord record = add("ProbeService");
        IBinder binder = liveBinder(new CopyOnWriteArrayList<>());
        attach(record, binder);

        org.mockito.ArgumentCaptor<IBinder.DeathRecipient> recipient =
                org.mockito.ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        Mockito.verify(binder).linkToDeath(recipient.capture(), eq(0));
        recipient.getValue().binderDied();
        drainCleanup();

        assertTrue(record.isRemoved());
        assertTrue(byKey().isEmpty());
        assertFalse(byPackage().containsKey(PACKAGE));
    }

    @Test
    public void aStartTimeoutThatFiresAfterDetachIsANoOp() throws Exception {
        UserServiceRecord record = add("ProbeService");
        Runnable timeout = pendingTimeout.get();
        assertNotNull(timeout);

        record.removeSelf();
        drainCleanup();
        assertEquals(1, manager.detached.size());

        timeout.run();

        assertEquals(1, manager.detached.size());
    }

    @Test
    public void aRemovalWhileTheStartTaskWaitsPreventsTheSpawn() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch sentinel = new CountDownLatch(1);
        manager.startCmd = key -> {
            if (key.endsWith(":Blocker")) {
                try {
                    assertTrue(blocked.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else if (key.endsWith(":Sentinel")) {
                sentinel.countDown();
            }
            // Never reaching Runtime.exec keeps a real shell out of the test; the executor replaces
            // the worker and drains what is still queued.
            throw new IllegalStateException("no shell in tests");
        };

        add("Blocker");
        UserServiceRecord victim = add("Victim");
        add("Sentinel");

        victim.removeSelf();
        blocked.countDown();
        assertTrue(sentinel.await(5, TimeUnit.SECONDS));

        assertFalse(manager.spawned.contains(PACKAGE + ":Victim"));
        assertTrue(manager.spawned.contains(PACKAGE + ":Sentinel"));
    }

    @Test
    public void destroyAgainstADeadRemoteStillKillsTheCallbacks() throws Exception {
        UserServiceRecord record = add("ProbeService");
        IBinder binder = mock(IBinder.class);
        when(binder.pingBinder()).thenReturn(true);
        when(binder.getInterfaceDescriptor()).thenReturn(DESCRIPTOR);
        when(binder.transact(anyInt(), any(), any(), anyInt())).thenThrow(new DeadObjectException());
        attach(record, binder);

        record.removeSelf();
        drainCleanup();

        assertFalse(record.callbacks.register(connection()));
    }

    @Test
    public void aStartTimeoutRecordIsDestroyedWithoutABinder() throws Exception {
        UserServiceRecord record = add("ProbeService");

        record.removeSelf();
        drainCleanup();

        assertFalse(record.callbacks.register(connection()));
    }

    @Test
    public void tokenLivenessFollowsTheRecord() throws Exception {
        UserServiceRecord record = add("ProbeService");
        assertTrue(manager.isUserServiceTokenLive(record.token));
        assertFalse(manager.isUserServiceTokenLive("not-a-token"));
        assertFalse(manager.isUserServiceTokenLive(null));

        record.removeSelf();
        drainCleanup();

        assertFalse(manager.isUserServiceTokenLive(record.token));
    }

    @Test
    public void theAuthorisingLookupAlsoCarriesSignatures() {
        PackageInfo packageInfo = manager.ensureCallingPackageForUserService(PACKAGE, APP_ID, 0);

        assertEquals(installed, packageInfo);
        packages.verify(() -> PackageManagerApis.getPackageInfoNoThrow(
                eq(PACKAGE),
                org.mockito.ArgumentMatchers.longThat(flags ->
                        (flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0
                                && (flags & 0x00002000L) != 0),
                eq(0)));
    }

    @Test
    public void theAuthorisingLookupStillRejectsAForeignPackage() {
        assertThrows(SecurityException.class,
                () -> manager.ensureCallingPackageForUserService(PACKAGE, APP_ID + 1, 0));
    }
}
