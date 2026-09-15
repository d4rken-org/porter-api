package rikka.shizuku.server.legacy;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.Objects;

import eu.darken.porter.core.UserServiceConnection;
import moe.shizuku.server.IShizukuServiceConnection;

/**
 * A {@link UserServiceConnection} spoken over the Shizuku user-service endpoint. Two adapters over
 * the same proxy answer with the same binder, so a callback list holds one registration for them.
 */
public final class LegacyServiceConnection implements UserServiceConnection {

    public final IShizukuServiceConnection connection;

    public LegacyServiceConnection(IShizukuServiceConnection connection) {
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
