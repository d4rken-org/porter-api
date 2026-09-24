package eu.darken.porter.sdk

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER
import eu.darken.porter.protocol.PorterProtocol.PROVIDER_AUTHORITY_SUFFIX
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.Dispatchers

/** How a process that does not host the provider obtains the connection, however often it asks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterMultiProcessTest {

    private lateinit var context: Application

    /** Counts what the provider process is asked, and answers with the binder it holds. */
    private class CountingProviderProcess(private val held: IBinder) : ContentProvider() {

        var lookups = 0

        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method != DELIVERY_METHOD_GET_BINDER) return null
            lookups++
            return Bundle().also { PorterProtocolDelivery.writeBinder(it, held) }
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null

        override fun getType(uri: Uri): String? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    }

    private val held = FakePorterService()
    private val providerProcess = CountingProviderProcess(held)

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
        Porter.deliveryExecutor = Executor { it.run() }
        // The test app declares the SDK's provider in its own process; this one is another.
        ShadowApplication.setProcessName(context.packageName + ":secondary")
        Porter.selectBackendForTest(Porter.Selection.PORTER)
        ShadowContentResolver.registerProviderInternal(context.packageName + PROVIDER_AUTHORITY_SUFFIX, providerProcess)
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun receivers(): Int =
        shadowOf(context).registeredReceivers.count { it.intentFilter.hasAction(PorterApiProvider.ACTION_BINDER_RECEIVED) }

    @Test
    fun askingRepeatedlyRegistersOnceAndAsksEachTime() {
        PorterApiProvider.requestBinderForNonProviderProcess(context)
        PorterApiProvider.requestBinderForNonProviderProcess(context)

        assertEquals("every request asks the provider process", 2, providerProcess.lookups)
        assertEquals("one receiver however often the app asks", 1, receivers())
        assertSame(held, Porter.connection.value!!.binder)

        val recipients = (Shadow.extract(held) as ShadowBinder).deathRecipients
        recipients.single().binderDied()
        ShadowLooper.shadowMainLooper().idle()

        assertEquals("one death is one fetch, not one per request", 3, providerProcess.lookups)
    }

    @Test
    fun theProcessAProviderAttachedInAsksNothing() {
        val provider = PorterApiProvider()
        provider.attachInfo(context, providerInfo(context.packageName + PROVIDER_AUTHORITY_SUFFIX))

        PorterApiProvider.requestBinderForNonProviderProcess(context)

        assertEquals(0, providerProcess.lookups)
        assertEquals(0, receivers())
    }

    /** Before the provider attaches, the process it is declared to run in is recognised by name. */
    @Test
    fun theDeclaredProviderProcessIsRecognisedBeforeItsProviderAttaches() {
        val info = providerInfo(context.packageName + PROVIDER_AUTHORITY_SUFFIX)
        info.name = PorterApiProvider::class.java.name
        info.packageName = context.packageName
        info.processName = "${context.packageName}:porter"
        shadowOf(context.packageManager).addOrUpdateProvider(info)

        ShadowApplication.setProcessName("${context.packageName}:porter")
        PorterApiProvider.requestBinderForNonProviderProcess(context)
        assertEquals("the provider process asked itself", 0, providerProcess.lookups)

        ShadowApplication.setProcessName(context.packageName)
        PorterApiProvider.requestBinderForNonProviderProcess(context)
        assertEquals("another process of the app did not ask", 1, providerProcess.lookups)
    }

    @Test
    fun theProviderAnnouncesOnlyAConnectionItPublished() {
        val provider = PorterApiProvider()
        provider.attachInfo(context, providerInfo(context.packageName + PROVIDER_AUTHORITY_SUFFIX))
        val broadcasts = shadowOf(context)

        val refused = FakePorterService().apply { protocolVersion = 1 }
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, Bundle().apply { putBinder(DELIVERY_EXTRA_BINDER, refused) })
        assertNull(Porter.connection.value)
        assertEquals("a refused binder is not announced", 0, broadcasts.broadcastIntents.size)

        val accepted = FakePorterService()
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, Bundle().apply { putBinder(DELIVERY_EXTRA_BINDER, accepted) })
        assertSame(accepted, Porter.connection.value!!.binder)
        assertEquals(1, broadcasts.broadcastIntents.size)
        assertEquals(PorterApiProvider.ACTION_BINDER_RECEIVED, broadcasts.broadcastIntents.single().action)
        assertEquals(context.packageName, broadcasts.broadcastIntents.single().`package`)
    }

    private fun providerInfo(authority: String): ProviderInfo = ProviderInfo().apply {
        this.authority = authority
        exported = true
        multiprocess = false
        readPermission = SHELL_ONLY_PERMISSION
        writePermission = SHELL_ONLY_PERMISSION
    }
}
