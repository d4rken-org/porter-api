package eu.darken.porter.sdk

import android.content.ComponentName
import android.os.IBinder

/** What one caller bound to a user service is told, on the main thread, in the order it happened. */
internal interface UserServiceListener {

    fun onConnected(componentName: ComponentName, binder: IBinder)

    fun onDisconnected(componentName: ComponentName)
}
