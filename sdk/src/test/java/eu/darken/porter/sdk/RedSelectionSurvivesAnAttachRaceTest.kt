package eu.darken.porter.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PermissionInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.shizuku.server.IShizukuApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

/**
 * A cross-process fetch seeds the selection with [Porter.adoptBackend] and then attaches outside
 * `Porter.lock`, so the published connection does not exist yet while the attach runs. A
 * [Porter.availability] that lands in that window resolves for itself and writes its answer,
 * because the guard that defers to a live connection only fires once one is published. The
 * connection that publishes afterwards must not be left answering for the other backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedSelectionSurvivesAnAttachRaceTest {

    /**
     * Stops the fetching thread inside the attach handshake and holds it there. The handshake is the
     * one part of a delivery that runs outside `Porter.lock`, so this is where a test can stand
     * while the session it is creating is not published yet.
     */
    private class GatedShizukuService : FakeShizukuService() {

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun attachApplication(application: IShizukuApplication, args: Bundle) {
            entered.countDown()
            try {
                release.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            super.attachApplication(application, args)
        }
    }

    /** Stands in for the provider process, which is connected on Shizuku and holds that binder. */
    private class ProviderProcess(private val held: IBinder) : ContentProvider() {

        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (DELIVERY_METHOD_GET_BINDER != method) return null
            val reply = Bundle()
            ShizukuProtocolDelivery.writeBinder(reply, held)
            return reply
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun declares(packageName: String, permission: String) {
        val info = PermissionInfo()
        info.name = permission
        info.packageName = packageName
        shadowOf(context.packageManager).addPermissionInfo(info)
    }

    @Test
    fun anAvailabilityCallDuringTheFetchAttachDoesNotMoveTheSelectionOffThePublishedConnection() = runBlocking<Unit> {
        // The provider process is connected on Shizuku. Porter was installed afterwards, so resolving
        // again now answers PORTER, which is what makes the two answers differ at all.
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION)
        declares(PorterProtocol.MANAGER_APPLICATION_ID, PorterProtocol.PERMISSION)

        val providerProcessBinder = GatedShizukuService()
        ShadowContentResolver.registerProviderInternal(
            context.packageName + ShizukuProtocolDelivery.authoritySuffix,
            ProviderProcess(providerProcessBinder),
        )

        // This process fetches the provider process's Shizuku binder and gets stuck in the handshake.
        val fetcher = Thread({ PorterApiProvider.fetchThrough(context, ShizukuProtocolDelivery) }, "fetch-binder")
        fetcher.start()
        assertTrue(
            "the fetch has to reach the attach handshake within the timeout",
            providerProcessBinder.entered.await(10, TimeUnit.SECONDS),
        )

        // What this process does whenever it is asked. Nothing is published yet, so this resolves for
        // itself rather than reading the connection, and records the answer it resolved.
        Porter.availability(context)

        providerProcessBinder.release.countDown()
        fetcher.join(TimeUnit.SECONDS.toMillis(10))
        assertFalse("the fetch has to finish within the timeout", fetcher.isAlive)
        ShadowLooper.shadowMainLooper().idle()

        val selection = Porter.selectBackend(context)
        val current = Porter.connection.value!!
        assertSame("the fetched Shizuku binder has to be the published connection", providerProcessBinder, current.binder)
        assertEquals("the published connection has to be the Shizuku one that was fetched", PorterBackend.SHIZUKU, current.backend)

        // What the disagreement then costs: the process refuses its own server's next delivery.
        val replacement = FakeShizukuService()
        Porter.onBinderReceived(context, replacement, PACKAGE, PorterBackend.SHIZUKU)

        assertEquals(
            "this process published a Shizuku connection but answers for Porter: the" +
                " availability call that ran while the fetched binder was still attaching" +
                " resolved PORTER and wrote it over the SHIZUKU that adoptBackend seeded," +
                " and publishing the connection never put the selection back",
            Porter.Selection.SHIZUKU, selection,
        )

        assertEquals(
            "a later Shizuku delivery from this process's own server was refused, because" +
                " the selection check compared it against a selection naming the other" +
                " backend; nothing can replace this connection until it dies",
            1, replacement.attachCount,
        )
        assertSame("the later Shizuku delivery never became the published connection", replacement, Porter.connection.value!!.binder)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
    }
}
