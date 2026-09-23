package eu.darken.porter.sdk

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_EXTRA_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_GET_BINDER
import eu.darken.porter.protocol.PorterProtocol.DELIVERY_METHOD_SEND_BINDER
import eu.darken.porter.protocol.PorterProtocol.PROVIDER_AUTHORITY_SUFFIX
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowContentResolver
import kotlinx.coroutines.Dispatchers

/** The receiving half of binder delivery: what the provider hands to [Porter] and back out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterApiProviderTest {

    private lateinit var context: Context
    private lateinit var provider: PorterApiProvider

    @Before
    fun setup() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
        context = RuntimeEnvironment.getApplication()
        provider = attached(PorterApiProvider(), exported = true, multiprocess = false)
        // A server delivery is taken only on the selected backend, and nothing is installed here.
        Porter.selectBackendForTest(Porter.Selection.PORTER)
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun attached(provider: PorterApiProvider, exported: Boolean, multiprocess: Boolean): PorterApiProvider {
        val info = ProviderInfo()
        info.authority = context.packageName + PROVIDER_AUTHORITY_SUFFIX
        info.exported = exported
        info.multiprocess = multiprocess
        provider.attachInfo(context, info)
        return provider
    }

    private fun delivery(binder: IBinder): Bundle = Bundle().apply { putBinder(DELIVERY_EXTRA_BINDER, binder) }

    private fun shizukuDelivery(binder: IBinder): Bundle = Bundle().apply { ShizukuProtocolDelivery.writeBinder(this, binder) }

    private val connected: Boolean get() = runBlocking { Porter.connection.value?.isAlive() } == true
    private val binder: IBinder? get() = Porter.connection.value?.binder
    private val backend: PorterBackend? get() = Porter.connection.value?.backend

    /**
     * Both authorities answerable, as an app declaring the Shizuku provider alongside the built-in
     * one has them. A secondary process reaches whichever of the two the server delivered to.
     */
    private fun bothAuthorities(): PorterShizukuApiProvider {
        declareShizukuProvider(context)
        ShadowContentResolver.registerProviderInternal(context.packageName + PROVIDER_AUTHORITY_SUFFIX, provider)

        val shizuku = PorterShizukuApiProvider()
        val info = ProviderInfo()
        info.authority = context.packageName + ShizukuProtocolDelivery.authoritySuffix
        info.exported = true
        info.multiprocess = false
        shizuku.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(info.authority, shizuku)
        return shizuku
    }

    @Test
    fun sendBinderDeliversToPorter() {
        val fake = FakePorterService()

        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake))

        assertTrue(connected)
        assertSame(fake, binder)
        assertEquals(1, fake.attachCount)
    }

    /** ContentProvider.requireContext() is API 30; this release has only getContext(). */
    @Test
    @Config(sdk = [24])
    fun sendBinderDeliversOnTheOldestSupportedRelease() {
        val fake = FakePorterService()

        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake))

        assertTrue(connected)
        assertSame(fake, binder)
    }

    @Test
    fun aSecondSendBinderIsIgnoredWhileTheFirstIsAlive() {
        val fake = FakePorterService()
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake))

        val reply = provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(FakePorterService()))

        assertNotNull(reply)
        assertTrue(reply!!.isEmpty)
        assertSame(fake, binder)
        assertEquals(1, fake.attachCount)
    }

    @Test
    fun sendBinderWithoutTheExtraDeliversNothing() {
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, Bundle())

        assertFalse(connected)
    }

    @Test
    fun getBinderAnswersOnlyWhileABinderIsHeld() {
        assertNull(provider.call(DELIVERY_METHOD_GET_BINDER, null, Bundle()))

        val fake = FakePorterService()
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake))

        val reply = provider.call(DELIVERY_METHOD_GET_BINDER, null, Bundle())

        assertNotNull(reply)
        assertSame(fake, reply!!.getBinder(DELIVERY_EXTRA_BINDER))
    }

    @Test
    fun sendBinderTagsTheSessionWithPortersBackend() {
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(FakePorterService()))

        assertEquals(PorterBackend.PORTER, backend)
    }

    @Test
    fun theProviderDeclarationIsEnforced() {
        assertThrows(IllegalStateException::class.java) { attached(PorterApiProvider(), exported = true, multiprocess = true) }
        assertThrows(IllegalStateException::class.java) { attached(PorterApiProvider(), exported = false, multiprocess = false) }
    }

    @Test
    fun aSecondaryProcessFindsASessionOnTheShizukuAuthority() {
        Porter.selectBackendForTest(Porter.Selection.SHIZUKU)
        val shizuku = bothAuthorities()
        val fake = FakeShizukuService()
        shizuku.call(DELIVERY_METHOD_SEND_BINDER, null, shizukuDelivery(fake))

        assertTrue(PorterApiProvider.fetchBinderFromProvider(context))

        assertSame(fake, binder)
        assertEquals(PorterBackend.SHIZUKU, backend)
    }

    /** Upstream's provider at that authority answers in its own terms, so it is not asked. */
    @Test
    fun aSecondaryProcessDoesNotAskAnotherProviderAtTheShizukuAuthority() {
        Porter.selectBackendForTest(Porter.Selection.SHIZUKU)
        val shizuku = bothAuthorities()
        declareShizukuProvider(context, "rikka.shizuku.ShizukuProvider")
        shizuku.call(DELIVERY_METHOD_SEND_BINDER, null, shizukuDelivery(FakeShizukuService()))

        assertFalse(PorterApiProvider.fetchBinderFromProvider(context))
    }

    @Test
    fun aSecondaryProcessStillFindsASessionOnPortersAuthority() {
        bothAuthorities()
        val fake = FakePorterService()
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(fake))

        assertTrue(PorterApiProvider.fetchBinderFromProvider(context))

        assertSame(fake, binder)
        assertEquals(PorterBackend.PORTER, backend)
    }

    /**
     * The liveness check runs against a binder the session already resolved. A replacement that
     * lands while that check is in flight must not become the answer.
     */
    @Test
    fun aReplacementLandingDuringTheLivenessCheckIsNotTheAnswer() {
        class ReplacingPing : FakePorterService() {
            var replacement: FakePorterService? = null

            override fun pingBinder(): Boolean {
                val next = replacement
                if (next != null) {
                    replacement = null
                    Porter.onBinderReceived(next, context.packageName)
                }
                return true
            }
        }

        val first = ReplacingPing()
        provider.call(DELIVERY_METHOD_SEND_BINDER, null, delivery(first))
        first.replacement = FakePorterService()

        val reply = provider.call(DELIVERY_METHOD_GET_BINDER, null, Bundle())

        assertNotNull(reply)
        assertSame(first, reply!!.getBinder(DELIVERY_EXTRA_BINDER))
    }
}
