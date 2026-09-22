package eu.darken.porter.sdk

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.VisibleForTesting
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
 * If the app runs in several processes, see [requestBinderForNonProviderProcess].
 *
 * A subclass can answer further methods in [call] and hand the rest to `super.call`; nothing else
 * can be overridden.
 */
public open class PorterApiProvider : ContentProvider() {

    private val endpoint = DeliveryEndpoint(PorterProtocolDelivery)

    final override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        endpoint.attached(info)
    }

    final override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
        endpoint.call(requireContext(), method, extras)

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

        internal const val ACTION_BINDER_RECEIVED: String = "eu.darken.porter.sdk.action.BINDER_RECEIVED"

        /** Set by a provider of this SDK attaching in this process. */
        @Volatile
        internal var isProviderProcess = false

        /** Guards [registered]. */
        private val registration = Any()

        /** Whether this process already listens for announcements and deaths. */
        private var registered = false

        /** Set while a fetch is queued and has not started, so a burst of requests queues one. */
        private val fetchQueued = AtomicBoolean(false)

        /**
         * Obtains the connection in a process that does not host the provider, and keeps obtaining
         * the next one whenever the provider process accepts it. The result arrives on
         * [Porter.connection]; nothing here waits for it, so any thread may call this, and calling
         * it again only asks the provider process once more.
         *
         * In the process that hosts the provider this does nothing: the server delivers there.
         */
        public fun requestBinderForNonProviderProcess(context: Context) {
            val appContext = context.applicationContext ?: context
            if (hostsProvider(appContext)) return

            synchronized(registration) {
                if (!registered) {
                    register(appContext)
                    registered = true
                }
            }

            scheduleFetch(appContext)
        }

        private fun register(appContext: Context) {
            Log.d(TAG, "listening for the provider process's binder")

            // Below API 33 a registered receiver is exported, so any app can send this action to us.
            // Treat the broadcast as a notification only and read the binder from the provider, which
            // android:permission and the same-uid exemption restrict to this app and the server.
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.i(TAG, "binder announced by broadcast")
                    scheduleFetch(appContext)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, IntentFilter(ACTION_BINDER_RECEIVED), Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, IntentFilter(ACTION_BINDER_RECEIVED))
            }

            // A delivery this process refused was refused because a connection was live. Once that
            // connection dies nothing is live, the refusal no longer applies, and the binder the
            // provider process holds can be adopted. It runs as a post-death hook so that every
            // collector sees that death before the replacement announces itself.
            Porter.addPostBinderDeadHook { scheduleFetch(appContext) }
        }

        /** Fetches off the calling thread: the attach behind it blocks, for seconds on the Shizuku wire. */
        private fun scheduleFetch(appContext: Context) {
            if (!fetchQueued.compareAndSet(false, true)) return
            Porter.deliveryExecutor.execute {
                // Cleared before the fetch, so an announcement that arrives during it queues another.
                fetchQueued.set(false)
                fetchBinderFromProvider(appContext)
            }
        }

        /**
         * Whether this process is the one the provider runs in. A provider that attached here says so
         * directly; before it attaches, the declared process name is compared with this process's.
         */
        private fun hostsProvider(context: Context): Boolean {
            if (isProviderProcess) return true
            val current = currentProcessName() ?: return false
            val providers = listOf(PorterApiProvider::class.java, PorterShizukuApiProvider::class.java)
            return providers.any { provider ->
                val info = try {
                    context.packageManager.getProviderInfo(ComponentName(context, provider), 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
                // A provider without a process of its own runs in the application's.
                info != null && current == (info.processName ?: context.applicationInfo.processName)
            }
        }

        private fun currentProcessName(): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Application.getProcessName()
            } else {
                try {
                    File("/proc/self/cmdline").readText().substringBefore('\u0000').takeIf { it.isNotEmpty() }
                } catch (e: IOException) {
                    null
                }
            }

        /**
         * Asks every authority a server can have delivered to, because the connection this process
         * is looking for is on whichever backend the provider process attached. Blocks for the attach.
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

        /** Forgets the registration and the provider flag, so one test cannot see another's. */
        @VisibleForTesting
        internal fun resetForTest() {
            synchronized(registration) {
                registered = false
            }
            fetchQueued.set(false)
            isProviderProcess = false
        }
    }
}
