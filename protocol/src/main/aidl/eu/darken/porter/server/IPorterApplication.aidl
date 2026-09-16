package eu.darken.porter.server;

interface IPorterApplication {

    oneway void dispatchRequestPermissionResult(int requestCode, in Bundle data) = 1;

    oneway void dispatchPermissionStateChanged(in Bundle state) = 2;
}
