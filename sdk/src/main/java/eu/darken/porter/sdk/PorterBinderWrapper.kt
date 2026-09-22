package eu.darken.porter.sdk

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor

/**
 * Wraps a binder so that every transaction on it is forwarded through the server of one
 * connection. [PorterConnection.wrap] is the usual way to get one.
 */
internal class PorterBinderWrapper(
    private val connection: PorterConnection,
    private val original: IBinder,
) : IBinder {

    @Throws(RemoteException::class)
    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        connection.wire.forward(original, code, data, reply, flags)
        return true
    }

    @Throws(RemoteException::class)
    override fun getInterfaceDescriptor(): String? = original.interfaceDescriptor

    override fun pingBinder(): Boolean = original.pingBinder()

    override fun isBinderAlive(): Boolean = original.isBinderAlive

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    @Throws(RemoteException::class)
    override fun dump(fd: FileDescriptor, args: Array<String>?) {
        original.dump(fd, args)
    }

    @Throws(RemoteException::class)
    override fun dumpAsync(fd: FileDescriptor, args: Array<String>?) {
        original.dumpAsync(fd, args)
    }

    @Throws(RemoteException::class)
    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        original.linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
        original.unlinkToDeath(recipient, flags)
}
