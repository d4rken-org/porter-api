package eu.darken.porter.endpoint

import android.os.IBinder
import android.os.RemoteException
import eu.darken.porter.core.UserServiceConnection
import eu.darken.porter.server.IPorterServiceConnection

/**
 * A [UserServiceConnection] spoken over the Porter user-service endpoint. Two adapters over the
 * same proxy answer with the same binder, so a callback list holds one registration for them.
 */
class PorterServiceConnection(connection: IPorterServiceConnection?) : UserServiceConnection {

    val connection: IPorterServiceConnection = (connection ?: throw NullPointerException("connection is null"))

    override fun asBinder(): IBinder = connection.asBinder()

    @Throws(RemoteException::class)
    override fun connected(service: IBinder) {
        connection.connected(service)
    }

    @Throws(RemoteException::class)
    override fun died() {
        connection.died()
    }
}
