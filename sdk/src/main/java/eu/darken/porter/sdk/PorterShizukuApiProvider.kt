package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.ProviderInfo

/**
 * Receives the binder a Shizuku server sends, which travels in a different envelope at a different
 * authority than [PorterApiProvider]'s.
 *
 * [Porter.availability] describes this backend on a device that selects it: no package declares
 * Porter's permission, one declares Shizuku's, and the `shizuku-compat` artifact below is on the
 * classpath. Without that artifact the answer stays [PorterAvailability.NOT_INSTALLED].
 *
 * The SDK declares nothing at that authority. An app that wants Shizuku delivery adds the whole
 * block itself, the permission and the meta-data included: the server refuses an app that requests
 * neither.
 *
 * ```xml
 * <uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
 *
 * <application>
 *     <meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true" />
 *     <provider
 *         android:name="eu.darken.porter.sdk.PorterShizukuApiProvider"
 *         android:authorities="${applicationId}.shizuku"
 *         android:exported="true"
 *         android:multiprocess="false"
 *         android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
 * </application>
 * ```
 *
 * This envelope needs the optional `com.github.d4rken-org.porter-api:shizuku-compat` artifact,
 * which the SDK does not depend on. Declaring the provider without it throws at attach.
 */
public open class PorterShizukuApiProvider : PorterApiProvider() {

    override fun attachInfo(context: Context, info: ProviderInfo) {
        check(ShizukuCompat.isPresent()) { "shizuku-compat is not on the classpath" }
        super.attachInfo(context, info)
    }

    override val delivery: PorterDelivery get() = ShizukuProtocolDelivery
}
