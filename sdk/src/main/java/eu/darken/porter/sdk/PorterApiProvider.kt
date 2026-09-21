package eu.darken.porter.sdk

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER

/**
 * Receives the binder the Porter server sends when the app process starts. The SDK declares this
 * provider itself at `${applicationId}.porter.api`; an app that must not expose it removes the
 * declaration with `tools:node="remove"`.
 *
 * `android:permission` has to be one granted to the shell but not to normal apps, so that only
 * this app and the server can reach it; `android:exported` has to be true for the server to reach
 * it at all; `android:multiprocess` has to be false because the server reads the uid once, when
 * the app starts.
 *
 * If the app runs in several processes, see [enableMultiProcessSupport].
 */
public open class PorterApiProvider : ContentProvider() {

    /** The envelope this provider speaks; a subclass overrides it to answer another authority. */
    internal open val delivery: PorterDelivery get() = PorterProtocolDelivery

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)

        check(!info.multiprocess) { "android:multiprocess must be false" }
        check(info.exported) { "android:exported must be true" }

        isProviderProcess = true
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null

        val reply = Bundle()
        when (method) {
            DELIVERY_METHOD_SEND_BINDER -> handleSendBinder(extras)
            DELIVERY_METHOD_GET_BINDER -> if (!handleGetBinder(reply)) return null
        }
        return reply
    }

    private fun handleSendBinder(extras: Bundle) {
        if (Porter.connection.value?.isAlive == true) {
            Log.d(TAG, "sendBinder is called when already a living binder")
            return
        }

        val binder = delivery.readBinder(extras)
        if (binder == null) {
            Log.w(TAG, "sendBinder is called without a binder")
            return
        }

        Log.d(TAG, "binder received")

        val context = requireContext()
        Porter.onBinderReceived(context, binder, context.packageName, delivery.backend)

        if (enableMultiProcess) {
            Log.d(TAG, "broadcast binder")

            val intent = Intent(ACTION_BINDER_RECEIVED).setPackage(context.packageName)
            context.sendBroadcast(intent)
        }
    }

    private fun handleGetBinder(reply: Bundle): Boolean {
        // Other processes in the same app can read the provider without permission
        val binder = Porter.binderFor(delivery.backend) ?: return false
        delivery.writeBinder(reply, binder)
        return true
    }

    // no other provider methods
    final override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    final override fun getType(uri: Uri): String? = null

    final override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    final override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    final override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    public companion object {

        private const val TAG = "PorterApiProvider"

        public const val ACTION_BINDER_RECEIVED: String = "eu.darken.porter.sdk.action.BINDER_RECEIVED"

        private var enableMultiProcess = false

        private var isProviderProcess = false

        /**
         * Enables the built-in multi-process support. Call this as early as possible, for instance
         * in the Application's constructor.
         */
        public fun enableMultiProcessSupport(isProviderProcess: Boolean) {
            Log.d(TAG, "Enable built-in multi-process support (from " +
                (if (isProviderProcess) "provider process" else "non-provider process") + ")")

            this.isProviderProcess = isProviderProcess
            enableMultiProcess = true
        }

        /**
         * Asks for the binder in a process that does not host the provider;
         * [enableMultiProcessSupport] must have been called first.
         */
        public fun requestBinderForNonProviderProcess(context: Context) {
            if (isProviderProcess) return

            Log.d(TAG, "request binder in non-provider process")

            // Below API 33 a registered receiver is exported, so any app can send this action to us.
            // Treat the broadcast as a notification only and read the binder from the provider, which
            // android:permission and the same-uid exemption restrict to this app and the server.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.i(TAG, "binder announced by broadcast")
                    fetchBinderFromProvider(context)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(ACTION_BINDER_RECEIVED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(ACTION_BINDER_RECEIVED))
            }

            // A delivery this process refused was refused because a connection was live. Once that
            // connection dies nothing is live, the refusal no longer applies, and the binder the
            // provider process holds can be adopted. It runs as a post-death hook so that every
            // collector sees that death before the replacement announces itself.
            val appContext = context.applicationContext
            Porter.addPostBinderDeadHook { fetchBinderFromProvider(appContext) }

            fetchBinderFromProvider(context)
        }

        /**
         * Asks every authority a server can have delivered to, because the connection this process
         * is looking for is on whichever backend the provider process attached.
         */
        internal fun fetchBinderFromProvider(context: Context): Boolean {
            if (fetchThrough(context, PorterProtocolDelivery)) return true
            // ShizukuProtocolDelivery names the container as a type, so the class cannot load at all
            // where the optional artifact is absent.
            return ShizukuCompat.isPresent() && fetchThrough(context, ShizukuProtocolDelivery)
        }

        internal fun fetchThrough(context: Context, delivery: PorterDelivery): Boolean {
            val reply = try {
                context.contentResolver.call(
                    Uri.parse("content://" + context.packageName + delivery.authoritySuffix),
                    DELIVERY_METHOD_GET_BINDER, null, Bundle(),
                )
            } catch (tr: Throwable) {
                null
            } ?: return false

            val binder = delivery.readBinder(reply) ?: return false

            Log.i(TAG, "Binder received from other process")
            // Ungated on purpose: this is the provider process's own connection, on the backend that
            // process already selected. Rejecting it here would refuse a binder the app is using.
            Porter.adoptBackend(delivery.backend)
            Porter.onBinderReceived(binder, context.packageName, delivery.backend)
            return true
        }
    }
}
