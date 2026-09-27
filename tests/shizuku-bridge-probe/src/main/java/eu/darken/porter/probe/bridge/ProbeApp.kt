package eu.darken.porter.probe.bridge

import android.app.Application
import android.util.Log
import eu.darken.porter.bridge.PorterShizukuBridge
import eu.darken.porter.sdk.PorterApiProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku

const val TAG = "BridgeProbe"

fun log(message: String) {
    Log.i(TAG, message)
}

/** API 28 added Application.getProcessName; this works back to API 24. */
fun currentProcessName(): String =
    java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')

class ProbeApp : Application() {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Ackpine's own exemption runs from a provider, so only the main process has it.
        if (currentProcessName().contains(':') && android.os.Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/", "Landroid/os/")
        }
        // Registered before the bridge starts, so the first delivery is heard too.
        Shizuku.addBinderReceivedListener {
            log("BINDER_RECEIVED ping=${Shizuku.pingBinder()} uid=${Shizuku.getUid()} version=${Shizuku.getVersion()} " +
                "granted=${Shizuku.checkSelfPermission() == 0}")
        }
        Shizuku.addBinderDeadListener { log("BINDER_DEAD ping=${Shizuku.pingBinder()}") }
        Shizuku.addRequestPermissionResultListener { code, result -> log("PERMISSION_RESULT code=$code result=$result") }
        PorterShizukuBridge.start(scope)
        // Does nothing in the process that receives the connection, so every process calls it.
        PorterApiProvider.requestBinderForNonProviderProcess(this)
        log("STARTED process=${currentProcessName()}")
    }
}
