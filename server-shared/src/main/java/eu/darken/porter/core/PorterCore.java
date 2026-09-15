package eu.darken.porter.core;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.Os;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import rikka.hidden.compat.PackageManagerApis;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.ConfigManager;
import rikka.shizuku.server.ConfigPackageEntry;
import rikka.shizuku.server.UserServiceManager;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.OsUtils;

/**
 * Every operation an endpoint answers with, and the managers it answers from. The caller is handed
 * in rather than read from Binder, so an operation is the same one whichever wire reached it.
 */
public class PorterCore<
        UserServiceMgr extends UserServiceManager,
        ClientMgr extends ClientManager<ConfigMgr>,
        ConfigMgr extends ConfigManager> {

    protected static final Logger LOGGER = new Logger("PorterCore");

    private final UserServiceMgr userServiceManager;
    private final ClientMgr clientManager;
    private final ConfigMgr configManager;
    private final ServerPolicy policy;
    private final IntFunction<List<String>> packagesForUid;

    public PorterCore(
            UserServiceMgr userServiceManager,
            ClientMgr clientManager,
            ConfigMgr configManager,
            ServerPolicy policy) {
        this(userServiceManager, clientManager, configManager, policy,
                PackageManagerApis::getPackagesForUidNoThrow);
    }

    public PorterCore(
            UserServiceMgr userServiceManager,
            ClientMgr clientManager,
            ConfigMgr configManager,
            ServerPolicy policy,
            IntFunction<List<String>> packagesForUid) {
        this.userServiceManager = Objects.requireNonNull(userServiceManager, "user service manager is null");
        this.clientManager = Objects.requireNonNull(clientManager, "client manager is null");
        this.configManager = Objects.requireNonNull(configManager, "config manager is null");
        this.policy = Objects.requireNonNull(policy, "policy is null");
        this.packagesForUid = Objects.requireNonNull(packagesForUid, "package lookup is null");
    }

    public final UserServiceMgr getUserServiceManager() {
        return userServiceManager;
    }

    public final ClientMgr getClientManager() {
        return clientManager;
    }

    public final ConfigMgr getConfigManager() {
        return configManager;
    }

    public final ServerPolicy getPolicy() {
        return policy;
    }

    public final void enforceCallingPermission(String func, CallerIdentity caller) {
        if (caller.uid == OsUtils.getUid()) {
            return;
        }

        ClientRecord clientRecord = clientManager.findClient(caller.uid, caller.pid);

        if (policy.checkCallerPermission(func, caller, clientRecord)) {
            return;
        }

        if (clientRecord == null) {
            String msg = "Permission Denial: " + func + " from pid="
                    + caller.pid
                    + " is not an attached client";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }

        if (!clientRecord.allowed) {
            String msg = "Permission Denial: " + func + " from pid="
                    + caller.pid
                    + " requires permission";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }
    }

    public final void enforceManagerPermission(String func, CallerIdentity caller) {
        if (caller.pid == Os.getpid()) {
            return;
        }

        if (policy.checkCallerManagerPermission(func, caller)) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + caller.pid
                + " is not manager ";
        LOGGER.w(msg);
        throw new SecurityException(msg);
    }

    /**
     * Finds or creates the caller's record. A process is attached through one endpoint only: a
     * second endpoint reaching an existing record is refused rather than given a second record.
     */
    public final AttachResult attach(
            @NonNull CallerIdentity caller,
            @NonNull String packageName,
            @NonNull ClientCallback callback,
            int apiLevel) {
        // Never null: getPackagesForUidNoThrow answers with an empty list when it cannot look up.
        if (!packagesForUid.apply(caller.uid).contains(packageName)) {
            throw new SecurityException(
                    "Request package " + packageName + " does not belong to uid " + caller.uid);
        }

        policy.onAttaching(caller, packageName);

        // One critical section: a second attach from the same process must find what the first left.
        synchronized (clientManager) {
            ClientRecord record = clientManager.findClient(caller.uid, caller.pid);
            if (record == null) {
                record = clientManager.attach(caller, callback, packageName, apiLevel);
                if (record == null) {
                    throw new IllegalStateException("client binder is dead");
                }
                return new AttachResult(record, true);
            }
            if (record.callback.getClass() != callback.getClass()) {
                throw new IllegalStateException("uid " + caller.uid + "/pid " + caller.pid
                        + " is attached through another endpoint");
            }
            if (!record.packageName.equals(packageName)) {
                throw new SecurityException("uid " + caller.uid + "/pid " + caller.pid
                        + " is attached as " + record.packageName);
            }
            return new AttachResult(record, false);
        }
    }

    public final void transactRemote(CallerIdentity caller, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        enforceCallingPermission("transactRemote", caller);

        IBinder targetBinder = data.readStrongBinder();
        int targetCode = data.readInt();
        int targetFlags;

        ClientRecord clientRecord = clientManager.findClient(caller.uid, caller.pid);

        if (clientRecord != null && clientRecord.apiVersion >= 13) {
            targetFlags = data.readInt();
        } else {
            targetFlags = flags;
        }

        if (Logger.debugEnabled()) {
            // Best effort: a descriptor lookup that fails must not stop the caller's transaction
            // from being forwarded, so diagnostics can never change what a client observes.
            String descriptor;
            try {
                descriptor = targetBinder.getInterfaceDescriptor();
            } catch (Throwable tr) {
                descriptor = "<unavailable>";
            }
            LOGGER.d("transact: uid=%d, descriptor=%s, code=%d", caller.uid, descriptor, targetCode);
        }
        Parcel newData = Parcel.obtain();
        try {
            newData.appendFrom(data, data.dataPosition(), data.dataAvail());
        } catch (Throwable tr) {
            LOGGER.w(tr, "appendFrom");
            return;
        }
        try {
            long id = Binder.clearCallingIdentity();
            targetBinder.transact(targetCode, newData, reply, targetFlags);
            Binder.restoreCallingIdentity(id);
        } finally {
            newData.recycle();
        }
    }

    public final int getUid(CallerIdentity caller) {
        enforceCallingPermission("getUid", caller);
        return Os.getuid();
    }

    public final int checkPermission(CallerIdentity caller, String permission) throws RemoteException {
        enforceCallingPermission("checkPermission", caller);
        return PermissionManagerApis.checkPermission(permission, Os.getuid());
    }

    public final String getSELinuxContext(CallerIdentity caller) {
        enforceCallingPermission("getSELinuxContext", caller);

        try {
            return SELinux.getContext();
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    public final String getSystemProperty(CallerIdentity caller, String name, String defaultValue) {
        enforceCallingPermission("getSystemProperty", caller);

        try {
            return SystemProperties.get(name, defaultValue);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    public final void setSystemProperty(CallerIdentity caller, String name, String value) {
        enforceCallingPermission("setSystemProperty", caller);

        try {
            SystemProperties.set(name, value);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    public final ServerProcess newServerProcess(CallerIdentity caller, String[] cmd, String[] env, String dir) {
        enforceCallingPermission("newProcess", caller);

        if (Logger.debugEnabled()) {
            LOGGER.d("newProcess: uid=%d, cmd=%s, env=%s, dir=%s", caller.uid,
                    Arrays.toString(cmd), Arrays.toString(env), dir);
        }

        java.lang.Process process;
        try {
            process = Runtime.getRuntime().exec(cmd, env, dir != null ? new File(dir) : null);
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage());
        }

        ClientRecord clientRecord = clientManager.findClient(caller.uid, caller.pid);
        IBinder token = clientRecord != null ? clientRecord.callback.asBinder() : null;

        return new ServerProcess(process, token);
    }

    public final int addUserService(
            CallerIdentity caller, UserServiceConnection conn, UserServiceOptions options, int callingApiLevel) {
        enforceCallingPermission("addUserService", caller);

        Objects.requireNonNull(conn, "connection is null");
        Objects.requireNonNull(options, "options is null");

        return userServiceManager.addUserService(caller, conn, options, callingApiLevel);
    }

    public final int removeUserService(
            CallerIdentity caller, @Nullable UserServiceConnection conn, UserServiceOptions options) {
        enforceCallingPermission("removeUserService", caller);

        return userServiceManager.removeUserService(caller, conn, options);
    }

    /** No gate of its own: the endpoints enforce the manager permission before they reach this. */
    public void attachUserService(IBinder binder, String token, @Nullable String interfaceDescriptor) {
        userServiceManager.attachUserService(binder, token, interfaceDescriptor);
    }

    public final boolean checkSelfPermission(CallerIdentity caller) {
        if (caller.uid == OsUtils.getUid() || caller.pid == OsUtils.getPid()) {
            return true;
        }

        return clientManager.requireClient(caller.uid, caller.pid).allowed;
    }

    public final void requestPermission(CallerIdentity caller, int requestCode) {
        int userId = caller.userId();

        if (caller.uid == OsUtils.getUid() || caller.pid == OsUtils.getPid()) {
            return;
        }

        ClientRecord clientRecord = clientManager.requireClient(caller.uid, caller.pid);

        if (clientRecord.allowed) {
            clientRecord.dispatchRequestPermissionResult(requestCode, true);
            return;
        }

        ConfigPackageEntry entry = configManager.find(caller.uid);
        if (entry != null && entry.isDenied()) {
            clientRecord.dispatchRequestPermissionResult(requestCode, false);
            return;
        }

        policy.showPermissionConfirmation(requestCode, clientRecord, caller, userId);
    }

    public final boolean shouldShowRequestPermissionRationale(CallerIdentity caller) {
        if (caller.uid == OsUtils.getUid() || caller.pid == OsUtils.getPid()) {
            return true;
        }

        clientManager.requireClient(caller.uid, caller.pid);

        ConfigPackageEntry entry = configManager.find(caller.uid);
        return entry != null && entry.isDenied();
    }

    /** The API level a legacy caller is answered at: its own, or the current one when unrecorded. */
    public final int legacyApiLevelOf(CallerIdentity caller) {
        ClientRecord clientRecord = clientManager.findClient(caller.uid, caller.pid);
        return clientRecord == null ? ShizukuApiConstants.SERVER_VERSION : clientRecord.apiVersion;
    }
}
