package rikka.shizuku.server;

import androidx.annotation.Nullable;

import eu.darken.porter.core.CallerIdentity;
import eu.darken.porter.core.ClientCallback;
import moe.shizuku.server.IShizukuApplication;
import rikka.shizuku.server.legacy.LegacyClientCallback;
import rikka.shizuku.server.util.Logger;

public class ClientRecord {

    protected static final Logger LOGGER = new Logger("ClientRecord");

    public final int uid;
    public final int pid;
    /** Null unless the client attached through the Shizuku endpoint. */
    @Nullable
    public final IShizukuApplication client;
    public final ClientCallback callback;
    public final String packageName;
    public final int apiVersion;
    public boolean allowed;

    public ClientRecord(int uid, int pid, IShizukuApplication client, String packageName, int apiVersion) {
        this(new CallerIdentity(uid, pid), new LegacyClientCallback(client), packageName, apiVersion);
    }

    public ClientRecord(CallerIdentity identity, ClientCallback callback, String packageName, int apiVersion) {
        this.uid = identity.uid;
        this.pid = identity.pid;
        this.callback = callback;
        this.client = callback instanceof LegacyClientCallback
                ? ((LegacyClientCallback) callback).application
                : null;
        this.packageName = packageName;
        this.allowed = false;
        this.apiVersion = apiVersion;
    }

    public void dispatchRequestPermissionResult(int requestCode, boolean allowed) {
        try {
            callback.onPermissionResult(requestCode, allowed);
        } catch (Throwable e) {
            LOGGER.w(e, "dispatchRequestPermissionResult failed for client (uid=%d, pid=%d, package=%s)", uid, pid, packageName);
        }
    }
}
