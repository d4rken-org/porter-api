package eu.darken.porter.sdk

import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED
import eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES
import eu.darken.porter.protocol.PorterProtocol.REPLY_MIN_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.protocol.PorterProtocol.REPLY_UNSUPPORTED
import eu.darken.porter.protocol.PorterProtocol.TRANSACTION_transactRemote
import eu.darken.porter.server.IPorterApplication
import eu.darken.porter.server.IPorterService
import eu.darken.porter.server.IPorterServiceConnection

/** The Porter protocol as a client speaks it, over one server binder. */
internal class PorterProtocolWire(
    binder: IBinder,
    private val callbacks: PorterWire.Callbacks,
) : PorterWire {

    private val service: IPorterService = IPorterService.Stub.asInterface(binder)

    /**
     * Held for the life of the connection: the server keeps only a proxy, so a stub that goes
     * unreachable here stops the pushes arriving.
     */
    private val application: IPorterApplication = object : IPorterApplication.Stub() {

        override fun dispatchRequestPermissionResult(requestCode: Int, data: Bundle) {
            callbacks.onRequestPermissionResult(
                requestCode, data.getBoolean(PERMISSION_RESULT_ALLOWED, false),
            )
        }

        override fun dispatchPermissionStateChanged(state: Bundle) {
            callbacks.onPermissionStateChanged(
                state.getBoolean(REPLY_PERMISSION_GRANTED, false),
                state.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false),
            )
        }
    }

    @Throws(RemoteException::class)
    override fun attach(packageName: String): PorterWire.AttachReply? {
        val args = Bundle().apply {
            putString(ATTACH_PACKAGE_NAME, packageName)
            putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION)
        }

        val reply = service.attach(application, args) ?: return null
        return PorterWire.AttachReply(
            serverUid = reply.getInt(REPLY_SERVER_UID, -1),
            protocolVersion = reply.getInt(REPLY_PROTOCOL_VERSION, 0),
            seLinuxContext = reply.getString(REPLY_SERVER_SECONTEXT),
            capabilities = reply.getLong(REPLY_CAPABILITIES, CAPABILITIES_NONE),
            permissionGranted = reply.getBoolean(REPLY_PERMISSION_GRANTED, false),
            shouldShowRequestPermissionRationale =
                reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false),
            patchVersion = null,
            minProtocolVersion = reply.getInt(REPLY_MIN_PROTOCOL_VERSION, 0),
            unsupported = reply.getBoolean(REPLY_UNSUPPORTED, false),
        )
    }

    override fun incompatibility(reply: PorterWire.AttachReply?): PorterIncompatibility? {
        if (reply == null) return PorterIncompatibility(serverVersion = 0, serverMinVersion = 0)
        val mismatch = PorterIncompatibility(reply.protocolVersion, reply.minProtocolVersion)
        // A server that refused says so; one that did not is still held to this side's floor, and
        // one that reports no version at all cannot be told apart from one below it.
        val refused = reply.unsupported || reply.protocolVersion < MIN_SERVER_VERSION
        return if (refused || mismatch.serverTooOld || mismatch.clientTooOld) mismatch else null
    }

    override fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        remote { service.asBinder().transact(TRANSACTION_transactRemote, data, reply, flags) }
    }

    override fun forward(target: IBinder, code: Int, data: Parcel, reply: Parcel?, flags: Int) {
        // The token and the transaction are answered for by this one connection: a replacement
        // publishing between the two would have its server reject a parcel carrying the other
        // wire's token.
        val newData = Parcel.obtain()
        try {
            newData.writeInterfaceToken(PorterProtocol.DESCRIPTOR)
            newData.writeStrongBinder(target)
            newData.writeInt(code)
            newData.writeInt(flags)
            newData.appendFrom(data, 0, data.dataSize())
            transactRemote(newData, reply, 0)
        } finally {
            newData.recycle()
        }
    }

    override fun getUid(): Int = remote { service.getUid() }

    override fun getSELinuxContext(): String? = remote { service.getSELinuxContext() }

    override fun checkPermission(permission: String): Int = remote { service.checkPermission(permission) }

    override fun getSystemProperty(name: String, defaultValue: String?): String? =
        remote { service.getSystemProperty(name, defaultValue) }

    override fun setSystemProperty(name: String, value: String) {
        remote { service.setSystemProperty(name, value) }
    }

    override fun requestPermission(requestCode: Int) {
        remote { service.requestPermission(requestCode) }
    }

    override fun checkSelfPermission(): Boolean = remote { service.checkSelfPermission() }

    override fun shouldShowRequestPermissionRationale(): Boolean =
        remote { service.shouldShowRequestPermissionRationale() }

    override fun addUserService(conn: UserServiceCallback, args: UserServiceArgs, noCreate: Boolean): Int {
        val adapter = adapterFor(conn)
        val options = PorterUserServiceCodec.encodeUserService(args, noCreate)
        return remote { service.addUserService(adapter, options) }
    }

    override fun removeUserService(conn: UserServiceCallback?, args: UserServiceArgs, remove: Boolean): Int {
        val adapter = adapterFor(conn)
        val options = PorterUserServiceCodec.encodeUserServiceRemoval(args, remove)
        return remote { service.removeUserService(adapter, options) }
    }

    private inline fun <T> remote(call: () -> T): T = try {
        call()
    } catch (e: RemoteException) {
        throw PorterRemoteException(e)
    }

    internal companion object {

        /**
         * The oldest server this SDK still speaks to. Raised only when the SDK stops speaking an
         * older generation; a server above [PorterProtocol.VERSION] is fine as long as its own
         * floor admits this client.
         */
        const val MIN_SERVER_VERSION: Int = 4

        /**
         * The stub this wire has registered for [callback], created on first use, and null for the
         * removal that names no callback at all.
         *
         * Lookup, creation and store happen under one lock on the callback, and the lock is released
         * before the call that carries the result: two threads binding one service register one stub.
         */
        fun adapterFor(callback: UserServiceCallback?): IPorterServiceConnection? {
            if (callback == null) return null
            synchronized(callback) {
                val registered = callback.registeredBinder(PorterBackend.PORTER)
                if (registered != null) return IPorterServiceConnection.Stub.asInterface(registered)

                val adapter = object : IPorterServiceConnection.Stub() {

                    override fun connected(binder: IBinder) {
                        callback.connected(binder)
                    }

                    override fun died() {
                        callback.died()
                    }
                }
                callback.rememberRegisteredBinder(PorterBackend.PORTER, adapter.asBinder())
                return adapter
            }
        }
    }
}
