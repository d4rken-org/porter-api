package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;

import android.os.Bundle;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.porsh.PorshService;
import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterService;
import eu.darken.porter.server.IPorterServiceConnection;
import rikka.hidden.compat.PackageManagerApis;
import rikka.shizuku.ShizukuApiConstants;
import rikka.shizuku.server.ClientManager;
import rikka.shizuku.server.ClientRecord;
import rikka.shizuku.server.Service;
import rikka.shizuku.server.UserServiceManager;
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

    private final Service<?, ?, ?> core;
    private final IntFunction<List<String>> packagesForUid;
    private final PorshService porshService;

    public PorterEndpoint(Service<?, ?, ?> core) {
        this(core, PackageManagerApis::getPackagesForUidNoThrow);
    }

    PorterEndpoint(Service<?, ?, ?> core, IntFunction<List<String>> packagesForUid) {
        this.core = core;
        this.packagesForUid = packagesForUid;
        this.porshService = new PorshService(PorterProtocol.DESCRIPTOR, PorterProtocol.TRANSACTION_PORSH_BASE) {

            @Override
            public void enforceCallingPermission(String func) {
                core.enforceCallingPermission(func);
            }
        };
    }

    @Override
    public Bundle attach(IPorterApplication application, Bundle args) {
        Objects.requireNonNull(application, "application is null");
        Objects.requireNonNull(args, "args is null");

        String packageName = Objects.requireNonNull(args.getString(ATTACH_PACKAGE_NAME), "package name is null");
        CallerIdentity caller = CallerIdentity.fromBinder();

        // Never null: getPackagesForUidNoThrow answers with an empty list when it cannot look up.
        if (!packagesForUid.apply(caller.uid).contains(packageName)) {
            throw new SecurityException(
                    "Request package " + packageName + " does not belong to uid " + caller.uid);
        }

        LOGGER.d("attach: uid=%d, pid=%d, package=%s, protocol=%d",
                caller.uid, caller.pid, packageName, args.getInt(ATTACH_PROTOCOL_VERSION, 0));

        ClientManager<?> clientManager = core.getClientManager();
        ClientRecord record;
        // One critical section: a second attach from the same process must find what the first left.
        synchronized (clientManager) {
            record = clientManager.findClient(caller.uid, caller.pid);
            if (record == null) {
                record = clientManager.attach(
                        caller, new PorterClientCallback(application), packageName, CORE_API_LEVEL);
                if (record == null) {
                    throw new IllegalStateException("client binder is dead");
                }
            } else if (!(record.callback instanceof PorterClientCallback)) {
                throw new IllegalStateException("uid " + caller.uid + "/pid " + caller.pid
                        + " is attached through the Shizuku endpoint");
            } else if (!record.packageName.equals(packageName)) {
                throw new SecurityException("uid " + caller.uid + "/pid " + caller.pid
                        + " is attached as " + record.packageName);
            }
        }

        Bundle reply = new Bundle();
        reply.putInt(REPLY_PROTOCOL_VERSION, PorterProtocol.VERSION);
        reply.putInt(REPLY_SERVER_UID, OsUtils.getUid());
        reply.putString(REPLY_SERVER_SECONTEXT, OsUtils.getSELinuxContext());
        reply.putBoolean(REPLY_PERMISSION_GRANTED, record.allowed);
        reply.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        reply.putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE);
        return reply;
    }

    @Override
    public int getUid() {
        return core.getUid();
    }

    @Override
    public int checkPermission(String permission) throws RemoteException {
        return core.checkPermission(permission);
    }

    @Override
    public String getSELinuxContext() {
        return core.getSELinuxContext();
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        return core.getSystemProperty(name, defaultValue);
    }

    @Override
    public void setSystemProperty(String name, String value) {
        core.setSystemProperty(name, value);
    }

    @Override
    public IPorterRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return new PorterRemoteProcessHolder(core.newServerProcess(cmd, env, dir));
    }

    @Override
    public int addUserService(IPorterServiceConnection conn, Bundle args) {
        core.enforceCallingPermission("addUserService");

        Objects.requireNonNull(conn, "connection is null");
        Objects.requireNonNull(args, "args is null");

        UserServiceManager userServiceManager = core.getUserServiceManager();
        return userServiceManager.addUserService(
                CallerIdentity.fromBinder(),
                new PorterServiceConnection(conn),
                PorterUserServiceOptions.decodeForBind(args),
                CORE_API_LEVEL);
    }

    @Override
    public int removeUserService(IPorterServiceConnection conn, Bundle args) {
        core.enforceCallingPermission("removeUserService");

        UserServiceManager userServiceManager = core.getUserServiceManager();
        return userServiceManager.removeUserService(
                CallerIdentity.fromBinder(),
                conn == null ? null : new PorterServiceConnection(conn),
                PorterUserServiceOptions.decodeForRemove(args));
    }

    @Override
    public void requestPermission(int requestCode) {
        core.requestPermission(requestCode);
    }

    @Override
    public boolean checkSelfPermission() {
        return core.checkSelfPermission();
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return core.shouldShowRequestPermissionRationale();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        if (code == PorterProtocol.TRANSACTION_transactRemote) {
            data.enforceInterface(PorterProtocol.DESCRIPTOR);
            core.transactRemote(data, reply, flags);
            return true;
        } else if (porshService.onTransact(code, data, reply, flags)) {
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
