package rikka.shizuku.server.legacy;

import static rikka.shizuku.ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Objects;

import eu.darken.porter.core.ClientCallback;
import moe.shizuku.server.IShizukuApplication;

/** A {@link ClientCallback} spoken over the Shizuku client endpoint. */
public final class LegacyClientCallback implements ClientCallback {

    public final IShizukuApplication application;

    public LegacyClientCallback(IShizukuApplication application) {
        this.application = Objects.requireNonNull(application, "application is null");
    }

    @Override
    public IBinder asBinder() {
        return application.asBinder();
    }

    @Override
    public void onPermissionResult(int requestCode, boolean allowed) throws RemoteException {
        Bundle reply = new Bundle();
        reply.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, allowed);
        application.dispatchRequestPermissionResult(requestCode, reply);
    }

    @Override
    public void onPermissionStateChanged(boolean granted, boolean shouldShowRationale) {
    }
}
