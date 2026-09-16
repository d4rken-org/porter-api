package eu.darken.porter.endpoint;

import static eu.darken.porter.protocol.PorterProtocol.PERMISSION_RESULT_ALLOWED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED;
import static eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Objects;

import eu.darken.porter.core.ClientCallback;
import eu.darken.porter.server.IPorterApplication;

/** A {@link ClientCallback} spoken over the Porter client endpoint. */
public final class PorterClientCallback implements ClientCallback {

    public final IPorterApplication application;

    public PorterClientCallback(IPorterApplication application) {
        this.application = Objects.requireNonNull(application, "application is null");
    }

    @Override
    public IBinder asBinder() {
        return application.asBinder();
    }

    @Override
    public void onPermissionResult(int requestCode, boolean allowed) throws RemoteException {
        Bundle reply = new Bundle();
        reply.putBoolean(PERMISSION_RESULT_ALLOWED, allowed);
        application.dispatchRequestPermissionResult(requestCode, reply);
    }

    @Override
    public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale)
            throws RemoteException {
        Bundle state = new Bundle();
        state.putBoolean(REPLY_PERMISSION_GRANTED, granted);
        state.putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale);
        application.dispatchPermissionStateChanged(state);
    }
}
