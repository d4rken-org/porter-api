package eu.darken.porter.sdk.extras;

import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The system service binders of this process, looked up locally. Nothing here reaches the server.
 *
 * <p>example:
 * <br><code>IBinder binder = PorterSystemServices.getSystemService("package");
 * <br>if (binder == null) return;
 * <br>IPackageManager pm = IPackageManager.Stub.asInterface(new PorterBinderWrapper(binder));</code>
 *
 * <p>The lookup reflects into a platform class outside the SDK. An Android release that refuses it
 * leaves every call here throwing {@link IllegalStateException}.
 */
public final class PorterSystemServices {

    private static final String TAG = "PorterSystemServices";

    private static final Map<String, IBinder> CACHE = new ConcurrentHashMap<>();

    @VisibleForTesting
    interface ServiceLookup {
        @Nullable
        IBinder get(String name) throws ReflectiveOperationException;
    }

    @Nullable
    @VisibleForTesting
    static ServiceLookup lookup = reflectiveLookup();

    private PorterSystemServices() {
    }

    @Nullable
    @VisibleForTesting
    static ServiceLookup reflectiveLookup() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            return name -> (IBinder) getService.invoke(null, name);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            Log.w(TAG, Log.getStackTraceString(e));
            return null;
        }
    }

    /**
     * @param name the name of the service, such as {@code "package"} for
     *             {@code android.content.pm.IPackageManager}
     * @return the binder, or null if no service goes by that name
     * @throws IllegalStateException if the lookup itself could not be performed, which says nothing
     *                               about whether the service exists
     */
    @Nullable
    public static IBinder getSystemService(@NonNull String name) {
        ServiceLookup current = lookup;
        if (current == null) {
            throw new IllegalStateException("android.os.ServiceManager.getService is unreachable");
        }
        IBinder cached = CACHE.get(name);
        if (cached != null) return cached;

        IBinder binder;
        try {
            binder = current.get(name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to look up the system service '" + name + "'", e);
        }
        // ConcurrentHashMap forbids a null value, and a missing service is worth asking about again.
        if (binder != null) CACHE.put(name, binder);
        return binder;
    }

    @VisibleForTesting
    static void clearCacheForTest() {
        CACHE.clear();
    }
}
