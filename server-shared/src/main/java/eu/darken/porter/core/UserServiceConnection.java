package eu.darken.porter.core;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

/**
 * Someone waiting to be told about a user service. Extends {@link IInterface} because
 * {@code RemoteCallbackList} keys its registrations on {@link IInterface#asBinder()}.
 */
public interface UserServiceConnection extends IInterface {

    void connected(IBinder service) throws RemoteException;

    void died() throws RemoteException;
}
