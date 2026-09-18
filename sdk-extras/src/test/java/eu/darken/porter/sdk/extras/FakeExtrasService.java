package eu.darken.porter.sdk.extras;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;

import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterService;
import eu.darken.porter.server.IPorterServiceConnection;

/** A local stub standing in for the server, carrying only the system property calls. */
class FakeExtrasService extends IPorterService.Stub {

    Bundle attachReply = new Bundle();

    /** Answered to every {@code getSystemProperty}; null means "echo the default back". */
    String propertyValue;

    String readName;
    String readDefault;
    String writtenName;
    String writtenValue;

    @Override
    public Bundle attach(IPorterApplication application, Bundle args) {
        return attachReply;
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        readName = name;
        readDefault = defaultValue;
        return propertyValue != null ? propertyValue : defaultValue;
    }

    @Override
    public void setSystemProperty(String name, String value) {
        writtenName = name;
        writtenValue = value;
    }

    @Override
    public int getUid() {
        return -1;
    }

    @Override
    public int checkPermission(String permission) {
        return PackageManager.PERMISSION_DENIED;
    }

    @Override
    public String getSELinuxContext() {
        return null;
    }

    @Override
    public IPorterRemoteProcess newProcess(String[] cmd, String[] env, String dir) {
        return null;
    }

    @Override
    public int addUserService(IPorterServiceConnection conn, Bundle args) {
        return 0;
    }

    @Override
    public int removeUserService(IPorterServiceConnection conn, Bundle args) {
        return 0;
    }

    @Override
    public void requestPermission(int requestCode) {
    }

    @Override
    public boolean checkSelfPermission() {
        return false;
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return false;
    }

    @Override
    public void exit() {
    }

    @Override
    public void attachUserService(IBinder binder, Bundle args) {
    }

    @Override
    public void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, Bundle data) {
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        return 0;
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
    }
}
