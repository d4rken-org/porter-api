package eu.darken.porter.sdk

import android.os.IBinder
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers

/** The binder identity a user service binding presents to the server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterProtocolWireUserServiceTest {

    /**
     * Holds its first lookup until the other thread reaches one too, so a wire that looks up and
     * registers without a lock creates two stubs for the one callback.
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

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun attached(): ScriptedPorterService {
        val fake = ScriptedPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        return fake
    }

    @Test
    fun oneBindingPresentsOneBinderAcrossAddAndRemove() = runTest {
        val fake = attached()
        val args = args("one-binding")

        val collector = RecordingCollector(backgroundScope, connection().userService(args))
        collector.job.cancelAndJoin()

        assertEquals(2, fake.connections.size)
        assertNotNull(fake.connections[0])
        assertSame(fake.connections[0], fake.connections[1])
    }

    @Test
    fun twoBindingsPresentTwoBinders() = runTest {
        val fake = attached()

        RecordingCollector(backgroundScope, connection().userService(args("first-binding")))
        RecordingCollector(backgroundScope, connection().userService(args("second-binding")))

        assertEquals(2, fake.connections.size)
        assertNotNull(fake.connections[0])
        assertNotNull(fake.connections[1])
        assertNotSame(fake.connections[0], fake.connections[1])
    }

    @Test
    fun removingAndKillingNamesNoConnection() = runBlocking<Unit> {
        val fake = attached()

        connection().stopUserService(args("killed-binding"))

        assertEquals(1, fake.connections.size)
        assertNull(fake.connections[0])
    }

    @Test
    fun twoThreadsBindingOneServiceRegisterOneBinder() {
        val fake = attached()
        val callback = GatedCallback()
        val add = args("concurrent-binding")

        val wire = connection().wire
        val bind = Runnable { wire.addUserService(callback, add, false) }
        val first = Thread(bind)
        val second = Thread(bind)
        first.start()
        second.start()
        first.join()
        second.join()

        assertEquals(2, fake.connections.size)
        assertNotNull(fake.connections[0])
        assertSame(fake.connections[0], fake.connections[1])
    }
}
