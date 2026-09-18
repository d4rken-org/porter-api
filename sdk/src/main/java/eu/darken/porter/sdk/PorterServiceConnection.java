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

    @Override
    public void died() {
        binder = null;

        List<ServiceConnection> snapshot;
        synchronized (PorterServiceConnections.LOCK) {
            // One binder carries a recipient per "connected" push, so its death arrives repeatedly.
            if (terminal) return;
            terminal = true;
            snapshot = new ArrayList<>(connections);
            connections.clear();
            PorterServiceConnections.remove(this);
        }

        // By the time the lock is released the death has happened, the set it applies to is
        // captured and this instance is evicted, so a later bind gets one of its own. Only the
        // delivery waits for the main thread.
        MAIN_HANDLER.post(() -> {
                    for (ServiceConnection conn : snapshot) {
                        conn.onServiceDisconnected(componentName);
                    }
                }
        );
    }
}
