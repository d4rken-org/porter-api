package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static eu.darken.porter.protocol.PorterProtocol.TRANSACTION_transactRemote;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterService;

/** The Porter protocol as a client speaks it, over one server binder. */
final class PorterProtocolWire implements PorterWire {

    private final IPorterService service;
    private final Callbacks callbacks;

    /**
     * Held for the life of the connection: the server keeps only a proxy, so a stub that goes
     * unreachable here stops the pushes arriving.
     */
    private final IPorterApplication application = new IPorterApplication.Stub() {

        @Override
        public void dispatchRequestPermissionResult(int requestCode, Bundle data) {
            callbacks.onRequestPermissionResult(
                    requestCode, data.getBoolean(PERMISSION_RESULT_ALLOWED, false));
        }

        @Override
        public void dispatchPermissionStateChanged(Bundle state) {
            callbacks.onPermissionStateChanged(
                    state.getBoolean(REPLY_PERMISSION_GRANTED, false),
                    state.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false));
        }
    };

    PorterProtocolWire(@NonNull IBinder binder, @NonNull Callbacks callbacks) {
        this.service = IPorterService.Stub.asInterface(binder);
        this.callbacks = callbacks;
    }

    @NonNull
    @Override
    public String descriptor() {
        return PorterProtocol.DESCRIPTOR;
    }

    @Nullable
    @Override
    public AttachReply attach(String packageName) throws RemoteException {
        Bundle args = new Bundle();
        args.putString(ATTACH_PACKAGE_NAME, packageName);
        args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION);

        Bundle reply = service.attach(application, args);
        if (reply == null) return null;
        return new AttachReply(
                reply.getInt(REPLY_SERVER_UID, -1),
                reply.getInt(REPLY_PROTOCOL_VERSION, 0),
                reply.getString(REPLY_SERVER_SECONTEXT),
                reply.getLong(REPLY_CAPABILITIES, CAPABILITIES_NONE),
                reply.getBoolean(REPLY_PERMISSION_GRANTED, false),
                reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false));
    }

    @Override
    public void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            service.asBinder().transact(TRANSACTION_transactRemote, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public int getUid() {
        try {
            return service.getUid();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public String getSELinuxContext() {
        try {
            return service.getSELinuxContext();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public int checkPermission(String permission) {
        try {
            return service.checkPermission(permission);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public String getSystemProperty(String name, String defaultValue) {
        try {
            return service.getSystemProperty(name, defaultValue);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public void setSystemProperty(String name, String value) {
        try {
            service.setSystemProperty(name, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public void requestPermission(int requestCode) {
        try {
            service.requestPermission(requestCode);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public boolean checkSelfPermission() {
        try {
            return service.checkSelfPermission();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public boolean shouldShowRequestPermissionRationale() {
        try {
            return service.shouldShowRequestPermissionRationale();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public int addUserService(@NonNull PorterServiceConnection conn, @NonNull Bundle args) {
        try {
            return service.addUserService(conn, args);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public int removeUserService(@Nullable PorterServiceConnection conn, @NonNull Bundle args) {
        try {
            return service.removeUserService(conn, args);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public void exit() {
        try {
            service.exit();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public void attachUserService(@NonNull IBinder binder, @NonNull String token) {
        Bundle args = new Bundle();
        args.putString(USER_SERVICE_TOKEN, token);
        try {
            service.attachUserService(binder, args);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime) {
        Bundle data = new Bundle();
        data.putBoolean(PERMISSION_CONFIRMATION_ALLOWED, allowed);
        data.putBoolean(PERMISSION_CONFIRMATION_ONETIME, onetime);
        try {
            service.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public int getFlagsForUid(int uid, int mask) {
        try {
            return service.getFlagsForUid(uid, mask);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @Override
    public void updateFlagsForUid(int uid, int mask, int value) {
        try {
            service.updateFlagsForUid(uid, mask, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    private static RuntimeException rethrowAsRuntimeException(RemoteException e) {
        return new RuntimeException(e);
    }
}
