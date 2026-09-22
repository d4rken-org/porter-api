package eu.darken.porter.sdk

import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.util.Log
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER

/**
 * What a delivery provider does with the calls a server and this app's other processes make on it,
 * in the envelope of one [delivery]. The providers themselves only forward to it.
 */
internal class DeliveryEndpoint(private val delivery: PorterDelivery) {

    fun attached(info: ProviderInfo) {
        check(!info.multiprocess) { "android:multiprocess must be false" }
        check(info.exported) { "android:exported must be true" }

        PorterApiProvider.isProviderProcess = true
    }

    fun call(context: Context, method: String, extras: Bundle?): Bundle? {
        if (extras == null) return null

        val reply = Bundle()
        when (method) {
            DELIVERY_METHOD_SEND_BINDER -> handleSendBinder(context, extras)
            DELIVERY_METHOD_GET_BINDER -> if (!handleGetBinder(reply)) return null
        }
        return reply
    }

    private fun handleSendBinder(context: Context, extras: Bundle) {
        // A provider call runs on a binder thread, so pinging and attaching here block no one.
        if (Porter.connection.value?.binder?.pingBinder() == true) {
            Log.d(TAG, "sendBinder is called when already a living binder")
            return
        }

        val binder = delivery.readBinder(extras)
        if (binder == null) {
            Log.w(TAG, "sendBinder is called without a binder")
            return
        }

        Log.d(TAG, "binder received")

        if (!Porter.onBinderReceived(context, binder, context.packageName, delivery.backend)) return

        // Only a notification: the other processes read the binder from the provider, never from
        // the broadcast, which below API 33 any app can send to a registered receiver.
        context.sendBroadcast(Intent(PorterApiProvider.ACTION_BINDER_RECEIVED).setPackage(context.packageName))
    }

    private fun handleGetBinder(reply: Bundle): Boolean {
        // Other processes in the same app can read the provider without permission
        val binder = Porter.binderFor(delivery.backend) ?: return false
        delivery.writeBinder(reply, binder)
        return true
    }

    private companion object {
        const val TAG = "PorterApiProvider"
    }
}
