package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_API_VERSION;
import static rikka.shizuku.ShizukuApiConstants.ATTACH_APPLICATION_PACKAGE_NAME;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_UID;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION;
import static rikka.shizuku.ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;
import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_IS_ONETIME;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.CallSuper;

import java.util.Objects;

import eu.darken.porter.core.AttachResult;
import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ManagerOperations;
import eu.darken.porter.core.PorterCore;
import eu.darken.porter.porsh.PorshService;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.api.RemoteProcessHolder;
import rikka.shizuku.server.legacy.LegacyClientCallback;
import rikka.shizuku.server.legacy.LegacyServiceConnection;
import rikka.shizuku.server.legacy.LegacyUserServiceOptions;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.OsUtils;

/**
 * The Shizuku wire, answered by the same core the Porter endpoint answers from. The server
 * bootstrap must call {@code PorshConfig.init(BINDER_DESCRIPTOR, 30000)} before serving terminal
 * transactions; this class does not load the native library.
 */
public class ShizukuLegacyEndpoint extends IShizukuService.Stub {

    protected static final Logger LOGGER = new Logger("ShizukuLegacyEndpoint");

    private static final int LEGACY_PORSH_TRANSACTION_BASE = 30000;
    /** What a client that attached before v13 is told the server is. */
    private static final int LEGACY_SERVER_VERSION = 12;

    private final PorterCore<?, ?, ?> core;
    private final ManagerOperations managerOperations;
    private final PorshService porshService;

    public ShizukuLegacyEndpoint(PorterCore<?, ?, ?> core, ManagerOperations managerOperations) {
        this.core = core;
        this.managerOperations = managerOperations;
        this.porshService = new PorshService(
                ShizukuApiConstants.BINDER_DESCRIPTOR, LEGACY_PORSH_TRANSACTION_BASE) {

            @Override
            public void enforceCallingPermission(String func) {
                core.enforceCallingPermission(func, CallerIdentity.fromBinder());
            }
        };
    }

    @Override
    public final void attachApplication(IShizukuApplication application, Bundle args) {
        if (application == null || args == null) {
            return;
        }

        String packageName = args.getString(ATTACH_APPLICATION_PACKAGE_NAME);
        if (packageName == null) {
            return;
        }
        int apiVersion = args.getInt(ATTACH_APPLICATION_API_VERSION, -1);

        CallerIdentity caller = CallerIdentity.fromBinder();
        AttachResult result = core.attach(
                caller, packageName, new LegacyClientCallback(application), apiVersion);

        Bundle reply = new Bundle();
        reply.putInt(BIND_APPLICATION_SERVER_UID, OsUtils.getUid());
        reply.putInt(BIND_APPLICATION_SERVER_VERSION,
                apiVersion == -1 ? LEGACY_SERVER_VERSION : ShizukuApiConstants.SERVER_VERSION);
        reply.putString(BIND_APPLICATION_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
        reply.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION);
        reply.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, result.record.allowed);
        reply.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);

        core.getPolicy().onAttached(result.record, result.created, reply);

        try {
            application.bindApplication(reply);
            core.getPolicy().onBound(result.record, result.created);
        } catch (Throwable e) {
            LOGGER.w(e, "attachApplication");
        }
    }

    @Override
    public final int getVersion() {
        core.enforceCallingPermission("getVersion", CallerIdentity.fromBinder());
        return ShizukuApiConstants.SERVER_VERSION;
    }

    @Override
    public final int getUid() {
        return core.getUid(CallerIdentity.fromBinder());
    }

    @Override
    public final int checkPermission(String permission) throws RemoteException {
        return core.checkPermission(CallerIdentity.fromBinder(), permission);
    }

    @Override
    public final String getSELinuxContext() {
        return core.getSELinuxContext(CallerIdentity.fromBinder());
    }

    @Override
    public final String getSystemProperty(String name, String defaultValue) {
        return core.getSystemProperty(CallerIdentity.fromBinder(), name, defaultValue);
    }

    @Override
    public final void setSystemProperty(String name, String value) {
        core.setSystemProperty(CallerIdentity.fromBinder(), name, value);
    }

    @Override
    public final IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return new RemoteProcessHolder(
                core.newServerProcess(CallerIdentity.fromBinder(), cmd, env, dir));
    }

    @Override
    public final int addUserService(IShizukuServiceConnection conn, Bundle options) {
        CallerIdentity caller = CallerIdentity.fromBinder();
        // Before any Bundle content is read: an unauthorized caller is refused, never told what
        // their options decoded to.
        core.enforceCallingPermission("addUserService", caller);

        Objects.requireNonNull(conn, "connection is null");
        Objects.requireNonNull(options, "options is null");

        return core.addUserService(
                caller,
                new LegacyServiceConnection(conn),
                LegacyUserServiceOptions.decodeForBind(options),
                core.legacyApiLevelOf(caller));
    }

    @Override
    public final int removeUserService(IShizukuServiceConnection conn, Bundle options) {
        CallerIdentity caller = CallerIdentity.fromBinder();
        core.enforceCallingPermission("removeUserService", caller);

        return core.removeUserService(
                caller,
                conn == null ? null : new LegacyServiceConnection(conn),
                LegacyUserServiceOptions.decodeForRemove(options));
    }

    @Override
    public final boolean checkSelfPermission() {
        return core.checkSelfPermission(CallerIdentity.fromBinder());
    }

    @Override
    public final void requestPermission(int requestCode) {
        core.requestPermission(CallerIdentity.fromBinder(), requestCode);
    }

    @Override
    public final boolean shouldShowRequestPermissionRationale() {
        return core.shouldShowRequestPermissionRationale(CallerIdentity.fromBinder());
    }

    @Override
    public final void exit() {
        core.enforceManagerPermission("exit", CallerIdentity.fromBinder());
        managerOperations.exit();
    }

    @Override
    public final void attachUserService(IBinder binder, Bundle options) {
        core.enforceManagerPermission("attachUserService", CallerIdentity.fromBinder());
        managerOperations.attachUserService(binder, LegacyUserServiceOptions.decodeToken(options));
    }

    @Override
    public final void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, Bundle data) {
        core.enforceManagerPermission("dispatchPermissionConfirmationResult", CallerIdentity.fromBinder());

        if (data == null) {
            return;
        }

        managerOperations.dispatchPermissionConfirmationResult(
                requestUid,
                requestPid,
                requestCode,
                data.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false),
                data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, false));
    }

    @Override
    public final int getFlagsForUid(int uid, int mask) {
        core.enforceManagerPermission("getFlagsForUid", CallerIdentity.fromBinder());
        return managerOperations.getFlagsForUid(uid, mask);
    }

    @Override
    public final void updateFlagsForUid(int uid, int mask, int value) {
        core.enforceManagerPermission("updateFlagsForUid", CallerIdentity.fromBinder());
        managerOperations.updateFlagsForUid(uid, mask, value);
    }

    @Override
    public final void dispatchPackageChanged(Intent intent) {
    }

    @Override
    public final boolean isHidden(int uid) {
        return false;
    }

    @CallSuper
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == ShizukuApiConstants.BINDER_TRANSACTION_transact) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            core.transactRemote(CallerIdentity.fromBinder(), data, reply, flags);
            return true;
        } else if (code == 14 /* attachApplication <= v12 */) {
            data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR);
            IBinder binder = data.readStrongBinder();
            String packageName = data.readString();
            Bundle args = new Bundle();
            args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName);
            args.putInt(ATTACH_APPLICATION_API_VERSION, -1);
            attachApplication(IShizukuApplication.Stub.asInterface(binder), args);
            reply.writeNoException();
            return true;
        } else if (porshService.onTransact(code, data, reply, flags)) {
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
