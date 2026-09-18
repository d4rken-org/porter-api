package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.sdk.ShizukuProtocol.APPLICATION_DESCRIPTOR;
import static eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_API_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_PACKAGE_NAME;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_PERMISSION_GRANTED;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_SECONTEXT;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_UID;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION;
import static eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static eu.darken.porter.sdk.ShizukuProtocol.DESCRIPTOR;
import static eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_ALLOWED;
import static eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_IS_ONETIME;
import static eu.darken.porter.sdk.ShizukuProtocol.SERVICE_CONNECTION_DESCRIPTOR;
import static eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TOKEN;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The Shizuku protocol as a client speaks it, over one server binder. */
final class ShizukuProtocolWire implements PorterWire {

    private static final String TAG = "Porter";

    private static final long ATTACH_TIMEOUT_MS = 5000;

    /** For a call that writes nothing after the interface token. */
    private static final Arguments NO_ARGUMENTS = data -> {
    };

    private final IBinder service;
    private final Callbacks callbacks;
    private final long attachTimeoutMs;

    private final CountDownLatch attached = new CountDownLatch(1);

    /**
     * Guards {@link #attachState}. Never held across a binder call and never across the wait on
     * {@link #attached}: the callback that counts the latch down runs on another thread and takes
     * this lock to do it.
     */
    private final Object lock = new Object();

    /** What the first {@code bindApplication} carried, and null until one arrives. */
    private Bundle attachState;

    /**
     * Held for the life of the connection: the server keeps only a proxy, so a stub that goes
     * unreachable here stops the pushes arriving.
     */
    private final ShizukuApplication application = new ShizukuApplication();

    ShizukuProtocolWire(@NonNull IBinder binder, @NonNull Callbacks callbacks) {
        this(binder, callbacks, ATTACH_TIMEOUT_MS);
    }

    ShizukuProtocolWire(@NonNull IBinder binder, @NonNull Callbacks callbacks, long attachTimeoutMs) {
        this.service = binder;
        this.callbacks = callbacks;
        this.attachTimeoutMs = attachTimeoutMs;
    }

    /** The binder the server pushes to, answering Shizuku's application descriptor. */
    private final class ShizukuApplication extends Binder implements IInterface {

        ShizukuApplication() {
            attachInterface(this, APPLICATION_DESCRIPTOR);
        }

        /** {@link Binder} carries {@link IBinder} rather than {@link IInterface}. */
        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
                throws RemoteException {
            // Both of these are oneway, so nothing is written back, not even an exception header.
            switch (code) {
                case ShizukuProtocol.APPLICATION_TRANSACTION_bindApplication: {
                    data.enforceInterface(APPLICATION_DESCRIPTOR);
                    onBindApplication(data.readTypedObject(Bundle.CREATOR));
                    return true;
                }
                case ShizukuProtocol.APPLICATION_TRANSACTION_dispatchRequestPermissionResult: {
                    data.enforceInterface(APPLICATION_DESCRIPTOR);
                    int requestCode = data.readInt();
                    Bundle result = data.readTypedObject(Bundle.CREATOR);
                    callbacks.onRequestPermissionResult(requestCode,
                            result != null && result.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, false));
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    /** The binder the server pushes one user service binding to, one per callback. */
    private static final class ShizukuServiceConnection extends Binder implements IInterface {

        private final UserServiceCallback callback;

        ShizukuServiceConnection(@NonNull UserServiceCallback callback) {
            this.callback = callback;
            attachInterface(this, SERVICE_CONNECTION_DESCRIPTOR);
        }

        /** {@link Binder} carries {@link IBinder} rather than {@link IInterface}. */
        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
                throws RemoteException {
            // Both of these are oneway, so nothing is written back, not even an exception header.
            switch (code) {
                case ShizukuProtocol.SERVICE_CONNECTION_TRANSACTION_connected: {
                    data.enforceInterface(SERVICE_CONNECTION_DESCRIPTOR);
                    callback.connected(data.readStrongBinder());
                    return true;
                }
                case ShizukuProtocol.SERVICE_CONNECTION_TRANSACTION_died: {
                    data.enforceInterface(SERVICE_CONNECTION_DESCRIPTOR);
                    callback.died();
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    }

    private void onBindApplication(@Nullable Bundle state) {
        boolean resent;
        synchronized (lock) {
            resent = attachState != null;
            if (!resent) attachState = state == null ? new Bundle() : state;
        }

        if (resent) {
            // A server pushes this again to a client that has already attached when the permission
            // it holds changes, and this wire carries no other signal for that. The handshake state
            // is kept, because the handshake completed against it, and the permission state taken.
            Log.d(TAG, "bindApplication after the handshake, permission state taken");
            // Called outside the lock, as the first push counts the latch down outside it.
            callbacks.onPermissionStateChanged(
                    state != null && state.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false),
                    state != null && state.getBoolean(
                            BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false));
            return;
        }

        attached.countDown();
    }

    @Nullable
    @Override
    public AttachReply attach(String packageName) {
        Bundle args = new Bundle();
        args.putInt(ATTACH_APPLICATION_API_VERSION, ShizukuProtocol.CLIENT_API_VERSION);
        args.putString(ATTACH_APPLICATION_PACKAGE_NAME, packageName);

        callVoid(ShizukuProtocol.TRANSACTION_attachApplication, data -> {
            data.writeStrongBinder(application.asBinder());
            data.writeTypedObject(args, 0);
        });

        return awaitAttachReply();
    }

    /**
     * Shizuku's {@code attachApplication} answers nothing: the state arrives afterwards on the
     * callback binder, from one of this process's binder threads, so the call that started the
     * handshake waits for it here.
     *
     * <p>Never returns a partly-filled reply. A handshake that did not complete throws, which is
     * what abandons the connection instead of publishing one that cannot answer.
     */
    @NonNull
    private AttachReply awaitAttachReply() {
        boolean answered;
        try {
            answered = attached.await(attachTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server to attach", e);
        }
        if (!answered) {
            throw new IllegalStateException(
                    "the server did not answer attach within " + attachTimeoutMs + "ms");
        }

        Bundle state;
        synchronized (lock) {
            state = attachState;
        }

        AttachReply reply = new AttachReply(
                state.getInt(BIND_APPLICATION_SERVER_UID, -1),
                state.getInt(BIND_APPLICATION_SERVER_VERSION, 0),
                state.getString(BIND_APPLICATION_SERVER_SECONTEXT),
                CAPABILITIES_NONE,
                state.getBoolean(BIND_APPLICATION_PERMISSION_GRANTED, false),
                state.getBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false),
                state.containsKey(BIND_APPLICATION_SERVER_PATCH_VERSION)
                        ? state.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION)
                        : null);

        if (reply.protocolVersion < ShizukuProtocol.MINIMUM_VERSION) {
            throw new IllegalStateException("Shizuku protocol " + reply.protocolVersion
                    + " is below the minimum supported " + ShizukuProtocol.MINIMUM_VERSION);
        }
        return reply;
    }

    @Override
    public void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            service.transact(ShizukuProtocol.TRANSACTION_transactRemote, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public void forward(@NonNull IBinder target, int code, @NonNull Parcel data,
                        @Nullable Parcel reply, int flags) {
        // The forwarded flags int is read by servers from protocol 13 on, and this wire refuses to
        // attach below 13, so the envelope always carries it and the outer call is never one-way.
        Parcel newData = Parcel.obtain();
        try {
            newData.writeInterfaceToken(DESCRIPTOR);
            newData.writeStrongBinder(target);
            newData.writeInt(code);
            newData.writeInt(flags);
            newData.appendFrom(data, 0, data.dataSize());
            transactRemote(newData, reply, 0);
        } finally {
            newData.recycle();
        }
    }

    @Override
    public int getUid() {
        return call(ShizukuProtocol.TRANSACTION_getUid, NO_ARGUMENTS, Parcel::readInt);
    }

    @Override
    public String getSELinuxContext() {
        return call(ShizukuProtocol.TRANSACTION_getSELinuxContext, NO_ARGUMENTS, Parcel::readString);
    }

    @Override
    public int checkPermission(String permission) {
        return call(ShizukuProtocol.TRANSACTION_checkPermission,
                data -> data.writeString(permission), Parcel::readInt);
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        return call(ShizukuProtocol.TRANSACTION_getSystemProperty, data -> {
            data.writeString(name);
            data.writeString(defaultValue);
        }, Parcel::readString);
    }

    @Override
    public void setSystemProperty(String name, String value) {
        callVoid(ShizukuProtocol.TRANSACTION_setSystemProperty, data -> {
            data.writeString(name);
            data.writeString(value);
        });
    }

    @Override
    public void requestPermission(int requestCode) {
        callVoid(ShizukuProtocol.TRANSACTION_requestPermission, data -> data.writeInt(requestCode));
    }

    @Override
    public boolean checkSelfPermission() {
        return call(ShizukuProtocol.TRANSACTION_checkSelfPermission, NO_ARGUMENTS,
                ShizukuProtocolWire::readBoolean);
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        return call(ShizukuProtocol.TRANSACTION_shouldShowRequestPermissionRationale, NO_ARGUMENTS,
                ShizukuProtocolWire::readBoolean);
    }

    @Override
    public int addUserService(@NonNull UserServiceCallback conn,
                              @NonNull Porter.UserServiceArgs args, boolean noCreate) {
        IBinder connection = connectionBinderFor(conn);
        Bundle options = ShizukuUserServiceCodec.encodeUserService(args, noCreate);
        return call(ShizukuProtocol.TRANSACTION_addUserService, data -> {
            data.writeStrongBinder(connection);
            data.writeTypedObject(options, 0);
        }, Parcel::readInt);
    }

    @Override
    public int removeUserService(@Nullable UserServiceCallback conn,
                                 @NonNull Porter.UserServiceArgs args, boolean remove) {
        if (!remove && !dropsAConnectionOnRequest()) return 0;

        IBinder connection = connectionBinderFor(conn);
        Bundle options = ShizukuUserServiceCodec.encodeUserServiceRemoval(args, remove);
        return call(ShizukuProtocol.TRANSACTION_removeUserService, data -> {
            data.writeStrongBinder(connection);
            data.writeTypedObject(options, 0);
        }, Parcel::readInt);
    }

    /**
     * Whether this server understands being asked to drop a connection without killing the service.
     * Upstream sends the call only above this gate and clears its own state below it; what a server
     * below the gate makes of such a request is not established here.
     *
     * <p>Nothing being sent below the gate means the server keeps the registration while the facade
     * clears and evicts its own, so a later bind registers a second binder alongside the first.
     * Upstream leaves it there the same way.
     */
    private boolean dropsAConnectionOnRequest() {
        Bundle state;
        synchronized (lock) {
            state = attachState;
        }
        if (state == null) return false;

        int version = state.getInt(BIND_APPLICATION_SERVER_VERSION, 0);
        int patch = state.getInt(BIND_APPLICATION_SERVER_PATCH_VERSION, 0);
        return version >= 14 || (version == 13 && patch >= 4);
    }

    /**
     * The binder this wire has registered for {@code callback}, created on first use, and null for
     * the removal that names no callback at all.
     *
     * <p>Lookup, creation and store happen under one lock on the callback, and the lock is released
     * before the call that carries the result: two threads binding one service register one binder.
     */
    @Nullable
    private static IBinder connectionBinderFor(@Nullable UserServiceCallback callback) {
        if (callback == null) return null;
        synchronized (callback) {
            IBinder registered = callback.registeredBinder(PorterBackend.SHIZUKU);
            if (registered != null) return registered;

            IBinder connection = new ShizukuServiceConnection(callback).asBinder();
            callback.rememberRegisteredBinder(PorterBackend.SHIZUKU, connection);
            return connection;
        }
    }

    @Override
    public void exit() {
        callVoid(ShizukuProtocol.TRANSACTION_exit, NO_ARGUMENTS);
    }

    @Override
    public void attachUserService(@NonNull IBinder binder, @NonNull String token) {
        Bundle options = new Bundle();
        options.putString(USER_SERVICE_ARG_TOKEN, token);
        callVoid(ShizukuProtocol.TRANSACTION_attachUserService, data -> {
            data.writeStrongBinder(binder);
            data.writeTypedObject(options, 0);
        });
    }

    @Override
    public void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime) {
        Bundle result = new Bundle();
        result.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
        result.putBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME, onetime);

        // One-way: no reply parcel is obtained, and no exception header comes back to read.
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(requestUid);
            data.writeInt(requestPid);
            data.writeInt(requestCode);
            data.writeTypedObject(result, 0);
            service.transact(ShizukuProtocol.TRANSACTION_dispatchPermissionConfirmationResult,
                    data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } finally {
            data.recycle();
        }
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        return call(ShizukuProtocol.TRANSACTION_getFlagsForUid, data -> {
            data.writeInt(uid);
            data.writeInt(mask);
        }, Parcel::readInt);
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
        callVoid(ShizukuProtocol.TRANSACTION_updateFlagsForUid, data -> {
            data.writeInt(uid);
            data.writeInt(mask);
            data.writeInt(value);
        });
    }

    /** Writes what follows the interface token. */
    private interface Arguments {
        void writeTo(@NonNull Parcel data);
    }

    /** Reads what follows the exception header. */
    private interface Result<T> {
        T readFrom(@NonNull Parcel reply);
    }

    private <T> T call(int code, @NonNull Arguments arguments, @NonNull Result<T> result) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            arguments.writeTo(data);
            service.transact(code, data, reply, 0);
            reply.readException();
            return result.readFrom(reply);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callVoid(int code, @NonNull Arguments arguments) {
        call(code, arguments, reply -> null);
    }

    /** A boolean travels as an int, which is what the generated proxy writes and reads. */
    private static boolean readBoolean(@NonNull Parcel reply) {
        return reply.readInt() != 0;
    }

    private static RuntimeException rethrowAsRuntimeException(RemoteException e) {
        return new RuntimeException(e);
    }
}
