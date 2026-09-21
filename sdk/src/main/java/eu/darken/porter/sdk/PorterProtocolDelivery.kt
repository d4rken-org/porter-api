package eu.darken.porter.sdk

import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER
import eu.darken.porter.protocol.PorterProtocol.PROVIDER_AUTHORITY_SUFFIX

/** Porter's envelope: the binder is a Bundle binder value, with no Parcelable in between. */
internal object PorterProtocolDelivery : PorterDelivery {

    override val authoritySuffix: String = PROVIDER_AUTHORITY_SUFFIX

    override val backend: PorterBackend = PorterBackend.PORTER

    override fun readBinder(extras: Bundle): IBinder? = extras.getBinder(DELIVERY_EXTRA_BINDER)

    override fun writeBinder(reply: Bundle, binder: IBinder) {
        reply.putBinder(DELIVERY_EXTRA_BINDER, binder)
    }
}
