package eu.darken.porter.sdk

import android.content.ComponentName
import android.content.Context
import android.content.pm.ProviderInfo
import org.robolectric.Shadows.shadowOf

/**
 * Makes [className] this app's provider at the Shizuku authority, replacing the SDK's own if it was
 * declared. The SDK selects and fetches from Shizuku only where that is [PorterShizukuApiProvider].
 */
internal fun declareShizukuProvider(context: Context, className: String = PorterShizukuApiProvider::class.java.name) {
    val packages = shadowOf(context.packageManager)
    packages.removeProvider(ComponentName(context.packageName, PorterShizukuApiProvider::class.java.name))
    val info = ProviderInfo()
    info.packageName = context.packageName
    info.name = className
    info.authority = context.packageName + ShizukuCompat.AUTHORITY_SUFFIX
    packages.addOrUpdateProvider(info)
}
