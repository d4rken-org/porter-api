package eu.darken.porter.sdk.consumer

import android.app.Application
import android.os.IBinder
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.isGranted

class ConsumerApplication : Application() {

    fun hasAccess(): Boolean {
        val connection = Porter.connection.value ?: return false
        return connection.isAlive && connection.permission.value.isGranted
    }

    fun wrap(binder: IBinder): IBinder? = Porter.connection.value?.wrap(binder)
}
