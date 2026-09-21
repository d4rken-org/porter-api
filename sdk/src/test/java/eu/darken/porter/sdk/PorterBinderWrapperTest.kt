package eu.darken.porter.sdk

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.TRANSACTION_transactRemote
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

    private companion object {
        const val TARGET_CODE = 7
        const val TARGET_FLAGS = 42
        const val PAYLOAD = 20816
    }
}
