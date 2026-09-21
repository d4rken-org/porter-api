package eu.darken.porter.server;

import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterServiceConnection;

// Ids are explicit and never reused: a removed method leaves its number unallocated.
interface IPorterService {

    Bundle attach(in IPorterApplication application, in Bundle args) = 1;

    int getUid() = 2;

    int checkPermission(String permission) = 3;

    String getSELinuxContext() = 4;

    String getSystemProperty(String name, String defaultValue) = 5;

    void setSystemProperty(String name, String value) = 6;

    int addUserService(in IPorterServiceConnection conn, in Bundle args) = 8;

    int removeUserService(in IPorterServiceConnection conn, in Bundle args) = 9;

    void requestPermission(int requestCode) = 10;

    boolean checkSelfPermission() = 11;

    boolean shouldShowRequestPermissionRationale() = 12;
}
