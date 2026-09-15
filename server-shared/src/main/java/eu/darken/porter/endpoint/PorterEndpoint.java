package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN;

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
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterService;
import eu.darken.porter.server.IPorterServiceConnection;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.util.Logger;
import rikka.shizuku.server.util.OsUtils;

/**
 * Porter's own client endpoint. Every operation is answered by the same core the Shizuku endpoint
 * answers from, so the two wires cannot drift apart in what they permit or record.
 */
public class PorterEndpoint extends IPorterService.Stub {

    private static final Logger LOGGER = new Logger("PorterEndpoint");

    /**
     * The legacy API level whose semantics the neutral entry points implement. Porter records carry
     * it so {@code transactRemote} reads in-parcel flags and {@code addUserService} answers with
     * version codes.
     */
    static final int CORE_API_LEVEL = ShizukuApiConstants.SERVER_VERSION;

    private final PorterCore<?, ?, ?> core;
    private final ManagerOperations managerOperations;
    private final PorshService porshService;

    public PorterEndpoint(PorterCore<?, ?, ?> core, ManagerOperations managerOperations) {
        this.core = core;
        this.managerOperations = managerOperations;
        this.porshService = new PorshService(PorterProtocol.DESCRIPTOR, PorterProtocol.TRANSACTION_PORSH_BASE) {

            @Override
            public void enforceCallingPermission(String func) {
                core.enforceCallingPermission(func, CallerIdentity.fromBinder());
            }
        };
    }

    @Override
    public Bundle attach(IPorterApplication application, Bundle args) {
        Objects.requireNonNull(application, "application is null");
        Objects.requireNonNull(args, "args is null");

        String packageName = Objects.requireNonNull(args.getString(ATTACH_PACKAGE_NAME), "package name is null");
        CallerIdentity caller = CallerIdentity.fromBinder();

        LOGGER.d("attach: uid=%d, pid=%d, package=%s, protocol=%d",
                caller.uid, caller.pid, packageName, args.getInt(ATTACH_PROTOCOL_VERSION, 0));

        AttachResult result = core.attach(
                caller, packageName, new PorterClientCallback(application), CORE_API_LEVEL);

        Bundle reply = new Bundle();
        reply.putInt(REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION);
        reply.putInt(REPLY_SERVER_UID, OsUtils.getUid());
        reply.putString(REPLY_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
        reply.putBoolean(REPLY_PERMISSION_GRANTED, result.record.allowed);
        reply.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        reply.putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE);

        core.getPolicy().onAttached(result.record, result.created, reply);
        // The synchronous reply is the delivery: returning it is what a bindApplication call is on
        // the Shizuku wire.
        core.getPolicy().onBound(result.record, result.created);
        return reply;
    }

    @Override
    public int getUid() {
        return core.getUid(CallerIdentity.fromBinder());
    }

    @Override
    public int checkPermission(String permission) throws RemoteException {
        return core.checkPermission(CallerIdentity.fromBinder(), permission);
    }

    @Override
    public String getSELinuxContext() {
        return core.getSELinuxContext(CallerIdentity.fromBinder());
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        return core.getSystemProperty(CallerIdentity.fromBinder(), name, defaultValue);
    }

    @Override
    public void setSystemProperty(String name, String value) {
        core.setSystemProperty(CallerIdentity.fromBinder(), name, value);
    }

    @Override
    public IPorterRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return new PorterRemoteProcessHolder(
                core.newServerProcess(CallerIdentity.fromBinder(), cmd, env, dir));
    }

    @Override
    public int addUserService(IPorterServiceConnection conn, Bundle args) {
        CallerIdentity caller = CallerIdentity.fromBinder();
        // Before any Bundle content is read: an unauthorized caller is refused, never told what
        // their arguments decoded to.
        core.enforceCallingPermission("addUserService", caller);

        Objects.requireNonNull(conn, "connection is null");
        Objects.requireNonNull(args, "args is null");

        return core.addUserService(
                caller,
                new PorterServiceConnection(conn),
                PorterUserServiceOptions.decodeForBind(args),
                CORE_API_LEVEL);
    }

    @Override
    public int removeUserService(IPorterServiceConnection conn, Bundle args) {
        CallerIdentity caller = CallerIdentity.fromBinder();
        core.enforceCallingPermission("removeUserService", caller);

        return core.removeUserService(
                caller,
                conn == null ? null : new PorterServiceConnection(conn),
                PorterUserServiceOptions.decodeForRemove(args));
    }

    @Override
    public void requestPermission(int requestCode) {
        core.requestPermission(CallerIdentity.fromBinder(), requestCode);
    }

    @Override
    public boolean checkSelfPermission() {
        return core.checkSelfPermission(CallerIdentity.fromBinder());
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return core.shouldShowRequestPermissionRationale(CallerIdentity.fromBinder());
    }

    @Override
    public void exit() {
        core.enforceManagerPermission("exit", CallerIdentity.fromBinder());
        managerOperations.exit();
    }

    @Override
    public void attachUserService(IBinder binder, Bundle args) {
        core.enforceManagerPermission("attachUserService", CallerIdentity.fromBinder());
        managerOperations.attachUserService(
                binder, Objects.requireNonNull(args.getString(USER_SERVICE_TOKEN), "token is null"));
    }

    @Override
    public void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, Bundle data) {
        core.enforceManagerPermission("dispatchPermissionConfirmationResult", CallerIdentity.fromBinder());

        if (data == null) {
            return;
        }

        managerOperations.dispatchPermissionConfirmationResult(
                requestUid,
                requestPid,
                requestCode,
                data.getBoolean(PERMISSION_CONFIRMATION_ALLOWED, false),
                data.getBoolean(PERMISSION_CONFIRMATION_ONETIME, false));
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        core.enforceManagerPermission("getFlagsForUid", CallerIdentity.fromBinder());
        return managerOperations.getFlagsForUid(uid, mask);
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
        core.enforceManagerPermission("updateFlagsForUid", CallerIdentity.fromBinder());
        managerOperations.updateFlagsForUid(uid, mask, value);
    }

    @CallSuper
    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == PorterProtocol.TRANSACTION_transactRemote) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            core.transactRemote(CallerIdentity.fromBinder(), data, reply, flags);
            return true;
        } else if (porshService.onTransact(code, data, reply, flags)) {
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
