package eu.darken.porter.sdk;

import android.content.pm.PackageManager;
import android.os.Bundle;

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

    /** Unknown until a test says otherwise, so a value kept from a previous reply stands out. */
    int uid = -1;
    String seLinuxContext;
    boolean selfPermission;
    int selfPermissionQueries;
    int requestedPermissionCode = -1;
    int userServiceResult;
    Bundle userServiceArgs;

    @Override
    public Bundle attach(IPorterApplication application, Bundle args) {
        attachCount++;
        this.application = application;
        this.attachArgs = args;
        if (attachFailure != null) {
            throw attachFailure;
        }
        return attachReply;
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
        return selfPermission;
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return false;
    }
}
