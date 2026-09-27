package eu.darken.porter.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.Dispatchers

/**
 * A process that does not host the provider loses its Porter connection along with the provider
 * process, whose fetch after the death can run before the provider process has adopted its kept
 * Shizuku binder. What the adoption announces is then what brings this process to that binder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class AdoptionReachesOtherProcessesTest {

    /** Stands in for the provider process at one authority, answering whatever it holds right now. */
    private class ProviderProcess(private val delivery: PorterDelivery) : ContentProvider() {

        @Volatile
        var held: IBinder? = null

        /** Runs inside a lookup, before it answers. */
        @Volatile
        var duringLookup: () -> Unit = {}

        @Volatile
        var lookups = 0

        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method != DELIVERY_METHOD_GET_BINDER) return null
            lookups++
            val answer = held
            duringLookup()
            return answer?.let { binder -> Bundle().also { delivery.writeBinder(it, binder) } }
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    private lateinit var context: Context
    private val atPorter = ProviderProcess(PorterProtocolDelivery)
    private val atShizuku = ProviderProcess(ShizukuProtocolDelivery)
    private val porter = FakePorterService()
    private val shizuku = FakeShizukuService()

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
        declareShizukuProvider(context)
        // This process hosts no provider, so the SDK's built-in fetches are all it has; they run
        // inline, where the assertions can see them.
        Porter.deliveryExecutor = Executor { it.run() }
        // The test app declares the SDK's provider in its own process; this one is another.
        ShadowApplication.setProcessName(context.packageName + ":secondary")
        ShadowContentResolver.registerProviderInternal(context.packageName + PorterProtocolDelivery.authoritySuffix, atPorter)
        ShadowContentResolver.registerProviderInternal(context.packageName + ShizukuCompat.AUTHORITY_SUFFIX, atShizuku)

        atPorter.held = porter
        PorterApiProvider.requestBinderForNonProviderProcess(context)
        assertSame("the provider process's Porter connection is this one's", porter, Porter.connection.value?.binder)

        // The provider process lost its Porter connection too, and has not adopted anything yet.
        atPorter.held = null
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun anAnnouncementAfterTheFetchThatFoundNothingFetchesAgain() {
        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()
        assertNull("the fetch after the death ran before the provider process adopted anything", Porter.connection.value)
        assertEquals(1, atShizuku.lookups)

        // The provider process adopts its kept Shizuku binder and announces it.
        atShizuku.held = shizuku
        PorterApiProvider.announceBinder(context)
        ShadowLooper.shadowMainLooper().idle()

        assertSame(shizuku, Porter.connection.value?.binder)
        assertEquals("the announced binder has to be attached to exactly once", 1, shizuku.attachCount)
        assertEquals(2, atShizuku.lookups)
    }

    @Test
    fun anAnnouncementDuringTheFetchThatFindsNothingFetchesAgain() {
        // The provider process adopts while this process's fetch is on its way back.
        atShizuku.duringLookup = {
            atShizuku.duringLookup = {}
            atShizuku.held = shizuku
            PorterApiProvider.announceBinder(context)
        }

        deathRecipientOf(porter).binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertSame(shizuku, Porter.connection.value?.binder)
        assertEquals("the announced binder has to be attached to exactly once", 1, shizuku.attachCount)
        assertEquals("one fetch found nothing, the one after the announcement found the binder", 2, atShizuku.lookups)
    }
}
