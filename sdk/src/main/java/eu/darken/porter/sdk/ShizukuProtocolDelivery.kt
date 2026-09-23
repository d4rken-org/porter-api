package eu.darken.porter.sdk

import android.os.Bundle
import android.os.IBinder
import moe.shizuku.api.BinderContainer

/** Shizuku's envelope: the binder travels inside a [BinderContainer] Parcelable. */
internal object ShizukuProtocolDelivery : PorterDelivery {

    private const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"

    override val authoritySuffix: String = ShizukuCompat.AUTHORITY_SUFFIX

    override val backend: PorterBackend = PorterBackend.SHIZUKU

    override fun readBinder(extras: Bundle): IBinder? {
        // The extras arrive from another process, so the container is still parcelled and nothing on
        // the framework class loader can name it.
        extras.classLoader = BinderContainer::class.java.classLoader
        @Suppress("DEPRECATION")
        val container = extras.getParcelable<BinderContainer>(EXTRA_BINDER)
        return container?.binder
    }

    override fun writeBinder(reply: Bundle, binder: IBinder) {
        reply.putParcelable(EXTRA_BINDER, BinderContainer(binder))
    }
}
