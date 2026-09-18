package eu.darken.porter.sdk;

import android.content.ServiceConnection;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class PorterServiceConnections {

    /**
     * Guards {@link #CACHE} and, in every {@link PorterServiceConnection}, its set of registered
     * {@link ServiceConnection}s and its terminal flag.
     */
    static final Object LOCK = new Object();

    private static final Map<String, PorterServiceConnection> CACHE = new HashMap<>();

    @NonNull
    static PorterServiceConnection get(Porter.UserServiceArgs args) {
        synchronized (LOCK) {
            return lookupOrCreate(args);
        }
    }

    /** A connection and whether the registration that produced it inserted the callback. */
    static final class Registration {

        final PorterServiceConnection connection;
        final boolean inserted;

        private Registration(PorterServiceConnection connection, boolean inserted) {
            this.connection = connection;
            this.inserted = inserted;
        }
    }

    /**
     * Looks up or creates the connection for {@code args} and registers {@code conn} on it, as one
     * locked operation: a bind arriving after a death finds no cached instance and gets a fresh
     * one, with no window in which it could register on the instance that just died.
     */
    @NonNull
    static Registration register(Porter.UserServiceArgs args, @Nullable ServiceConnection conn) {
        synchronized (LOCK) {
            PorterServiceConnection connection = lookupOrCreate(args);
            return new Registration(connection, connection.addConnection(conn));
        }
    }

    @NonNull
    private static PorterServiceConnection lookupOrCreate(Porter.UserServiceArgs args) {
        String key = key(args);
        PorterServiceConnection connection = CACHE.get(key);

        if (connection == null) {
            connection = new PorterServiceConnection(args);
            CACHE.put(key, connection);
        }
        return connection;
    }

    /** The cached connection for {@code args}, and null when nothing is bound under it. */
    @Nullable
    static PorterServiceConnection peek(Porter.UserServiceArgs args) {
        synchronized (LOCK) {
            return CACHE.get(key(args));
        }
    }

    private static String key(Porter.UserServiceArgs args) {
        return args.tag != null ? args.tag : args.componentName.getClassName();
    }

    static void remove(PorterServiceConnection connection) {
        synchronized (LOCK) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, PorterServiceConnection> entry : CACHE.entrySet()) {
                if (entry.getValue() == connection) {
                    keys.add(entry.getKey());
                }
            }
            for (String key : keys) {
                CACHE.remove(key);
            }
        }
    }

    static void clearForTest() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }
}
