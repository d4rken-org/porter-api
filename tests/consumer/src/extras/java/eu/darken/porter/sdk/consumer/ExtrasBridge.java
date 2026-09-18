package eu.darken.porter.sdk.consumer;

import android.os.IBinder;

import eu.darken.porter.sdk.extras.PorterSystemProperties;
import eu.darken.porter.sdk.extras.PorterSystemServices;

/** Compiles only in the extras build: keeps both of that artifact's classes off the dead-code path. */
public class ExtrasBridge {
    public int sdkInt() {
        return PorterSystemProperties.getInt("ro.build.version.sdk", 0);
    }

    public IBinder packageManager() {
        return PorterSystemServices.getSystemService("package");
    }
}
