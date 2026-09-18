package eu.darken.porter.sdk;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;

/**
 * A local stub standing in for a Shizuku server: records what it was asked and answers what it was
 * told. The generated dispatch does the decoding, so what the wire wrote has to be what the real
 * server would have read.
 */
class FakeShizukuService extends IShizukuService.Stub {

    /** Every transaction code, in the order it arrived. */
    final List<Integer> codes = Collections.synchronizedList(new ArrayList<>());

    int lastTransactFlags;
    boolean lastTransactExpectedReply;

    /** Sent from inside attachApplication, as the real server sends it. */
    Bundle bindApplicationReply = replyWithVersion(ShizukuProtocol.MINIMUM_VERSION);
    boolean suppressBindApplication;
    /** Sends the reply from another thread after this delay instead, when above zero. */
    long deferBindApplicationMs;

    int attachCount;
    Bundle attachArgs;
    IShizukuApplication application;

    int version = ShizukuProtocol.MINIMUM_VERSION;
    /** Unknown until a test says otherwise, so a value kept from a previous reply stands out. */
    int uid = -1;
    String seLinuxContext;
    String checkedPermission;
    int remotePermission = PackageManager.PERMISSION_DENIED;
    String systemProperty;
    String queriedPropertyName;
    String queriedPropertyDefault;
    String setPropertyName;
    String setPropertyValue;
    int requestedPermissionCode = -1;
    boolean selfPermission;
    boolean rationale;

    /** What one add or remove carried, read the way the server reads it. */
    static final class UserServiceCall {

        final IBinder connection;
        final Bundle args;

        UserServiceCall(IBinder connection, Bundle args) {
            this.connection = connection;
            this.args = args;
        }
    }

    final List<UserServiceCall> userServiceAdds = Collections.synchronizedList(new ArrayList<>());
    final List<UserServiceCall> userServiceRemoves = Collections.synchronizedList(new ArrayList<>());
    int addUserServiceResult;
    int removeUserServiceResult;
    /** The connection of the newest add, which is what the user service pushes go to. */
    private IShizukuServiceConnection userServiceConnection;

    int exitCalls;
    IBinder attachedUserServiceBinder;
    Bundle attachedUserServiceOptions;
    int confirmationUid = -1;
    int confirmationPid = -1;
    int confirmationRequestCode = -1;
    Bundle confirmationData;
    int flags;
    int flagsUid = -1;
    int flagsMask = -1;
    int flagsValue = -1;

    /** What a forwarded transaction carried, read the way the server reads it. */
    IBinder forwardedTarget;
    int forwardedCode = -1;
    int forwardedFlags = -1;
    int forwardedPayload = -1;

    static Bundle replyWithVersion(int version) {
        Bundle reply = new Bundle();
        reply.putInt(ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION, version);
        return reply;
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        codes.add(code);
        lastTransactFlags = flags;
        lastTransactExpectedReply = reply != null;

        if (code == ShizukuProtocol.TRANSACTION_transactRemote) {
            // Outside the AIDL, so the generated dispatch would enforce the token and then refuse it.
            data.enforceInterface(ShizukuProtocol.DESCRIPTOR);
            forwardedTarget = data.readStrongBinder();
            forwardedCode = data.readInt();
            forwardedFlags = data.readInt();
            forwardedPayload = data.readInt();
            return true;
        }

        return super.onTransact(code, data, reply, flags);
    }

    @Override
    public void attachApplication(IShizukuApplication application, Bundle args) {
        attachCount++;
        this.application = application;
        this.attachArgs = args;

        if (suppressBindApplication) return;
        if (deferBindApplicationMs > 0) {
            replyFromAnotherThread(deferBindApplicationMs);
            return;
        }
        pushBindApplication(bindApplicationReply);
    }

    private void replyFromAnotherThread(long delayMs) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            pushBindApplication(bindApplicationReply);
        });
        thread.setDaemon(true);
        thread.start();
    }

    void pushBindApplication(Bundle state) {
        try {
            application.bindApplication(state);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    void pushRequestPermissionResult(int requestCode, Bundle result) {
        try {
            application.dispatchRequestPermissionResult(requestCode, result);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getVersion() {
        return version;
    }

    @Override
    public int getUid() {
        return uid;
    }

    @Override
    public int checkPermission(String permission) {
        checkedPermission = permission;
        return remotePermission;
    }

    @Override
    public IRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return null;
    }

    @Override
    public String getSELinuxContext() {
        return seLinuxContext;
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        queriedPropertyName = name;
        queriedPropertyDefault = defaultValue;
        return systemProperty;
    }

    @Override
    public void setSystemProperty(String name, String value) {
        setPropertyName = name;
        setPropertyValue = value;
    }

    @Override
    public int addUserService(IShizukuServiceConnection conn, Bundle args) {
        userServiceAdds.add(new UserServiceCall(conn == null ? null : conn.asBinder(), args));
        userServiceConnection = conn;
        return addUserServiceResult;
    }

    @Override
    public int removeUserService(IShizukuServiceConnection conn, Bundle args) {
        userServiceRemoves.add(new UserServiceCall(conn == null ? null : conn.asBinder(), args));
        return removeUserServiceResult;
    }

    void pushUserServiceConnected(IBinder service) {
        try {
            userServiceConnection.connected(service);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    void pushUserServiceDied() {
        try {
            userServiceConnection.died();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void requestPermission(int requestCode) {
        requestedPermissionCode = requestCode;
    }

    @Override
    public boolean checkSelfPermission() {
        return selfPermission;
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return rationale;
    }

    @Override
    public void exit() {
        exitCalls++;
    }

    @Override
    public void attachUserService(IBinder binder, Bundle options) {
        attachedUserServiceBinder = binder;
        attachedUserServiceOptions = options;
    }

    @Override
    public void dispatchPackageChanged(Intent intent) {
    }

    @Override
    public boolean isHidden(int uid) {
        return false;
    }

    @Override
    public void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, Bundle data) {
        confirmationUid = requestUid;
        confirmationPid = requestPid;
        confirmationRequestCode = requestCode;
        confirmationData = data;
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        flagsUid = uid;
        flagsMask = mask;
        return flags;
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
        flagsUid = uid;
        flagsMask = mask;
        flagsValue = value;
    }
}
