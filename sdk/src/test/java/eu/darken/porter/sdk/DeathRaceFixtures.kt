package eu.darken.porter.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import eu.darken.porter.server.IPorterApplication
import java.util.Collections
import java.util.NoSuchElementException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder

/**
 * Stands in for the provider process at Porter's authority. A real one answers out of its own
 * connection, which is not this process's state, so it cannot be the SDK's own provider here: that
 * one would answer out of the very connection a test is driving.
 */
internal class ProviderProcessStandIn(private val held: IBinder) : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != DELIVERY_METHOD_GET_BINDER) return null
        return Bundle().also { PorterProtocolDelivery.writeBinder(it, held) }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

/**
 * Death links with the bookkeeping of a binder that lives in another process:
 * `android.os.BinderProxy.unlinkToDeath` throws [NoSuchElementException] when the recipient is not
 * linked. A local `android.os.Binder`, which is what a plain [FakePorterService] is, ignores
 * `unlinkToDeath` altogether, so a duplicate unlink leaves no trace there.
 */
internal open class RemoteLikeService : FakePorterService() {

    private val linked = ArrayList<IBinder.DeathRecipient>()

    /** Every unlink, the ones that threw included. */
    var unlinks = 0

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        synchronized(linked) {
            linked.add(recipient)
        }
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
        synchronized(linked) {
            unlinks++
            if (!linked.remove(recipient)) throw NoSuchElementException("Death link does not exist")
        }
        return true
    }
}

/** Holds its attach reply in the server until the test lets it answer. */
internal class BlockingService : FakePorterService() {

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
        val reply = super.attach(application, args)
        entered.countDown()
        release.await()
        return reply
    }
}

/** As [BlockingService], with the death-link bookkeeping of a binder in another process. */
internal class BlockingRemoteLikeService : RemoteLikeService() {

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
        val reply = super.attach(application, args)
        entered.countDown()
        release.await()
        return reply
    }
}

/** Reaches the server, waits for the test, and then refuses the client. */
internal class BlockingRefusingService : FakePorterService() {

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)

    override fun attach(application: IPorterApplication, args: Bundle): Bundle? {
        super.attach(application, args)
        entered.countDown()
        release.await()
        throw SecurityException("not an attached client")
    }
}

internal fun deathRecipientOf(binder: IBinder): IBinder.DeathRecipient {
    val shadow: ShadowBinder = Shadow.extract(binder)
    val recipients = shadow.deathRecipients
    assertEquals("one connection links one recipient", 1, recipients.size)
    return recipients[0]
}

/**
 * Collects [Porter.connection] inline on whichever thread publishes, so what a test reads back is
 * the order the SDK published in, with no dispatcher of the test's own in between.
 */
internal class ConnectionObserver {

    val failures: MutableList<Throwable> = Collections.synchronizedList(ArrayList())

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure -> failures.add(failure) },
    )

    /** The binders published since this call, the one published at the time of the call first. */
    fun observe(onEach: (PorterConnection?) -> Unit = {}): List<IBinder?> {
        val seen = Collections.synchronizedList(ArrayList<IBinder?>())
        scope.launch {
            Porter.connection.collect {
                seen.add(it?.binder)
                onEach(it)
            }
        }
        return seen
    }

    fun close() {
        scope.cancel()
    }
}
