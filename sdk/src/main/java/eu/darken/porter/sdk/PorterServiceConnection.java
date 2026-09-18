package eu.darken.porter.sdk;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class PorterServiceConnection implements UserServiceCallback {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** Guarded by {@link PorterServiceConnections#LOCK}. */
    private final Set<ServiceConnection> connections = new HashSet<>();
    /** Guarded by this instance, which is also what a wire locks while it registers one. */
    private final Map<PorterBackend, IBinder> registeredBinders = new EnumMap<>(PorterBackend.class);
    private final ComponentName componentName;
    private IBinder binder;

    /** Set once, by the death of the binder this was bound to. Guarded by the same lock. */
    private boolean terminal = false;

    /** The set a queued death delivery will be handed, null once it ran or was called off. */
    private List<ServiceConnection> pendingDelivery;

    public PorterServiceConnection(Porter.UserServiceArgs args) {
        this.componentName = args.componentName;
    }

    /** @return whether {@code conn} was inserted, so a refused call can remove what it added */
    public boolean addConnection(@Nullable ServiceConnection conn) {
        synchronized (PorterServiceConnections.LOCK) {
            return conn != null && connections.add(conn);
        }
    }

    public void removeConnection(@Nullable ServiceConnection conn) {
        synchronized (PorterServiceConnections.LOCK) {
            if (conn != null) {
                connections.remove(conn);
            }
        }
    }

    public void clearConnections() {
        synchronized (PorterServiceConnections.LOCK) {
            connections.clear();
        }
    }

    @Nullable
    @Override
    public IBinder registeredBinder(@NonNull PorterBackend backend) {
        synchronized (this) {
            return registeredBinders.get(backend);
        }
    }

    @Override
    public void rememberRegisteredBinder(@NonNull PorterBackend backend, @NonNull IBinder binder) {
        synchronized (this) {
            registeredBinders.put(backend, binder);
        }
    }

    @Override
    public void connected(@NonNull IBinder binder) {
        synchronized (PorterServiceConnections.LOCK) {
            // Nothing is registered here any more and nothing can be, so holding the binder and
            // linking a recipient would only keep both alive for a delivery that cannot happen.
            if (terminal) return;
        }

        MAIN_HANDLER.post(() -> {
                    List<ServiceConnection> snapshot;
                    synchronized (PorterServiceConnections.LOCK) {
                        snapshot = new ArrayList<>(connections);
                    }

                    for (ServiceConnection conn : snapshot) {
                        conn.onServiceConnected(componentName, binder);
                    }
                }
        );

        // Hold the binder, or linkToDeath will not work after reference to
        // the binder is dropped
        this.binder = binder;

        try {
            this.binder.linkToDeath(this::died, 0);
        } catch (RemoteException ignored) {
        }
    }

    /** Calls off a delivery that has not run yet, for a caller that no longer wants the binding. */
    void cancelPendingDelivery() {
        synchronized (PorterServiceConnections.LOCK) {
            pendingDelivery = null;
        }
    }

    @Override
    public void died() {
        binder = null;

        synchronized (PorterServiceConnections.LOCK) {
            // One binder carries a recipient per "connected" push, so its death arrives repeatedly.
            if (terminal) return;
            terminal = true;
            pendingDelivery = new ArrayList<>(connections);
            connections.clear();
            PorterServiceConnections.retire(this);

            // Queued while the eviction is still private to this thread: a rebind has to take this
            // same lock to register, so its "connected" cannot reach the queue ahead of this
            // disconnect. Posting is not executing, the body runs later and outside the lock.
            MAIN_HANDLER.post(() -> {
                        List<ServiceConnection> snapshot;
                        synchronized (PorterServiceConnections.LOCK) {
                            snapshot = pendingDelivery;
                            pendingDelivery = null;
                            PorterServiceConnections.deliveryDone(this);
                        }

                        if (snapshot == null) return;

                        for (ServiceConnection conn : snapshot) {
                            conn.onServiceDisconnected(componentName);
                        }
                    }
            );
        }
    }
}
