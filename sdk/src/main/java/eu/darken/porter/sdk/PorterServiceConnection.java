package eu.darken.porter.sdk;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class PorterServiceConnection implements UserServiceCallback {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private final Set<ServiceConnection> connections = new HashSet<>();
    /** Guarded by this instance, which is also what a wire locks while it registers one. */
    private final Map<PorterBackend, IBinder> registeredBinders = new EnumMap<>(PorterBackend.class);
    private final ComponentName componentName;
    private IBinder binder;

    public PorterServiceConnection(Porter.UserServiceArgs args) {
        this.componentName = args.componentName;
    }

    private boolean dead = false;
    /** Counts the changes to {@link #connections}, so a queued death can tell it is stale. */
    private int generation = 0;

    /** @return whether {@code conn} was not registered already */
    public boolean addConnection(@Nullable ServiceConnection conn) {
        if (conn == null || !connections.add(conn)) return false;
        generation++;
        // A registration made after a death is a live binding again, and is owed a later one.
        dead = false;
        return true;
    }

    public void removeConnection(@Nullable ServiceConnection conn) {
        if (conn != null) {
            connections.remove(conn);
        }
    }

    public void clearConnections() {
        generation++;
        connections.clear();
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
        MAIN_HANDLER.post(() -> {
                    for (ServiceConnection conn : connections) {
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

        if (dead) return;
        dead = true;

        // A rebind can land before this runs. It is then a binding of its own, and the death
        // belongs to the one it replaced: telling it would disconnect a caller nothing happened to.
        int atDeath = generation;
        MAIN_HANDLER.post(() -> {
                    if (generation != atDeath) return;

                    for (ServiceConnection conn : connections) {
                        conn.onServiceDisconnected(componentName);
                    }

                    connections.clear();
                    PorterServiceConnections.remove(this);
                }
        );
    }
}
