package rikka.shizuku.server;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.Os;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ServerProcess;
import eu.darken.porter.porsh.PorshConfig;
import eu.darken.porter.porsh.PorshService;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.hidden.compat.PermissionManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.RemoteProcessHolder;
import rikka.shizuku.server.legacy.LegacyServiceConnection;
import rikka.shizuku.server.legacy.LegacyUserServiceOptions;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.OsUtils;

public abstract class Service<
        UserServiceMgr extends UserServiceManager,
        ClientMgr extends ClientManager<ConfigMgr>,
        ConfigMgr extends ConfigManager> extends IShizukuService.Stub {

    private final UserServiceMgr userServiceManager;
    private final ConfigMgr configManager;
    private final ClientMgr clientManager;
    private final PorshService porshService;

    private static final int LEGACY_PORSH_TRANSACTION_BASE = 30000;

    protected static final Logger LOGGER = new Logger("Service");

    public Service() {
        PorshConfig.init(ShizukuApiConstants.BINDER_DESCRIPTOR, LEGACY_PORSH_TRANSACTION_BASE);

        userServiceManager = onCreateUserServiceManager();
        configManager = onCreateConfigManager();
        clientManager = onCreateClientManager();
        porshService = new PorshService(ShizukuApiConstants.BINDER_DESCRIPTOR, LEGACY_PORSH_TRANSACTION_BASE) {

            @Override
            public void enforceCallingPermission(String func) {
                Service.this.enforceCallingPermission(func);
            }
        };
    }

    public abstract UserServiceMgr onCreateUserServiceManager();

    public abstract ClientMgr onCreateClientManager();

    public abstract ConfigMgr onCreateConfigManager();

    public final UserServiceMgr getUserServiceManager() {
        return userServiceManager;
    }

    public final ClientMgr getClientManager() {
        return clientManager;
    }

    public ConfigMgr getConfigManager() {
        return configManager;
    }

    public boolean checkCallerManagerPermission(String func, int callingUid, int callingPid) {
        return false;
    }

    public final void enforceManagerPermission(String func) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingPid == Os.getpid()) {
            return;
        }

        if (checkCallerManagerPermission(func, callingUid, callingPid)) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + " is not manager ";
        LOGGER.w(msg);
        throw new SecurityException(msg);
    }

    public boolean checkCallerPermission(String func, int callingUid, int callingPid, @Nullable ClientRecord clientRecord) {
        return false;
    }

    public final void enforceCallingPermission(String func) {
        int callingUid = Binder.getCallingUid();
        int callingPid = Binder.getCallingPid();

        if (callingUid == OsUtils.getUid()) {
            return;
        }

        ClientRecord clientRecord = clientManager.findClient(callingUid, callingPid);

        if (checkCallerPermission(func, callingUid, callingPid, clientRecord)) {
            return;
        }

        if (clientRecord == null) {
            String msg = "Permission Denial: " + func + " from pid="
                    + Binder.getCallingPid()
                    + " is not an attached client";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }

        if (!clientRecord.allowed) {
            String msg = "Permission Denial: " + func + " from pid="
                    + Binder.getCallingPid()
                    + " requires permission";
            LOGGER.w(msg);
            throw new SecurityException(msg);
        }
    }

    public final void transactRemote(Parcel data, Parcel reply, int flags) throws RemoteException {
        enforceCallingPermission("transactRemote");

        CallerIdentity caller = CallerIdentity.fromBinder();

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

    @Override
    public final int getVersion() {
        enforceCallingPermission("getVersion");
        return ShizukuApiConstants.SERVER_VERSION;
    }

    @Override
    public final int getUid() {
        enforceCallingPermission("getUid");
        return Os.getuid();
    }

    @Override
    public final int checkPermission(String permission) throws RemoteException {
        enforceCallingPermission("checkPermission");
        return PermissionManagerApis.checkPermission(permission, Os.getuid());
    }

    @Override
    public final String getSELinuxContext() {
        enforceCallingPermission("getSELinuxContext");

        try {
            return SELinux.getContext();
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final String getSystemProperty(String name, String defaultValue) {
        enforceCallingPermission("getSystemProperty");

        try {
            return SystemProperties.get(name, defaultValue);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final void setSystemProperty(String name, String value) {
        enforceCallingPermission("setSystemProperty");

        try {
            SystemProperties.set(name, value);
        } catch (Throwable tr) {
            throw new IllegalStateException(tr.getMessage());
        }
    }

    @Override
    public final int removeUserService(IShizukuServiceConnection conn, Bundle options) {
        enforceCallingPermission("removeUserService");

        CallerIdentity caller = CallerIdentity.fromBinder();

        return userServiceManager.removeUserService(
                caller,
                conn == null ? null : new LegacyServiceConnection(conn),
                LegacyUserServiceOptions.decodeForRemove(options));
    }

    @Override
    public final int addUserService(IShizukuServiceConnection conn, Bundle options) {
        enforceCallingPermission("addUserService");

        CallerIdentity caller = CallerIdentity.fromBinder();
        int callingApiVersion;

        ClientRecord clientRecord = clientManager.findClient(caller.uid, caller.pid);
        if (clientRecord == null) {
            callingApiVersion = ShizukuApiConstants.SERVER_VERSION;
        } else {
            callingApiVersion = clientRecord.apiVersion;
        }

        Objects.requireNonNull(conn, "connection is null");
        Objects.requireNonNull(options, "options is null");

        return userServiceManager.addUserService(
                caller,
                new LegacyServiceConnection(conn),
                LegacyUserServiceOptions.decodeForBind(options),
                callingApiVersion);
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        userServiceManager.attachUserService(binder, options);
    }

    /**
     * For an override that has to acquire the interface descriptor before it takes its own monitor.
     * See {@link UserServiceManager#getInterfaceDescriptor(IBinder)}.
     */
    public void attachUserService(IBinder binder, Bundle options, String interfaceDescriptor) {
        userServiceManager.attachUserService(binder, options, interfaceDescriptor);
    }

    @Override
    public final boolean checkSelfPermission() {
        CallerIdentity caller = CallerIdentity.fromBinder();

        if (caller.uid == OsUtils.getUid() || caller.pid == OsUtils.getPid()) {
            return true;
        }

        return clientManager.requireClient(caller.uid, caller.pid).allowed;
    }

    @Override
    public final void requestPermission(int requestCode) {
        CallerIdentity caller = CallerIdentity.fromBinder();
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

        showPermissionConfirmation(requestCode, clientRecord, caller.uid, caller.pid, userId);
    }

    public abstract void showPermissionConfirmation(
            int requestCode, @NonNull ClientRecord clientRecord, int callingUid, int callingPid, int userId);

    @Override
    public final boolean shouldShowRequestPermissionRationale() {
        CallerIdentity caller = CallerIdentity.fromBinder();

        if (caller.uid == OsUtils.getUid() || caller.pid == OsUtils.getPid()) {
            return true;
        }

        clientManager.requireClient(caller.uid, caller.pid);

        ConfigPackageEntry entry = configManager.find(caller.uid);
        return entry != null && entry.isDenied();
    }

    public final ServerProcess newServerProcess(String[] cmd, String[] env, String dir) {
        enforceCallingPermission("newProcess");

        CallerIdentity caller = CallerIdentity.fromBinder();

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

    @Override
    public final IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return new RemoteProcessHolder(newServerProcess(cmd, env, dir));
    }

    @CallSuper
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            transactRemote(data, reply, flags);
            return true;
        } else if (code == 14 /* attachApplication <= v12 */) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            IBinder binder = data.readStrongBinder();
            String packageName = data.readString();
            Bundle args = new Bundle();
            args.putString(ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME, packageName);
            args.putInt(ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION, -1);
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args);
            reply.writeNoException();
            return true;
        } else if (porshService.onTransact(code, data, reply, flags)) {
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
