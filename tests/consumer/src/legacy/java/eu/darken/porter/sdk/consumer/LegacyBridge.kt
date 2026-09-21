package eu.darken.porter.sdk.consumer

import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/** Compiles only in the dual-SDK build: both SDKs on one classpath, sharing no class. */
class LegacyBridge {

    fun shizukuAlive(): Boolean = Shizuku.pingBinder()

    fun shizukuManager(): String = ShizukuProvider.MANAGER_APPLICATION_ID
}
