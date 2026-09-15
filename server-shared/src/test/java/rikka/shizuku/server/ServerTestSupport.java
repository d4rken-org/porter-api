package rikka.shizuku.server;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.Bundle;
import android.os.IBinder;

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
