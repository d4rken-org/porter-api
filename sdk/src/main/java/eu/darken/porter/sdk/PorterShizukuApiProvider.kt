package eu.darken.porter.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Receives the binder a Shizuku server sends, which travels in a different envelope at a different
 * authority than [PorterApiProvider]'s.
 *
 * [Porter.availability] describes this backend on a device that selects it: no package declares
 * Porter's permission, one declares Shizuku's or Shizuku+'s, and the `shizuku-compat` artifact below
 * is on the classpath. Without that artifact the answer stays [PorterAvailability.NotInstalled].
 * Shizuku+ takes the same block below, which requests the stock permission.
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
public class PorterShizukuApiProvider : ContentProvider() {

    // Created on first use, after attach has checked for the artifact: ShizukuProtocolDelivery
    // names the container as a type.
    private val endpoint by lazy { DeliveryEndpoint(ShizukuProtocolDelivery) }

    override fun attachInfo(context: Context, info: ProviderInfo) {
        check(ShizukuCompat.isPresent()) { "shizuku-compat is not on the classpath" }
        super.attachInfo(context, info)
        endpoint.attached(info)
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        return endpoint.call(context, method, extras)
    }

    // no other provider methods
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
