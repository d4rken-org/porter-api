package eu.darken.porter.sdk.consumer

import android.os.IBinder
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.extras.PorterSystemServices
import eu.darken.porter.sdk.extras.getSystemPropertyInt

/** Compiles only in the extras build: keeps both of that artifact's entry points off the dead-code path. */
class ExtrasBridge {

    fun sdkInt(): Int? = Porter.connection.value?.getSystemPropertyInt("ro.build.version.sdk", 0)

    fun packageManager(): IBinder? = PorterSystemServices.getSystemService("package")
}
