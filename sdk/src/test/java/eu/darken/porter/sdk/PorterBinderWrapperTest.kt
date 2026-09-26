package eu.darken.porter.sdk

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.TRANSACTION_transactRemote
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/** The parcel a wrapped binder writes for the server to forward. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterBinderWrapperTest {

    /** Reads the forwarding parcel the way `Service#transactRemote` reads it. */
    private class CapturingService : FakePorterService() {

        var forwardedTarget: IBinder? = null
        var forwardedCode = -1
        var forwardedFlags = -1
        var forwardedPayload = -1

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != TRANSACTION_transactRemote) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(PorterProtocol.DESCRIPTOR)
            forwardedTarget = data.readStrongBinder()
            forwardedCode = data.readInt()
            forwardedFlags = data.readInt()
            forwardedPayload = data.readInt()
            return true
        }
    }

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    @Test
    fun theWrappedBinderCodeAndFlagsFollowTheToken() {
        val fake = CapturingService()
        Porter.onBinderReceived(fake, "eu.darken.porter.probe")
        val connection = Porter.connection.value!!
        val original: IBinder = Binder()

        val data = Parcel.obtain()
        try {
            data.writeInt(PAYLOAD)
            assertTrue(connection.wrap(original).transact(TARGET_CODE, data, null, TARGET_FLAGS))
        } finally {
            data.recycle()
        }

        assertSame(original, fake.forwardedTarget)
        assertEquals(TARGET_CODE, fake.forwardedCode)
        assertEquals(TARGET_FLAGS, fake.forwardedFlags)
        assertEquals(PAYLOAD, fake.forwardedPayload)
    }

    @Test
    fun whatTheForwardingTransactThrowsReachesTheWrappedProxyAsItIs() {
        val thrown = IllegalArgumentException("bad value")
        val fake = FakePorterService()
        // Reached through the generated proxy, so the failure is the transport's own, not a reply's.
        val transport = object : IBinder by fake {
            override fun queryLocalInterface(descriptor: String): IInterface? = null

            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == TRANSACTION_transactRemote) throw thrown
                return fake.transact(code, data, reply, flags)
            }
        }
        Porter.onBinderReceived(transport, "eu.darken.porter.probe")

        val data = Parcel.obtain()
        try {
            val failure = runCatching {
                Porter.connection.value!!.wrap(Binder()).transact(TARGET_CODE, data, null, 0)
            }.exceptionOrNull()

            assertSame(thrown, failure)
        } finally {
            data.recycle()
        }
    }

    private companion object {
        const val TARGET_CODE = 7
        const val TARGET_FLAGS = 42
        const val PAYLOAD = 20816
    }
}
