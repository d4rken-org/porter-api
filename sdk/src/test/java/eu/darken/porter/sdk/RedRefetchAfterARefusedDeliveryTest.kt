package eu.darken.porter.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PermissionInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper

/**
 * A process that does not host the provider refuses a delivery on the other backend while it has a
 * live connection, and then loses that connection. Neither fetch that runs on its own reaches the
 * binder the provider process still holds: the one
 * [PorterApiProvider.requestBinderForNonProviderProcess] makes at startup has already happened, and
 * the provider announces nothing here because it received its binder before this process's
 * connection died. The death of the refused connection is what sends this process back to the
 * provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class RedRefetchAfterARefusedDeliveryTest {

    private lateinit var context: Context

    /** The binder the provider process attached, which this process can only reach by fetching. */
    private val providerProcessBinder = FakePorterService()

    private val recorder = CoroutineScope(Dispatchers.Unconfined)

    /**
     * Stands in for the provider process at Porter's authority. A real one answers out of its own
     * session, which is not this process's static state, so it cannot be the SDK's own provider
     * here: that one would answer out of the very session this test is driving.
     */
    private class ProviderProcess(private val held: IBinder) : ContentProvider() {

        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (DELIVERY_METHOD_GET_BINDER != method) return null
            val reply = Bundle()
            PorterProtocolDelivery.writeBinder(reply, held)
            return reply
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        // This process hosts no provider, so the SDK's built-in fetches are all it has.
        PorterApiProvider.enableMultiProcessSupport(false)
        ShadowContentResolver.registerProviderInternal(
            context.packageName + PorterProtocolDelivery.authoritySuffix,
            ProviderProcess(providerProcessBinder),
        )
    }

    @After
    fun teardown() {
        recorder.cancel()
        Porter.resetForTest()
    }

    private fun declares(packageName: String, permission: String) {
        val info = PermissionInfo()
        info.name = permission
        info.packageName = packageName
        shadowOf(context.packageManager).addPermissionInfo(info)
    }

    @Test
    fun aRefusedDeliveryIsFetchedAgainOnceTheConnectionThatRefusedItDies() {
        declares(ShizukuProtocol.MANAGER_APPLICATION_ID, ShizukuProtocol.PERMISSION)
        assertEquals(
            "the only installed manager is Shizuku's, so that is what this process selects",
            Porter.Selection.SHIZUKU, Porter.selectBackend(context),
        )

        // The connection this process is on, as an earlier fetch from the provider left it.
        val shizuku = FakeShizukuService()
        Porter.onBinderReceived(context, shizuku, context.packageName, PorterBackend.SHIZUKU)
        assertSame(
            "the Shizuku connection has to be published before the refused fetch runs",
            shizuku, Porter.connection.value!!.binder,
        )

        var deaths = 0
        recorder.launch { Porter.connection.collect { if (it == null) deaths++ } }

        // Everything this process does about binder delivery, as the SDK documents it.
        PorterApiProvider.requestBinderForNonProviderProcess(context)

        assertSame(
            "the provider process's Porter binder replaced a live Shizuku connection, which" +
                " the session lock is supposed to refuse",
            shizuku, Porter.connection.value!!.binder,
        )
        assertEquals("a refused delivery must not be attached to", 0, providerProcessBinder.attachCount)

        deathRecipientOf(shizuku).binderDied()

        assertEquals(
            "the death of the published Shizuku connection has to be announced before" +
                " anything can react to it",
            1, deaths,
        )

        ShadowLooper.shadowMainLooper().idle()

        assertSame(
            "the Shizuku connection died and the provider process still holds a Porter binder" +
                " that answers, but no built-in fetch runs after a death, so this process" +
                " stays disconnected for as long as it lives",
            providerProcessBinder, Porter.connection.value!!.binder,
        )
        assertEquals("the binder fetched after the death has to be attached to once", 1, providerProcessBinder.attachCount)
    }

    private companion object {
        fun deathRecipientOf(binder: IBinder): IBinder.DeathRecipient {
            val shadow: ShadowBinder = Shadow.extract(binder)
            val recipients = shadow.deathRecipients
            assertEquals("one connection links one recipient", 1, recipients.size)
            return recipients[0]
        }
    }
}
