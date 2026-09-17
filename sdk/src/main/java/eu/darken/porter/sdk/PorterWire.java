package eu.darken.porter.sdk;

import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One server connection, as the calls a client makes on it: the attach handshake, the raw
 * transaction code and everything reachable once attached.
 *
 * <p>An implementation holds the connection it speaks for, and is the only place that names the
 * wire's own types. Nothing here mentions one, so a server speaking another protocol can be reached
 * through a second implementation.
 */
interface PorterWire {

    /** What a session wants told, whatever callback interface the wire actually registers. */
    interface Callbacks {

        void onRequestPermissionResult(int requestCode, boolean allowed);

        void onPermissionStateChanged(boolean granted, boolean shouldShowRationale);
    }

    /** What the server answered, with a defined value for every key it left out. */
    final class AttachReply {

        final int serverUid;
        final int protocolVersion;
        final String seLinuxContext;
        final long capabilities;
        final boolean permissionGranted;
        final boolean shouldShowRequestPermissionRationale;

        AttachReply(int serverUid, int protocolVersion, String seLinuxContext, long capabilities,
                    boolean permissionGranted, boolean shouldShowRequestPermissionRationale) {
            this.serverUid = serverUid;
            this.protocolVersion = protocolVersion;
            this.seLinuxContext = seLinuxContext;
            this.capabilities = capabilities;
            this.permissionGranted = permissionGranted;
            this.shouldShowRequestPermissionRationale = shouldShowRequestPermissionRationale;
        }
    }

    /** The interface token a forwarded transaction must carry. */
    @NonNull
    String descriptor();

    /** @return null if the server answered without a reply at all */
    @Nullable
    AttachReply attach(String packageName) throws RemoteException;

    void transactRemote(@NonNull Parcel data, @Nullable Parcel reply, int flags);

    int getUid();

    String getSELinuxContext();

    int checkPermission(String permission);

    String getSystemProperty(String name, String defaultValue);

    void setSystemProperty(String name, String value);

    void requestPermission(int requestCode);

    boolean checkSelfPermission();

    boolean shouldShowRequestPermissionRationale();

    int addUserService(@NonNull PorterServiceConnection conn, @NonNull Bundle args);

    int removeUserService(@Nullable PorterServiceConnection conn, @NonNull Bundle args);

    void exit();

    void attachUserService(@NonNull IBinder binder, @NonNull String token);

    void dispatchPermissionConfirmationResult(
            int requestUid, int requestPid, int requestCode, boolean allowed, boolean onetime);

    int getFlagsForUid(int uid, int mask);

    void updateFlagsForUid(int uid, int mask, int value);
}
