package eu.darken.porter.sdk

import android.content.ComponentName
import android.os.IBinder

/**
 * What one caller bound to a user service is told, on [Porter.userServiceExecutor], in the order the
 * SDK queued it. That order is among user service events only, not against [Porter.connection] or
 * the main thread.
 */
internal interface UserServiceListener {

    fun onConnected(componentName: ComponentName, binder: IBinder)

    fun onDisconnected(componentName: ComponentName)
}
