package rikka.shizuku.server.legacy

import android.os.IBinder
import android.os.RemoteException
import eu.darken.porter.core.UserServiceConnection
import moe.shizuku.server.IShizukuServiceConnection

/**
 * A [UserServiceConnection] spoken over the Shizuku user-service endpoint. Two adapters over
 * the same proxy answer with the same binder, so a callback list holds one registration for them.
 */
class LegacyServiceConnection(connection: IShizukuServiceConnection?) : UserServiceConnection {

    val connection: IShizukuServiceConnection = (connection ?: throw NullPointerException("connection is null"))

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
