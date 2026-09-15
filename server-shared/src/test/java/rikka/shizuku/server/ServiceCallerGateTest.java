package rikka.shizuku.server;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.system.Os;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;

import java.lang.reflect.Field;

import moe.shizuku.server.IShizukuApplication;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.OsUtils;

/**
 * Who {@link Service#enforceCallingPermission} and {@link Service#enforceManagerPermission} admit,
 * and with which message they refuse everyone else.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ServiceCallerGateTest {

    static final int CLIENT_UID = 10200;
    static final int CLIENT_PID = 45678;

    /**
     * The six {@code IShizukuService} methods {@link Service} does not implement stay abstract;
     * {@code mock(TestService.class, CALLS_REAL_METHODS)} answers those with defaults.
     */
    public abstract static class TestService
            extends Service<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> {

        public boolean callerPermission;
        public boolean managerPermission;

        public boolean confirmationShown;
        public int confirmationRequestCode;
        public ClientRecord confirmationRecord;
        public int confirmationUid;
        public int confirmationPid;
        public int confirmationUserId;

        public IShizukuApplication attachedApplication;
        public Bundle attachedArgs;

        @Override
        public UserServiceManager onCreateUserServiceManager() {
            return null;
        }

        @Override
        public ClientManager<ConfigManager> onCreateClientManager() {
            return null;
        }

        @Override
        public ConfigManager onCreateConfigManager() {
            return null;
        }

        @Override
        public boolean checkCallerPermission(String func, int callingUid, int callingPid, ClientRecord clientRecord) {
            return callerPermission;
        }

        @Override
        public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
            return managerPermission;
        }

        @Override
        public void showPermissionConfirmation(
                int requestCode, ClientRecord clientRecord, int callingUid, int callingPid, int userId) {
            confirmationShown = true;
            confirmationRequestCode = requestCode;
            confirmationRecord = clientRecord;
            confirmationUid = callingUid;
            confirmationPid = callingPid;
            confirmationUserId = userId;
        }

        @Override
        public void attachApplication(IShizukuApplication application, Bundle args) {
            attachedApplication = application;
            attachedArgs = args;
        }
    }

    /** A concrete manager so the injected field carries a real object rather than a mock. */
    public static class TestUserServiceManager extends UserServiceManager {
        @Override
        public String getUserServiceStartCmd(
                UserServiceRecord record, String key, String token, String packageName,
                String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {
            return "exit 0";
        }
    }

    /**
     * {@code Service()} loads the native porsh library, so the service under test is built without
     * running any constructor and the three manager fields are injected.
     */
    static TestService newService(
            ClientManager<ConfigManager> clientManager, UserServiceManager userServiceManager, ConfigManager configManager) {
        TestService service = mock(TestService.class, CALLS_REAL_METHODS);
        inject(service, "clientManager", clientManager);
        inject(service, "userServiceManager", userServiceManager);
        inject(service, "configManager", configManager);
        return service;
    }

    static void inject(Object service, String name, Object value) {
        try {
            Field field = Service.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** An application binder death can be fired from, which {@code addClient} needs to link to. */
    static IShizukuApplication application(IBinder binder) {
        IShizukuApplication application = mock(IShizukuApplication.class);
        when(application.asBinder()).thenReturn(binder);
        return application;
    }

    static ConfigPackageEntry entry(boolean allowed, boolean denied) {
        return new ConfigPackageEntry() {
            @Override public boolean isAllowed() { return allowed; }
            @Override public boolean isDenied() { return denied; }
        };
    }

    private TestService service;
    private ConfigManager config;
    private ClientManager<ConfigManager> clients;

    @Before
    public void setup() {
        HandlerUtil.setMainHandler(mock(Handler.class));
        config = mock(ConfigManager.class);
        clients = new ClientManager<>(config);
        service = newService(clients, new TestUserServiceManager(), config);

        ShadowBinder.setCallingUid(CLIENT_UID);
        ShadowBinder.setCallingPid(CLIENT_PID);
    }

    @After
    public void teardown() {
        ShadowBinder.reset();
    }

    private void attach(int apiVersion) {
        clients.addClient(CLIENT_UID, CLIENT_PID, application(mock(IBinder.class)), "eu.darken.porter.probe", apiVersion);
    }

    @Test
    public void serverUidPassesWithoutARecord() {
        ShadowBinder.setCallingUid(OsUtils.getUid());

        service.enforceCallingPermission("getVersion");
    }

    @Test
    public void unattachedCallerIsRefusedAsNotAttached() {
        SecurityException e = assertThrows(SecurityException.class,
                () -> service.enforceCallingPermission("getVersion"));

        assertTrue(e.getMessage(), e.getMessage().contains("is not an attached client"));
    }

    @Test
    public void attachedButDisallowedClientIsRefusedAsRequiresPermission() {
        when(config.find(CLIENT_UID)).thenReturn(entry(false, false));
        attach(13);

        SecurityException e = assertThrows(SecurityException.class,
                () -> service.enforceCallingPermission("getVersion"));

        assertTrue(e.getMessage(), e.getMessage().contains("requires permission"));
    }

    @Test
    public void attachedAllowedClientPasses() {
        when(config.find(CLIENT_UID)).thenReturn(entry(true, false));
        attach(13);

        service.enforceCallingPermission("getVersion");
    }

    @Test
    public void overrideAnsweringTrueAdmitsAnUnattachedCaller() {
        service.callerPermission = true;

        service.enforceCallingPermission("transactRemote");
    }

    @Test
    public void managerGatePassesForOwnPid() {
        ShadowBinder.setCallingPid(Os.getpid());

        service.enforceManagerPermission("exit");
    }

    @Test
    public void managerGatePassesWhenOverrideAgrees() {
        service.managerPermission = true;

        service.enforceManagerPermission("exit");
    }

    @Test
    public void managerGateRefusesOtherwise() {
        SecurityException e = assertThrows(SecurityException.class,
                () -> service.enforceManagerPermission("exit"));

        assertTrue(e.getMessage(), e.getMessage().contains("is not manager"));
    }
}
