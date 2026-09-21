package eu.darken.porter.sdk

import android.os.Bundle
import android.os.IBinder

/**
 * One way a server hands a binder to [PorterApiProvider]: the authority it calls, the envelope
 * the binder travels in, and the backend a binder that arrived this way belongs to.
 */
internal interface PorterDelivery {

    /** Appended to the package name to form the provider authority. */
    val authoritySuffix: String

    val backend: PorterBackend

    fun readBinder(extras: Bundle): IBinder?

    fun writeBinder(reply: Bundle, binder: IBinder)
}
