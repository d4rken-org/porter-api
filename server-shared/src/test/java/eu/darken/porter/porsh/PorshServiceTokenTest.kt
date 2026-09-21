package eu.darken.porter.porsh

import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuApiConstants

/**
 * Two endpoints run one shell service each. A transaction is answered by the instance whose code
 * range and interface token it was written for, and by no other.
 *
 * Lives in `server-shared` because `porsh`'s own unit tests run with `returnDefaultValues` and
 * cannot build a [Parcel]. The service runs on its real host registry: this module cannot reach
 * `porsh`'s internal one, and a registry that knows no host answers an exit-code query with -1,
 * which is what tells an answered transaction from an ignored one here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorshServiceTokenTest {

    private fun service(interfaceToken: String, transactionCodeStart: Int): PorshService =
        object : PorshService(interfaceToken, transactionCodeStart) {

            override fun enforceCallingPermission(func: String) {
            }
        }

    @Test
    fun aCodeIsAnsweredOnlyByTheInstanceThatOwnsIt() {
        val porter = service(PORTER_TOKEN, PORTER_BASE)
        val legacy = service(LEGACY_TOKEN, LEGACY_BASE)

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PORTER_TOKEN)

            data.setDataPosition(0)
            assertTrue(porter.onTransact(PORTER_BASE + TRANSACTION_getExitCode, data, reply, 0))
            reply.setDataPosition(0)
            reply.readException()
            assertEquals("no host for this caller", -1, reply.readInt())

            data.setDataPosition(0)
            val untouched = Parcel.obtain()
            try {
                assertFalse(legacy.onTransact(PORTER_BASE + TRANSACTION_getExitCode, data, untouched, 0))
                assertEquals("nothing was written for a code the instance does not own", 0, untouched.dataSize())
            } finally {
                untouched.recycle()
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun theOwnedCodeUnderAnotherTokenIsRefused() {
        val porter = service(PORTER_TOKEN, PORTER_BASE)

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(LEGACY_TOKEN)
            data.setDataPosition(0)

            assertThrows(SecurityException::class.java) {
                porter.onTransact(PORTER_BASE + TRANSACTION_getExitCode, data, reply, 0)
            }

            assertEquals("refused before anything was answered", 0, reply.dataSize())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val PORTER_TOKEN = PorterProtocol.DESCRIPTOR
        const val LEGACY_TOKEN = ShizukuApiConstants.BINDER_DESCRIPTOR
        const val PORTER_BASE = PorterProtocol.TRANSACTION_PORSH_BASE
        const val LEGACY_BASE = 30000

        /** `PorshConfig.TRANSACTION_getExitCode`, which is internal to `porsh`. */
        const val TRANSACTION_getExitCode = 2
    }
}
