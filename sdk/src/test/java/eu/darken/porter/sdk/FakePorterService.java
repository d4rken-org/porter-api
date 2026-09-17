package eu.darken.porter.sdk;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterService;
import eu.darken.porter.server.IPorterServiceConnection;

/** A local stub standing in for the server: records what it was asked and answers what it was told. */
class FakePorterService extends IPorterService.Stub {

    Bundle attachReply = new Bundle();
    RuntimeException attachFailure;
    int attachCount;
    Bundle attachArgs;
    IPorterApplication application;

    /** Pushed to the client from inside a call, before that call answers. */
    Bundle attachTimePermissionPush;
    Bundle checkTimePermissionPush;

    /** Unknown until a test says otherwise, so a value kept from a previous reply stands out. */
    int uid = -1;
    String seLinuxContext;
    boolean selfPermission;
    int selfPermissionQueries;
    int requestedPermissionCode = -1;
    int userServiceResult;
    Bundle userServiceArgs;

    int exitCalls;
    IBinder attachedUserServiceBinder;
    Bundle attachedUserServiceArgs;
    int confirmationUid = -1;
    int confirmationPid = -1;
    int confirmationRequestCode = -1;
    Bundle confirmationData;
    int flags;
    int flagsUid = -1;
    int flagsMask = -1;
    int flagsValue = -1;

    @Override
    public Bundle attach(IPorterApplication application, Bundle args) {
        attachCount++;
        this.application = application;
        this.attachArgs = args;
        if (attachFailure != null) {
            throw attachFailure;
        }
        if (attachTimePermissionPush != null) {
            pushPermissionState(attachTimePermissionPush);
        }
        return attachReply;
    }

    private void pushPermissionState(Bundle state) {
        try {
            application.dispatchPermissionStateChanged(state);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getUid() {
        return uid;
    }

    @Override
    public int checkPermission(String permission) {
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public String getSELinuxContext() {
        return seLinuxContext;
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        return defaultValue;
    }

    @Override
    public void setSystemProperty(String name, String value) {
    }

    @Override
    public IPorterRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return null;
    }

    @Override
    public int addUserService(IPorterServiceConnection conn, Bundle args) {
        userServiceArgs = args;
        return userServiceResult;
    }

    @Override
    public int removeUserService(IPorterServiceConnection conn, Bundle args) {
        userServiceArgs = args;
        return 0;
    }

    @Override
    public void requestPermission(int requestCode) {
        requestedPermissionCode = requestCode;
    }

    @Override
    public boolean checkSelfPermission() {
        selfPermissionQueries++;
        if (checkTimePermissionPush != null) {
            Bundle state = checkTimePermissionPush;
            checkTimePermissionPush = null;
            pushPermissionState(state);
        }
        return selfPermission;
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return false;
    }

    @Override
    public void exit() {
        exitCalls++;
    }

    @Override
    public void attachUserService(IBinder binder, Bundle args) {
        attachedUserServiceBinder = binder;
        attachedUserServiceArgs = args;
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
