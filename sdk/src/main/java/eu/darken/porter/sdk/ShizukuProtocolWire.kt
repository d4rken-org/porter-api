package eu.darken.porter.sdk

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.sdk.ShizukuProtocol.APPLICATION_DESCRIPTOR
import eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_API_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_PACKAGE_NAME
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_PERMISSION_GRANTED
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_SECONTEXT
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_UID
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.sdk.ShizukuProtocol.DESCRIPTOR
import eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_ALLOWED
import eu.darken.porter.sdk.ShizukuProtocol.SERVICE_CONNECTION_DESCRIPTOR
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Shizuku protocol as a client speaks it, over one server binder. */
internal class ShizukuProtocolWire(
    private val service: IBinder,
    private val callbacks: PorterWire.Callbacks,
    private val attachTimeoutMs: Long = ATTACH_TIMEOUT_MS,
) : PorterWire {

    private val attached = CountDownLatch(1)

    /**
     * Guards [attachState]. Never held across a binder call and never across the wait on
     * [attached]: the callback that counts the latch down runs on another thread and takes
     * this lock to do it.
     */
    private val lock = Any()

    /** What the first `bindApplication` carried, and null until one arrives. */
    private var attachState: Bundle? = null

    /**
     * Set once, from the death dispatch, and read by the waiter on [attached]. Outside
     * [lock] so that counting the latch down here cannot happen while that lock is held.
     */
    @Volatile
    private var peerDied = false

    /**
     * Held for the life of the connection: the server keeps only a proxy, so a stub that goes
     * unreachable here stops the pushes arriving.
     */
    private val application = ShizukuApplication()

    /** The binder the server pushes to, answering Shizuku's application descriptor. */
    private inner class ShizukuApplication : Binder(), IInterface {

        init {
            attachInterface(this, APPLICATION_DESCRIPTOR)
        }

        /** [Binder] carries [IBinder] rather than [IInterface]. */
        override fun asBinder(): IBinder = this

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Both of these are oneway, so nothing is written back, not even an exception header.
            return when (code) {
                ShizukuProtocol.APPLICATION_TRANSACTION_bindApplication -> {
                    data.enforceInterface(APPLICATION_DESCRIPTOR)
                    onBindApplication(data.readTypedObject(Bundle.CREATOR))
                    true
                }
                ShizukuProtocol.APPLICATION_TRANSACTION_dispatchRequestPermissionResult -> {
                    data.enforceInterface(APPLICATION_DESCRIPTOR)
                    val requestCode = data.readInt()
                    val result = data.readTypedObject(Bundle.CREATOR)
                    callbacks.onRequestPermissionResult(
                        requestCode,
                        result != null && result.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false),
                    )
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    /** The binder the server pushes one user service binding to, one per callback. */
    private class ShizukuServiceConnection(private val callback: UserServiceCallback) : Binder(), IInterface {

        init {
            attachInterface(this, SERVICE_CONNECTION_DESCRIPTOR)
        }

        /** [Binder] carries [IBinder] rather than [IInterface]. */
        override fun asBinder(): IBinder = this

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Both of these are oneway, so nothing is written back, not even an exception header.
            return when (code) {
                ShizukuProtocol.SERVICE_CONNECTION_TRANSACTION_connected -> {
                    data.enforceInterface(SERVICE_CONNECTION_DESCRIPTOR)
                    callback.connected(data.readStrongBinder())
                    true
                }
                ShizukuProtocol.SERVICE_CONNECTION_TRANSACTION_died -> {
                    data.enforceInterface(SERVICE_CONNECTION_DESCRIPTOR)
                    callback.died()
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    private fun onBindApplication(state: Bundle?) {
        val resent: Boolean
        synchronized(lock) {
            resent = attachState != null
            if (!resent) attachState = state ?: Bundle()
        }

        if (resent) {
            // A server pushes this again to a client that has already attached when the permission
            // it holds changes, and this wire carries no other signal for that. The handshake state
            // is kept, because the handshake completed against it, and the permission state taken.
            Log.d(TAG, "bindApplication after the handshake, permission state taken")
            // Called outside the lock, as the first push counts the latch down outside it.
            callbacks.onPermissionStateChanged(
                state != null && state.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false),
                state != null && state.getBoolean(
                    BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false,
                ),
            )
            return
        }

        attached.countDown()
    }

    override fun attach(packageName: String): PorterWire.AttachReply? {
        val args = Bundle().apply {
            putInt(ATTACH_APPLICATION_API_VERSION, ShizukuProtocol.CLIENT_API_VERSION)
            putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName)
        }

        callVoid(ShizukuProtocol.TRANSACTION_attachApplication) { data ->
            data.writeStrongBinder(application.asBinder())
            data.writeTypedObject(args, 0)
        }

        return awaitAttachReply()
    }

    override fun onPeerDied() {
        peerDied = true
        attached.countDown()
    }

    /**
     * Shizuku's `attachApplication` answers nothing: the state arrives afterwards on the
     * callback binder, from one of this process's binder threads, so the call that started the
     * handshake waits for it here.
     *
     * Never returns a partly-filled reply. A handshake that did not complete throws, which is
     * what abandons the connection instead of publishing one that cannot answer.
     */
    private fun awaitAttachReply(): PorterWire.AttachReply {
        val answered = try {
            attached.await(attachTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while waiting for the server to attach", e)
        }
        // Ahead of the timeout: a death that lands as the wait expires ended it, whatever the clock
        // says.
        if (peerDied) {
            throw IllegalStateException("the server died before it answered attach")
        }
        if (!answered) {
            throw IllegalStateException("the server did not answer attach within ${attachTimeoutMs}ms")
        }

        val state = synchronized(lock) { checkNotNull(attachState) }

        return PorterWire.AttachReply(
            serverUid = state.getInt(BIND_APPLICATION_SERVER_UID, -1),
            protocolVersion = state.getInt(BIND_APPLICATION_SERVER_VERSION, 0),
            seLinuxContext = state.getString(BIND_APPLICATION_SERVER_SECONTEXT),
            capabilities = CAPABILITIES_NONE,
            permissionGranted = state.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false),
            shouldShowRequestPermissionRationale =
                state.getBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false),
            patchVersion = if (state.containsKey(BIND_APPLICATION_SERVER_PATCH_VERSION)) {
                state.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION)
            } else {
                null
            },
        )
    }

    /** A server below [ShizukuProtocol.MINIMUM_VERSION] is refused rather than spoken to on an older encoding. */
    override fun incompatibility(reply: PorterWire.AttachReply?): PorterIncompatibility? {
        val version = reply?.protocolVersion ?: 0
        if (reply != null && version >= ShizukuProtocol.MINIMUM_VERSION) return null
        // The Shizuku wire names no oldest client, so only this side's floor can refuse.
        return PorterIncompatibility(PorterBackend.SHIZUKU, serverVersion = version, serverMinVersion = 0)
    }

    override fun transactRemote(data: Parcel, reply: Parcel?, flags: Int) {
        try {
            service.transact(ShizukuProtocol.TRANSACTION_transactRemote, data, reply, flags)
        } catch (e: RemoteException) {
            throw PorterRemoteException(e)
        }
    }

    override fun forward(target: IBinder, code: Int, data: Parcel, reply: Parcel?, flags: Int) {
        // The forwarded flags int is read by servers from protocol 13 on, and this wire refuses to
        // attach below 13, so the envelope always carries it and the outer call is never one-way.
        val newData = Parcel.obtain()
        try {
            newData.writeInterfaceToken(DESCRIPTOR)
            newData.writeStrongBinder(target)
            newData.writeInt(code)
            newData.writeInt(flags)
            newData.appendFrom(data, 0, data.dataSize())
            transactRemote(newData, reply, 0)
        } finally {
            newData.recycle()
        }
    }

    override fun getUid(): Int = call(ShizukuProtocol.TRANSACTION_getUid, NO_ARGUMENTS) { it.readInt() }

    override fun getSELinuxContext(): String? =
        call(ShizukuProtocol.TRANSACTION_getSELinuxContext, NO_ARGUMENTS) { it.readString() }

    override fun checkPermission(permission: String): Int =
        call(ShizukuProtocol.TRANSACTION_checkPermission, { data -> data.writeString(permission) }) { it.readInt() }

    override fun getSystemProperty(name: String, defaultValue: String?): String? =
        call(ShizukuProtocol.TRANSACTION_getSystemProperty, { data ->
            data.writeString(name)
            data.writeString(defaultValue)
        }) { it.readString() }

    override fun setSystemProperty(name: String, value: String) {
        callVoid(ShizukuProtocol.TRANSACTION_setSystemProperty) { data ->
            data.writeString(name)
            data.writeString(value)
        }
    }

    override fun requestPermission(requestCode: Int) {
        callVoid(ShizukuProtocol.TRANSACTION_requestPermission) { data -> data.writeInt(requestCode) }
    }

    override fun checkSelfPermission(): Boolean =
        call(ShizukuProtocol.TRANSACTION_checkSelfPermission, NO_ARGUMENTS, ::readBoolean)

    override fun shouldShowRequestPermissionRationale(): Boolean =
        call(ShizukuProtocol.TRANSACTION_shouldShowRequestPermissionRationale, NO_ARGUMENTS, ::readBoolean)

    override fun addUserService(conn: UserServiceCallback, args: UserServiceArgs, noCreate: Boolean): Int {
        val connection = connectionBinderFor(conn)
        val options = ShizukuUserServiceCodec.encodeUserService(args, noCreate)
        return call(ShizukuProtocol.TRANSACTION_addUserService, { data ->
            data.writeStrongBinder(connection)
            data.writeTypedObject(options, 0)
        }) { it.readInt() }
    }

    override fun removeUserService(conn: UserServiceCallback?, args: UserServiceArgs, remove: Boolean): Int {
        if (!remove && !dropsAConnectionOnRequest()) return 0

        val connection = connectionBinderFor(conn)
        val options = ShizukuUserServiceCodec.encodeUserServiceRemoval(args, remove)
        return call(ShizukuProtocol.TRANSACTION_removeUserService, { data ->
            data.writeStrongBinder(connection)
            data.writeTypedObject(options, 0)
        }) { it.readInt() }
    }

    /**
     * Whether this server understands being asked to drop a connection without killing the service.
     * Upstream sends the call only above this gate and clears its own state below it; what a server
     * below the gate makes of such a request is not established here.
     *
     * Nothing being sent below the gate means the server keeps the registration while the facade
     * clears and evicts its own, so a later bind registers a second binder alongside the first.
     * Upstream leaves it there the same way.
     */
    private fun dropsAConnectionOnRequest(): Boolean {
        val state = synchronized(lock) { attachState } ?: return false

        val version = state.getInt(BIND_APPLICATION_SERVER_VERSION, 0)
        val patch = state.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION, 0)
        return version >= 14 || (version == 13 && patch >= 4)
    }

    /**
     * The binder this wire has registered for [callback], created on first use, and null for the
     * removal that names no callback at all.
     *
     * Lookup, creation and store happen under one lock on the callback, and the lock is released
     * before the call that carries the result: two threads binding one service register one binder.
     */
    private fun connectionBinderFor(callback: UserServiceCallback?): IBinder? {
        if (callback == null) return null
        synchronized(callback) {
            callback.registeredBinder(PorterBackend.SHIZUKU)?.let { return it }

            val connection = ShizukuServiceConnection(callback).asBinder()
            callback.rememberRegisteredBinder(PorterBackend.SHIZUKU, connection)
            return connection
        }
    }

    /**
     * @param arguments writes what follows the interface token
     * @param result reads what follows the exception header
     */
    private fun <T> call(code: Int, arguments: (Parcel) -> Unit, result: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            arguments(data)
            try {
                service.transact(code, data, reply, 0)
                reply.readException()
            } catch (e: RemoteException) {
                throw PorterRemoteException(e)
            } catch (e: SecurityException) {
                // Raised by readException, which is where the server's refusal arrives.
                throw PorterSecurityException(e)
            } catch (e: RuntimeException) {
                // Also raised by readException: whatever else the server failed the call with.
                throw PorterRemoteException(e)
            }
            return result(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun callVoid(code: Int, arguments: (Parcel) -> Unit) {
        call(code, arguments) { }
    }

    private companion object {

        const val TAG = "Porter"

        const val ATTACH_TIMEOUT_MS = 5000L

        /** For a call that writes nothing after the interface token. */
        val NO_ARGUMENTS: (Parcel) -> Unit = { }

        /** A boolean travels as an int, which is what the generated proxy writes and reads. */
        fun readBoolean(reply: Parcel): Boolean = reply.readInt() != 0
    }
}
