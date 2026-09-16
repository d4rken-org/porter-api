package eu.darken.porter.sdk;

import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME;
import static eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_CONFIRMATION_ONETIME;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;
import static eu.darken.porter.protocol.PorterProtocol.TRANSACTION_transactRemote;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_COMPONENT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DAEMON;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_DEBUGGABLE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_NO_CREATE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_PROCESS_NAME_SUFFIX;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_REMOVE;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TAG;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_TOKEN;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_USE_32_BIT;
import static eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_VERSION_CODE;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import eu.darken.porter.protocol.PorterProtocol;
import eu.darken.porter.server.IPorterApplication;
import eu.darken.porter.server.IPorterRemoteProcess;
import eu.darken.porter.server.IPorterService;
import eu.darken.porter.server.IPorterServiceConnection;

/**
 * The Porter protocol as a client speaks it: the attach handshake, the raw transaction code and the
 * calls a connection makes.
 *
 * <p>{@link Porter.UserServiceArgs}, {@link PorterBinderWrapper} and the per-connection application
 * callback name protocol keys of their own; everything else asks here.
 */
final class PorterWire {

    private PorterWire() {
    }

    @NonNull
    static IPorterService asService(@NonNull IBinder binder) {
        return IPorterService.Stub.asInterface(binder);
    }

    /** What the server answered, with a defined value for every key it left out. */
    static final class AttachReply {

        final int serverUid;
        final int protocolVersion;
        final String seLinuxContext;
        final long capabilities;
        final boolean permissionGranted;
        final boolean shouldShowRequestPermissionRationale;

        private AttachReply(@NonNull Bundle reply) {
            serverUid = reply.getInt(REPLY_SERVER_UID, -1);
            protocolVersion = reply.getInt(REPLY_PROTOCOL_VERSION, 0);
            seLinuxContext = reply.getString(REPLY_SERVER_SECONTEXT);
            capabilities = reply.getLong(REPLY_CAPABILITIES, CAPABILITIES_NONE);
            permissionGranted = reply.getBoolean(REPLY_PERMISSION_GRANTED, false);
            shouldShowRequestPermissionRationale =
                    reply.getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false);
        }
    }

    /** @return null if the server answered without a reply at all */
    @Nullable
    static AttachReply attach(
            @NonNull IPorterService service, @NonNull IPorterApplication application, String packageName)
            throws RemoteException {
        Bundle args = new Bundle();
        args.putString(ATTACH_PACKAGE_NAME, packageName);
        args.putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION);

        Bundle reply = service.attach(application, args);
        return reply == null ? null : new AttachReply(reply);
    }

    static void transactRemote(
            @NonNull IPorterService service, @NonNull Parcel data, @Nullable Parcel reply, int flags) {
        try {
            service.asBinder().transact(TRANSACTION_transactRemote, data, reply, flags);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @NonNull
    static IPorterRemoteProcess newProcess(
            @NonNull IPorterService service, @NonNull String[] cmd, @Nullable String[] env, @Nullable String dir) {
        try {
            return service.newProcess(cmd, env, dir);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static int getUid(@NonNull IPorterService service) {
        try {
            return service.getUid();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static String getSELinuxContext(@NonNull IPorterService service) {
        try {
            return service.getSELinuxContext();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static int checkPermission(@NonNull IPorterService service, String permission) {
        try {
            return service.checkPermission(permission);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static String getSystemProperty(@NonNull IPorterService service, String name, String defaultValue) {
        try {
            return service.getSystemProperty(name, defaultValue);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static void setSystemProperty(@NonNull IPorterService service, String name, String value) {
        try {
            service.setSystemProperty(name, value);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static void requestPermission(@NonNull IPorterService service, int requestCode) {
        try {
            service.requestPermission(requestCode);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static boolean checkSelfPermission(@NonNull IPorterService service) {
        try {
            return service.checkSelfPermission();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static boolean shouldShowRequestPermissionRationale(@NonNull IPorterService service) {
        try {
            return service.shouldShowRequestPermissionRationale();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static int addUserService(
            @NonNull IPorterService service, @NonNull IPorterServiceConnection conn, @NonNull Bundle args) {
        try {
            return service.addUserService(conn, args);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static int removeUserService(
            @NonNull IPorterService service, @Nullable IPorterServiceConnection conn, @NonNull Bundle args) {
        try {
            return service.removeUserService(conn, args);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    @NonNull
    static Bundle encodeUserService(@NonNull Porter.UserServiceArgs args) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(USER_SERVICE_COMPONENT, args.componentName);
        bundle.putBoolean(USER_SERVICE_DEBUGGABLE, args.debuggable);
        bundle.putInt(USER_SERVICE_VERSION_CODE, args.versionCode);
        bundle.putBoolean(USER_SERVICE_DAEMON, args.daemon);
        bundle.putBoolean(USER_SERVICE_USE_32_BIT, args.use32BitAppProcess);
        bundle.putString(USER_SERVICE_PROCESS_NAME_SUFFIX,
                Objects.requireNonNull(args.processName, "process name suffix must not be null"));
        if (args.tag != null) {
            bundle.putString(USER_SERVICE_TAG, args.tag);
        }
        return bundle;
    }

    /** Marks an encoded user service "do not start it", as {@code peekUserService} asks for. */
    @NonNull
    static Bundle withoutCreation(@NonNull Bundle encoded) {
        encoded.putBoolean(USER_SERVICE_NO_CREATE, true);
        return encoded;
    }

    @NonNull
    static Bundle encodeUserServiceRemoval(@NonNull Porter.UserServiceArgs args, boolean remove) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(USER_SERVICE_COMPONENT, args.componentName);
        if (args.tag != null) {
            bundle.putString(USER_SERVICE_TAG, args.tag);
        }
        bundle.putBoolean(USER_SERVICE_REMOVE, remove);
        return bundle;
    }

    static void exit(@NonNull IPorterService service) {
        try {
            service.exit();
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static void attachUserService(
            @NonNull IPorterService service, @NonNull IBinder binder, @NonNull String token) {
        Bundle args = new Bundle();
        args.putString(USER_SERVICE_TOKEN, token);
        try {
            service.attachUserService(binder, args);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static void dispatchPermissionConfirmationResult(
            @NonNull IPorterService service, int requestUid, int requestPid, int requestCode,
            boolean allowed, boolean onetime) {
        Bundle data = new Bundle();
        data.putBoolean(PERMISSION_CONFIRMATION_ALLOWED, allowed);
        data.putBoolean(PERMISSION_CONFIRMATION_ONETIME, onetime);
        try {
            service.dispatchPermissionConfirmationResult(requestUid, requestPid, requestCode, data);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static int getFlagsForUid(@NonNull IPorterService service, int uid, int mask) {
        try {
            return service.getFlagsForUid(uid, mask);
        } catch (RemoteException e) {
            throw rethrowAsRuntimeException(e);
        }
    }

    static void updateFlagsForUid(@NonNull IPorterService service, int uid, int mask, int value) {
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
