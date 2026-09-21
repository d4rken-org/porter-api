package eu.darken.porter.sdk

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_COMPONENT
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_NO_CREATE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_REMOVE
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TAG
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Shizuku wire puts on the wire for a user service, read back by the server's stub. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class ShizukuProtocolWireUserServiceTest {

    private val callbacks = object : PorterWire.Callbacks {

        override fun onRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        }

        override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
        }
    }

    /** One user service binding as the SDK holds it, without the registry plumbing. */
    private class RecordingCallback : UserServiceCallback {

        private val registered = EnumMap<PorterBackend, IBinder>(PorterBackend::class.java)

        var connectedWith: IBinder? = null
        var connects = 0
        var deaths = 0

        override fun connected(binder: IBinder) {
            connects++
            connectedWith = binder
        }

        override fun died() {
            deaths++
        }

        override fun registeredBinder(backend: PorterBackend): IBinder? = registered[backend]

        override fun rememberRegisteredBinder(backend: PorterBackend, binder: IBinder) {
            registered[backend] = binder
        }
    }

    /**
     * Holds its first lookup until the other thread reaches one too, so a wire that looks up and
     * registers without a lock creates two binders for the one callback.
     */
    private class GatedCallback : UserServiceCallback {

        private val registered = ConcurrentHashMap<PorterBackend, IBinder>()
        private val lookups = CountDownLatch(2)

        override fun registeredBinder(backend: PorterBackend): IBinder? {
            lookups.countDown()
            try {
                lookups.await(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return registered[backend]
        }

        override fun rememberRegisteredBinder(backend: PorterBackend, binder: IBinder) {
            registered[backend] = binder
        }

        override fun connected(binder: IBinder) {
        }

        override fun died() {
        }
    }

    private fun args(tag: String): UserServiceArgs =
        UserServiceArgs(COMPONENT, processNameSuffix = PROCESS_SUFFIX, tag = tag)

    /** A wire that has completed the handshake against a server reporting this version. */
    private fun attached(fake: FakeShizukuService, version: Int, patch: Int): ShizukuProtocolWire {
        val state = Bundle()
        state.putInt(BIND_APPLICATION_SERVER_VERSION, version)
        state.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, patch)
        fake.bindApplicationReply = state

        val wire = ShizukuProtocolWire(fake, callbacks)
        wire.attach(PACKAGE)
        return wire
    }

    @Test
    fun anAddCarriesTheEncodedArgumentsAndTheRegisteredBinder() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION)
        fake.addUserServiceResult = 7
        val callback = RecordingCallback()

        assertEquals(7, wire.addUserService(callback, args("added"), false))

        assertEquals(1, fake.userServiceAdds.size)
        val call = fake.userServiceAdds[0]
        @Suppress("DEPRECATION")
        assertEquals(COMPONENT, call.args.getParcelable<ComponentName>(USER_SERVICE_ARG_COMPONENT))
        assertEquals("added", call.args.getString(USER_SERVICE_ARG_TAG))
        assertNotNull(call.connection)
        assertSame(callback.registeredBinder(PorterBackend.SHIZUKU), call.connection)
    }

    @Test
    fun aPeekAsksForNoCreationAndABindDoesNot() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION)

        wire.addUserService(RecordingCallback(), args("peeked"), true)
        wire.addUserService(RecordingCallback(), args("bound"), false)

        assertTrue(fake.userServiceAdds[0].args.getBoolean(USER_SERVICE_ARG_NO_CREATE))
        assertFalse(fake.userServiceAdds[1].args.containsKey(USER_SERVICE_ARG_NO_CREATE))
    }

    @Test
    fun aRemovalCarriesTheBinderTheAddRegistered() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION)
        val callback = RecordingCallback()

        wire.addUserService(callback, args("one-binding"), false)
        wire.removeUserService(callback, args("one-binding"), false)

        assertEquals(1, fake.userServiceRemoves.size)
        assertSame(fake.userServiceAdds[0].connection, fake.userServiceRemoves[0].connection)
    }

    @Test
    fun killingNamesNoConnection() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION)

        wire.removeUserService(null, args("killed"), true)

        assertEquals(1, fake.userServiceRemoves.size)
        assertNull(fake.userServiceRemoves[0].connection)
        assertTrue(fake.userServiceRemoves[0].args.getBoolean(USER_SERVICE_ARG_REMOVE))
    }

    @Test
    fun theServerPushesConnectedAndDiedToTheCallback() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION)
        val callback = RecordingCallback()
        val service: IBinder = Binder()

        wire.addUserService(callback, args("pushed-to"), false)
        fake.pushUserServiceConnected(service)
        fake.pushUserServiceDied()

        assertEquals(1, callback.connects)
        assertSame(service, callback.connectedWith)
        assertEquals(1, callback.deaths)
    }

    @Test
    fun twoThreadsBindingOneServiceRegisterOneBinder() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION)
        val callback = GatedCallback()
        val args = args("concurrent-binding")

        val bind = Runnable { wire.addUserService(callback, args, false) }
        val first = Thread(bind)
        val second = Thread(bind)
        first.start()
        second.start()
        first.join()
        second.join()

        assertEquals(2, fake.userServiceAdds.size)
        assertNotNull(fake.userServiceAdds[0].connection)
        assertSame(fake.userServiceAdds[0].connection, fake.userServiceAdds[1].connection)
    }

    @Test
    fun aServerBelowThePatchGateIsNotAskedToDropAConnection() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION - 1)

        assertEquals(0, wire.removeUserService(RecordingCallback(), args("kept"), false))

        assertTrue(fake.userServiceRemoves.isEmpty())
        assertFalse(fake.codes.contains(ShizukuProtocol.TRANSACTION_removeUserService))
    }

    @Test
    fun aServerAtThePatchGateIsAskedToDropAConnection() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION)
        fake.removeUserServiceResult = 3

        assertEquals(3, wire.removeUserService(RecordingCallback(), args("dropped"), false))

        assertEquals(1, fake.userServiceRemoves.size)
        assertFalse(fake.userServiceRemoves[0].args.getBoolean(USER_SERVICE_ARG_REMOVE))
    }

    @Test
    fun aLaterServerIsAskedToDropAConnection() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 14, 0)

        wire.removeUserService(RecordingCallback(), args("dropped"), false)

        assertEquals(1, fake.userServiceRemoves.size)
    }

    @Test
    fun killingIsSentBelowThePatchGateToo() {
        val fake = FakeShizukuService()
        val wire = attached(fake, 13, GATED_PATCH_VERSION - 1)

        wire.removeUserService(null, args("killed"), true)

        assertEquals(1, fake.userServiceRemoves.size)
        assertTrue(fake.userServiceRemoves[0].args.getBoolean(USER_SERVICE_ARG_REMOVE))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"
        val COMPONENT = ComponentName(PACKAGE, CLASS)
        const val PROCESS_SUFFIX = "probe"

        /** The oldest server that is asked to drop a connection rather than kill the service. */
        const val GATED_PATCH_VERSION = 4
    }
}
