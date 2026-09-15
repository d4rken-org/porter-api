package rikka.shizuku.server;

import static rikka.shizuku.ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;

import java.util.UUID;

import eu.darken.porter.core.UserServiceConnection;
import rikka.shizuku.server.util.HandlerUtil;
import rikka.shizuku.server.util.Logger;

public abstract class UserServiceRecord {

    private Runnable startTimeoutCallback;

    private class ConnectionList extends RemoteCallbackList<UserServiceConnection> {

        @Override
        public void onCallbackDied(UserServiceConnection callback) {
            if (daemon || getRegisteredCallbackCount() != 0) {
                return;
            }

            LOGGER.v("Remove service record %s since it does not run as a daemon and all connections are gone", token);
            removeSelf();
        }
    }

    protected static final Logger LOGGER = new Logger("UserServiceRecord");

    private final IBinder.DeathRecipient deathRecipient;
    public final int versionCode;
    public String token;
    public IBinder service;
    public final RemoteCallbackList<UserServiceConnection> callbacks = new ConnectionList();
    public boolean daemon;
    /**
     * Written under the manager monitor, read from the start executor and the main handler, neither
     * of which holds it.
     */
    public volatile boolean starting;
    private volatile boolean removed;
    /**
     * Acquired once, with no monitor held, by whoever publishes the binder. {@link #destroy()} needs
     * it and cannot ask the remote for it: that is a synchronous round trip a wedged service never
     * answers.
     */
    private volatile String interfaceDescriptor;

    public UserServiceRecord(int versionCode, boolean daemon) {
        this.versionCode = versionCode;
        this.token = UUID.randomUUID().toString() + "-" + System.currentTimeMillis();
        this.deathRecipient = () -> {
            LOGGER.v("Binder for service record %s is dead", token);
            removeSelf();
        };
        this.daemon = daemon;
    }

    public void setStartingTimeout(long timeoutMillis) {
        if (starting) {
            LOGGER.w("Service record %s is already starting", token);
            return;
        }

        LOGGER.v("Set starting timeout for service record %s: %d", token, timeoutMillis);

        starting = true;
        startTimeoutCallback = () -> {
            if (!removed && starting) {
                LOGGER.w("Service record %s is not started in %d ms", token, timeoutMillis);
                removeSelf();
            }
        };
        HandlerUtil.getMainHandler().postDelayed(startTimeoutCallback, timeoutMillis);
    }

    /**
     * Marks the record detached from every index. A record only ever goes from live to removed, so
     * the callers that consult {@link #isRemoved()} without the monitor cannot miss a later revival.
     */
    public void markRemoved() {
        removed = true;
        starting = false;
        if (startTimeoutCallback != null) {
            HandlerUtil.getMainHandler().removeCallbacks(startTimeoutCallback);
        }
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public void setBinder(IBinder binder, String interfaceDescriptor) {
        LOGGER.v("Binder received for service record %s", token);

        HandlerUtil.getMainHandler().removeCallbacks(startTimeoutCallback);

        service = binder;
        this.interfaceDescriptor = interfaceDescriptor;

        try {
            binder.linkToDeath(deathRecipient, 0);
        } catch (Throwable tr) {
            LOGGER.w("linkToDeath %s", token);
        }

        broadcastBinderReceived();
    }

    public void broadcastBinderReceived() {
        LOGGER.v("Broadcast binder received for service record %s", token);

        int count = callbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbacks.getBroadcastItem(i).connected(service);
            } catch (Throwable e) {
                LOGGER.w("Failed to call connected %s", token);
            }
        }
        callbacks.finishBroadcast();
    }

    public void broadcastBinderDied() {
        LOGGER.v("Broadcast binder died for service record %s", token);

        int count = callbacks.beginBroadcast();
        for (int i = 0; i < count; i++) {
            try {
                callbacks.getBroadcastItem(i).died();
            } catch (Throwable e) {
                LOGGER.w("Failed to call died %s", token);
            }
        }
        callbacks.finishBroadcast();
    }

    public abstract void removeSelf();

    public void destroy() {
        try {
            if (service != null) {
                try {
                    service.unlinkToDeath(deathRecipient, 0);
                } catch (Throwable tr) {
                    LOGGER.w("unlinkToDeath %s", token);
                }
            }

            // A record removed by start timeout never received a binder, so both guards stay.
            if (service != null && interfaceDescriptor != null) {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(interfaceDescriptor);
                    service.transact(USER_SERVICE_TRANSACTION_destroy, data, reply, Binder.FLAG_ONEWAY);
                } catch (Throwable e) {
                    LOGGER.w("Failed to call destroy %s", token);
                } finally {
                    data.recycle();
                    reply.recycle();
                }
            } else if (service != null) {
                // Parcel.writeInterfaceToken(null) reaches a JNI null check that aborts the process,
                // which no catch here would see.
                LOGGER.w("No interface descriptor for service record %s, cannot request destroy", token);
            }
        } finally {
            callbacks.kill();
        }
    }
}
