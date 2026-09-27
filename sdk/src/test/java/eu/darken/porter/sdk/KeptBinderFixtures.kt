package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER
import java.util.concurrent.CountDownLatch
import moe.shizuku.api.BinderContainer
import moe.shizuku.server.IShizukuApplication
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Both servers' pushes into the process that hosts the providers, through the providers the SDK
 * gives an app, so what a push meets is what it meets on a device.
 */
internal class ServerPushes(private val context: Context) {

    private val porterProvider = PorterApiProvider()
    private val shizukuProvider = PorterShizukuApiProvider()

    init {
        declareShizukuProvider(context)
        porterProvider.attachInfo(context, providerInfo(PorterProtocol.PROVIDER_AUTHORITY_SUFFIX))
        shizukuProvider.attachInfo(context, providerInfo(ShizukuCompat.AUTHORITY_SUFFIX))
    }

    private fun providerInfo(suffix: String) = ProviderInfo().apply {
        authority = context.packageName + suffix
        exported = true
        multiprocess = false
        readPermission = SHELL_ONLY_PERMISSION
        writePermission = SHELL_ONLY_PERMISSION
    }

    fun porter(binder: IBinder) {
        porterProvider.call(DELIVERY_METHOD_SEND_BINDER, null, Bundle().apply { putBinder(DELIVERY_EXTRA_BINDER, binder) })
    }

    /** Through a parcel, as the server sends it, so the container is read the way it is on a device. */
    fun shizuku(binder: IBinder) {
        val extras = Bundle()
        extras.putParcelable(SHIZUKU_EXTRA_BINDER, BinderContainer(binder))
        val parcel = Parcel.obtain()
        val sent = try {
            parcel.writeBundle(extras)
            parcel.setDataPosition(0)
            parcel.readBundle()!!
        } finally {
            parcel.recycle()
        }
        shizukuProvider.call(DELIVERY_METHOD_SEND_BINDER, null, sent)
    }

    private companion object {
        const val SHIZUKU_EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    }
}

/**
 * Installs a manager as a package declaring [permission]. A permission added on its own outlives
 * [uninstallManager], and uninstalling is what these cases are about.
 */
internal fun installManager(context: Context, packageName: String, permission: String) {
    shadowOf(context.packageManager).installPackage(
        PackageInfo().apply {
            this.packageName = packageName
            permissions = arrayOf(
                PermissionInfo().apply {
                    name = permission
                    this.packageName = packageName
                },
            )
        },
    )
}

internal fun installPorter(context: Context) = installManager(context, PorterProtocol.MANAGER_APPLICATION_ID, PorterProtocol.PERMISSION)

internal fun installShizuku(context: Context) = installManager(context, ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION)

internal fun uninstallManager(context: Context, packageName: String) {
    shadowOf(context.packageManager).removePackage(packageName)
}

/** How often the provider process told the app's other processes about a connection. */
internal fun announcements(): Int =
    shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.count { it.action == PorterApiProvider.ACTION_BINDER_RECEIVED }

/** A Porter server whose binder can stop answering before its death is delivered. */
internal class StoppablePorterService : FakePorterService() {

    @Volatile
    var answering = true

    override fun pingBinder(): Boolean = answering
}

/** A Shizuku server whose binder can stop answering before its death is delivered. */
internal class StoppableShizukuService : FakeShizukuService() {

    @Volatile
    var answering = true

    override fun pingBinder(): Boolean = answering
}

/** Holds its attach in the server, after the reply was sent, until the test lets it return. */
internal class BlockingShizukuService : FakeShizukuService() {

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun attachApplication(application: IShizukuApplication, args: Bundle) {
        super.attachApplication(application, args)
        entered.countDown()
        release.await()
    }
}

/** Runs [check] inside the attach, before answering it. */
internal class ObservedAttachShizukuService(private val check: () -> Unit) : FakeShizukuService() {

    override fun attachApplication(application: IShizukuApplication, args: Bundle) {
        check()
        super.attachApplication(application, args)
    }
}
