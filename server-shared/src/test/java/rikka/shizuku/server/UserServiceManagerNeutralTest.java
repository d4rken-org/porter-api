package rikka.shizuku.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.UserServiceConnection;
import eu.darken.porter.core.UserServiceOptions;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.util.HandlerUtil;

/** The neutral bind, remove and attach entry points of {@link UserServiceManager}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class UserServiceManagerNeutralTest {

    private static final String PACKAGE = "eu.darken.porter.probe";
    private static final String CLASS = "ProbeService";
    private static final String DESCRIPTOR = "eu.darken.porter.probe.IProbe";
    private static final int UID = 10123;
    private static final int PID = 45678;

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

    /** A connection that is not a Shizuku one, so nothing can quietly unwrap it. */
    private static class RecordingConnection implements UserServiceConnection {
        final IBinder binder = mock(IBinder.class);
        final List<IBinder> connected = new CopyOnWriteArrayList<>();
        int died;

        @Override
        public IBinder asBinder() {
            return binder;
        }

        @Override
        public void connected(IBinder service) {
            connected.add(service);
        }

        @Override
        public void died() {
            died++;
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

        // Anything falling back on Binder would authorise against an app id that owns nothing here.
        ShadowBinder.setCallingUid(UID + 500);
        ShadowBinder.setCallingPid(PID + 500);
        manager = new TestManager();
    }

    @After
    public void teardown() {
        packages.close();
        ShadowBinder.reset();
    }

    private static UserServiceOptions bind() {
        return new UserServiceOptions(
                new ComponentName(PACKAGE, CLASS), null, 1, null, false, false, true, false, true);
    }

    private static UserServiceOptions unbindKeepingTheRecord() {
        return new UserServiceOptions(
                new ComponentName(PACKAGE, CLASS), null, 1, null, false, false, true, false, false);
    }

    private IBinder liveBinder() throws Exception {
        IBinder binder = mock(IBinder.class);
        when(binder.pingBinder()).thenReturn(true);
        when(binder.getInterfaceDescriptor()).thenReturn(DESCRIPTOR);
        when(binder.transact(anyInt(), any(), any(), anyInt())).thenReturn(true);
        return binder;
    }

    @Test
    public void bindAuthorisesAgainstTheHandedIdentity() {
        assertThrows(SecurityException.class, () -> manager.addUserService(
                new CallerIdentity(UID + 1, PID), new RecordingConnection(), bind(),
                ShizukuApiConstants.SERVER_VERSION));
        assertEquals(0, manager.created.size());

        assertEquals(0, manager.addUserService(
                new CallerIdentity(UID, PID), new RecordingConnection(), bind(),
                ShizukuApiConstants.SERVER_VERSION));
        assertEquals(1, manager.created.size());
    }

    @Test
    public void attachByTokenStringBroadcastsToANeutralConnection() throws Exception {
        RecordingConnection connection = new RecordingConnection();
        manager.addUserService(new CallerIdentity(UID, PID), connection, bind(), ShizukuApiConstants.SERVER_VERSION);
        UserServiceRecord record = manager.created.get(0);

        IBinder service = liveBinder();
        manager.attachUserService(service, record.token, DESCRIPTOR);

        assertEquals(List.of(service), connection.connected);
    }

    @Test
    public void removeWithoutTheRemoveFlagUnregistersTheNeutralConnection() {
        RecordingConnection kept = new RecordingConnection();
        RecordingConnection dropped = new RecordingConnection();
        manager.addUserService(new CallerIdentity(UID, PID), kept, bind(), ShizukuApiConstants.SERVER_VERSION);
        UserServiceRecord record = manager.created.get(0);
        manager.addUserService(new CallerIdentity(UID, PID), dropped, bind(), ShizukuApiConstants.SERVER_VERSION);

        assertEquals(0, manager.removeUserService(new CallerIdentity(UID, PID), dropped, unbindKeepingTheRecord()));

        assertFalse(record.isRemoved());

        record.broadcastBinderDied();

        assertEquals(1, kept.died);
        assertEquals(0, dropped.died);
    }
}
