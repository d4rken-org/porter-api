package eu.darken.porter.endpoint;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.Objects;

import eu.darken.porter.core.UserServiceConnection;
import eu.darken.porter.server.IPorterServiceConnection;

/**
 * A {@link UserServiceConnection} spoken over the Porter user-service endpoint. Two adapters over
 * the same proxy answer with the same binder, so a callback list holds one registration for them.
 */
public final class PorterServiceConnection implements UserServiceConnection {

    public final IPorterServiceConnection connection;

    public PorterServiceConnection(IPorterServiceConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection is null");
    }

    @Override
    public IBinder asBinder() {
        return connection.asBinder();
    }

    @Override
    public void connected(IBinder service) throws RemoteException {
        connection.connected(service);
    }

    @Override
    public void died() throws RemoteException {
        connection.died();
    }
}
