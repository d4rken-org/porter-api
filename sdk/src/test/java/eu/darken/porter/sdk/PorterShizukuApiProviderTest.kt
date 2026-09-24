package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER
import moe.shizuku.api.BinderContainer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

/** The Shizuku envelope at the Shizuku authority, and the session a binder delivered there opens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterShizukuApiProviderTest {

    private lateinit var context: Context
    private lateinit var provider: PorterShizukuApiProvider

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
        provider = PorterShizukuApiProvider()

        val info = ProviderInfo()
        info.authority = context.packageName + AUTHORITY_SUFFIX
        info.exported = true
        info.multiprocess = false
        info.readPermission = SHELL_ONLY_PERMISSION
        info.writePermission = SHELL_ONLY_PERMISSION
        provider.attachInfo(context, info)
        // A server delivery is taken only on the selected backend, and nothing is installed here.
        Porter.selectBackendForTest(Porter.Selection.SHIZUKU)
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private val connected: Boolean get() = runBlocking { Porter.connection.value?.isAlive() } == true
    private val binder: IBinder? get() = Porter.connection.value?.binder

    /**
     * Sends the extras the way the server does. A Bundle that never leaves the process hands the
     * same container back, so the CREATOR and the class loader would decide nothing.
     */
    private fun throughAParcel(source: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(source)
            parcel.setDataPosition(0)
            return parcel.readBundle()!!
        } finally {
            parcel.recycle()
        }
    }

    private fun shizukuDelivery(binder: IBinder): Bundle {
        val extras = Bundle()
        extras.putParcelable(EXTRA_BINDER, BinderContainer(binder))
        return throughAParcel(extras)
    }

    @Test
    fun theEnvelopeIsTheOneShizukuSends() {
        val fake = FakePorterService()

        assertEquals(AUTHORITY_SUFFIX, ShizukuProtocolDelivery.authoritySuffix)
        assertSame(fake, ShizukuProtocolDelivery.readBinder(shizukuDelivery(fake)))
    }

    @Test
    fun theWrittenEnvelopeReadsBackAsAContainer() {
        val fake = FakePorterService()

        val reply = Bundle()
        ShizukuProtocolDelivery.writeBinder(reply, fake)
        val sent = throughAParcel(reply)
        sent.classLoader = BinderContainer::class.java.classLoader

        @Suppress("DEPRECATION")
        val container = sent.getParcelable<BinderContainer>(EXTRA_BINDER)
        assertSame(fake, container!!.binder)
    }

    @Test
    fun aBinderDeliveredOnTheShizukuAuthorityAttaches() {
        val fake = FakeShizukuService()
        fake.bindApplicationReply = FakeShizukuService.replyWithVersion(14)

        provider.call(DELIVERY_METHOD_SEND_BINDER, null, shizukuDelivery(fake))

        assertTrue(connected)
        val connection = Porter.connection.value
        assertNotNull(connection)
        assertEquals(PorterBackend.SHIZUKU, connection!!.backend)
        val info = connection.serverInfo
        assertEquals(PorterBackend.SHIZUKU, info.backend)
        assertEquals(14, info.version)
    }

    /** ContentProvider.requireContext() is API 30; this release has only getContext(). */
    @Test
    @Config(sdk = [24])
    fun aBinderDeliveredOnTheOldestSupportedReleaseAttaches() {
        val fake = FakeShizukuService()
        fake.bindApplicationReply = FakeShizukuService.replyWithVersion(14)

        provider.call(DELIVERY_METHOD_SEND_BINDER, null, shizukuDelivery(fake))

        assertTrue(connected)
        assertEquals(PorterBackend.SHIZUKU, Porter.connection.value!!.backend)
    }

    /**
     * The Porter stub rejects the Shizuku interface token at attach, and the session is abandoned on
     * what that throws. The failure is immediate only because the parcel enforces the token; without
     * that enforcement this would sit on the attach timeout instead.
     */
    @Test
    fun aBinderThatDoesNotSpeakShizukuIsNotPublished() {
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, shizukuDelivery(FakePorterService()))

        assertFalse(connected)
        assertNull(binder)
        assertNull(Porter.connection.value)
    }

    @Test
    fun portersOwnEnvelopeIsIgnoredOnTheShizukuAuthority() {
        val extras = Bundle()
        extras.putBinder(DELIVERY_EXTRA_BINDER, FakePorterService())

        provider.call(DELIVERY_METHOD_SEND_BINDER, null, extras)

        assertFalse(connected)
        assertNull(binder)
    }

    @Test
    fun getBinderRefusesWhileTheLiveSessionIsPorters() {
        Porter.onBinderReceived(FakePorterService(), context.packageName)
        assertTrue(connected)

        assertNull(provider.call(DELIVERY_METHOD_GET_BINDER, null, Bundle()))
    }

    private companion object {
        const val AUTHORITY_SUFFIX = ".shizuku"
        const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    }
}
