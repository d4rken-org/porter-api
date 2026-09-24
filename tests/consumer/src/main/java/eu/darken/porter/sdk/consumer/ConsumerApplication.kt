package eu.darken.porter.sdk.consumer

import android.app.Application
import android.content.ComponentName
import android.os.IBinder
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.UserServiceArgs
import eu.darken.porter.sdk.isGranted
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ConsumerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val services = listOf(ConsumerService::class.java, ConsumerContextService::class.java).map {
            UserServiceArgs(componentName = ComponentName(this, it), processNameSuffix = it.simpleName)
        }
        MainScope().launch {
            val connection = Porter.connection.filterNotNull().first()
            for (args in services) connection.userService(args).first()
        }
    }

    suspend fun hasAccess(): Boolean {
        val connection = Porter.connection.value ?: return false
        return connection.isAlive() && connection.permission.value.isGranted
    }

    fun wrap(binder: IBinder): IBinder? = Porter.connection.value?.wrap(binder)
}
