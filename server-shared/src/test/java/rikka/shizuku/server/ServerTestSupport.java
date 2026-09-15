package rikka.shizuku.server;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.PorterCore;
import eu.darken.porter.core.ServerPolicy;
import moe.shizuku.server.IShizukuApplication;

/** The server pieces every suite in this module builds its subject out of. */
public final class ServerTestSupport {

    private ServerTestSupport() {
    }

    /** A policy that answers what a test set on it and remembers what it was told. */
    public static class TestPolicy implements ServerPolicy {

        public boolean callerPermission;
        public boolean managerPermission;

        public boolean confirmationShown;
        public int confirmationRequestCode;
        public ClientRecord confirmationRecord;
        public int confirmationUid;
        public int confirmationPid;
        public int confirmationUserId;

        public final List<CallerIdentity> attachingCallers = new ArrayList<>();
        public final List<String> attachingPackages = new ArrayList<>();
        public final List<ClientRecord> attachedRecords = new ArrayList<>();
        public final List<Boolean> attachedCreated = new ArrayList<>();
        public final List<Bundle> attachedReplies = new ArrayList<>();
        public final List<ClientRecord> boundRecords = new ArrayList<>();
        public final List<Boolean> boundCreated = new ArrayList<>();

        @Override
        public boolean checkCallerPermission(String func, CallerIdentity caller, ClientRecord record) {
            return callerPermission;
        }

        @Override
        public boolean checkCallerManagerPermission(String func, CallerIdentity caller) {
            return managerPermission;
        }

        @Override
        public void showPermissionConfirmation(
                int requestCode, ClientRecord record, CallerIdentity caller, int userId) {
            confirmationShown = true;
            confirmationRequestCode = requestCode;
            confirmationRecord = record;
            confirmationUid = caller.uid;
            confirmationPid = caller.pid;
            confirmationUserId = userId;
        }

        @Override
        public void onAttaching(CallerIdentity caller, String packageName) {
            attachingCallers.add(caller);
            attachingPackages.add(packageName);
        }

        @Override
        public void onAttached(ClientRecord record, boolean created, Bundle reply) {
            attachedRecords.add(record);
            attachedCreated.add(created);
            attachedReplies.add(reply);
        }

        @Override
        public void onBound(ClientRecord record, boolean created) {
            boundRecords.add(record);
            boundCreated.add(created);
        }
    }

    /** A concrete manager so the core carries a real object rather than a mock. */
    public static class TestUserServiceManager extends UserServiceManager {
        @Override
        public String getUserServiceStartCmd(
                UserServiceRecord record, String key, String token, String packageName,
                String classname, String processNameSuffix, int callingUid, boolean use32Bits, boolean debug) {
            return "exit 0";
        }
    }

    public static PorterCore<UserServiceManager, ClientManager<ConfigManager>, ConfigManager> newCore(
            ClientManager<ConfigManager> clientManager,
            UserServiceManager userServiceManager,
            ConfigManager configManager,
            ServerPolicy policy,
            IntFunction<List<String>> packagesForUid) {
        return new PorterCore<>(userServiceManager, clientManager, configManager, policy, packagesForUid);
    }

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

    /**
     * {@code Service()} loads the native porsh library, so the service under test is built without
     * running any constructor and the three manager fields are injected.
     */
    public static TestService newService(
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
    public static IShizukuApplication application(IBinder binder) {
        IShizukuApplication application = mock(IShizukuApplication.class);
        when(application.asBinder()).thenReturn(binder);
        return application;
    }

    public static ConfigPackageEntry entry(boolean allowed, boolean denied) {
        return new ConfigPackageEntry() {
            @Override public boolean isAllowed() { return allowed; }
            @Override public boolean isDenied() { return denied; }
        };
    }
}
