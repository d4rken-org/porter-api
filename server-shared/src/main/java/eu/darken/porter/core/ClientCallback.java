package eu.darken.porter.core;

import android.os.IBinder;
import android.os.RemoteException;

/** What the server sends an attached client, whichever endpoint the client attached through. */
public interface ClientCallback {

    /** The binder whose death means the client is gone. */
    IBinder asBinder();

    void onPermissionResult(int requestCode, boolean allowed) throws RemoteException;

    void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) throws RemoteException;
}
