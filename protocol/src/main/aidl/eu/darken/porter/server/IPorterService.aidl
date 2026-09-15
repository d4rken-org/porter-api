package eu.darken.porter.server;

import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterServiceConnection;

interface IPorterService {

    Bundle attach(in IPorterApplication application, in Bundle args) = 1;

    int getUid() = 2;

    int checkPermission(String permission) = 3;

    String getSELinuxContext() = 4;

    String getSystemProperty(String name, String defaultValue) = 5;

    void setSystemProperty(String name, String value) = 6;

    IPorterRemoteProcess newProcess(in String[] cmd, in String[] env, String dir) = 7;

    int addUserService(in IPorterServiceConnection conn, in Bundle args) = 8;

    int removeUserService(in IPorterServiceConnection conn, in Bundle args) = 9;

    void requestPermission(int requestCode) = 10;

    boolean checkSelfPermission() = 11;

    boolean shouldShowRequestPermissionRationale() = 12;

    void exit() = 13;

    void attachUserService(in IBinder binder, in Bundle args) = 14;

    oneway void dispatchPermissionConfirmationResult(int requestUid, int requestPid, int requestCode, in Bundle data) = 15;

    int getFlagsForUid(int uid, int mask) = 16;

    void updateFlagsForUid(int uid, int mask, int value) = 17;
}
