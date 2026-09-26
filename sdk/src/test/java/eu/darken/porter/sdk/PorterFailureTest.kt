package eu.darken.porter.sdk

import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Parcel
import android.os.RemoteException
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.sdk.UserServiceTestSupport.peek
import eu.darken.porter.server.IPorterServiceConnection
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What a caller sees when the server refuses a call, dies under it, or attaches incompletely. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterFailureTest {

    @Before
    fun setup() {
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    /** Fails every call it is told to, the way the generated stub surfaces what the server threw. */
    private class FailingPorterService : FakePorterService() {
        var failure: Exception? = null
        var addFailure: Exception? = null

        override fun getSystemProperty(name: String, defaultValue: String?): String? {
            failure?.let { throw it }
            return defaultValue
        }

        override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
            addFailure?.let { throw it }
            return super.addUserService(conn, args)
        }
    }

    private fun attached(): FailingPorterService {
        val fake = FailingPorterService()
        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)
        return fake
    }

    private suspend fun failureOf(call: suspend () -> Unit): Throwable? = runCatching { call() }.exceptionOrNull()

    @Test
    fun aRefusalFromThePorterServerIsAPorterSecurityException() = runBlocking<Unit> {
        val fake = attached()
        val refusal = SecurityException("Caller has no permission")
        fake.failure = refusal

        val failure = failureOf { connection().getSystemProperty("ro.x") }

        assertTrue(failure is PorterSecurityException)
        assertSame(refusal, failure!!.cause)
        assertEquals(refusal.message, failure.message)
    }

    @Test
    fun aDeadPorterServerIsAPorterRemoteException() = runBlocking<Unit> {
        val fake = attached()
        fake.failure = DeadObjectException()

        val failure = failureOf { connection().getSystemProperty("ro.x") }

        assertTrue(failure is PorterRemoteException)
        assertTrue(failure!!.cause is DeadObjectException)
    }

    @Test
    fun anotherFailureFromThePorterServerIsAPorterRemoteException() = runBlocking<Unit> {
        val fake = attached()
        val thrown = IllegalStateException("Not an attached client")
        fake.failure = thrown

        val failure = failureOf { connection().getSystemProperty("ro.x") }

        assertTrue(failure is PorterRemoteException)
        assertSame(thrown, failure!!.cause)
    }

    /** A refusal from a Shizuku server arrives inside the reply, and readException raises it. */
    @Test
    fun aRefusalReadFromAShizukuReplyIsAPorterSecurityException() {
        val refusing = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeException(SecurityException("Caller has no permission"))
                return true
            }
        }
        val wire = ShizukuProtocolWire(refusing, NoCallbacks)

        val failure = runCatching { wire.checkSelfPermission() }.exceptionOrNull()

        assertTrue(failure is PorterSecurityException)
        assertEquals("Caller has no permission", failure!!.message)
    }

    @Test
    fun anotherFailureReadFromAShizukuReplyIsAPorterRemoteException() {
        val failing = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeException(IllegalStateException("Not an attached client"))
                return true
            }
        }
        val wire = ShizukuProtocolWire(failing, NoCallbacks)

        val failure = runCatching { wire.checkSelfPermission() }.exceptionOrNull()

        assertTrue(failure is PorterRemoteException)
        assertTrue(failure!!.cause is IllegalStateException)
    }

    /** Every call that blocks on the server runs on the IO dispatcher, not on the caller's thread. */
    @Test
    fun serverCallsRunOnTheIoDispatcher() = runTest {
        val io = Executors.newSingleThreadExecutor { Thread(it, IO_THREAD) }
        try {
            Porter.ioDispatcher = io.asCoroutineDispatcher()
            val threads = Collections.synchronizedList(ArrayList<String>())
            val fake = object : FakePorterService() {
                override fun checkSelfPermission(): Boolean {
                    threads.add("checkSelfPermission@" + Thread.currentThread().name)
                    return super.checkSelfPermission()
                }

                override fun getSystemProperty(name: String, defaultValue: String?): String? {
                    threads.add("getSystemProperty@" + Thread.currentThread().name)
                    return defaultValue
                }

                override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
                    threads.add("addUserService@" + Thread.currentThread().name)
                    return super.addUserService(conn, args)
                }

                override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
                    threads.add("removeUserService@" + Thread.currentThread().name)
                    return super.removeUserService(conn, args)
                }
            }
            Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)

            connection().checkPermission()
            connection().getSystemProperty("ro.x")
            val collector = RecordingCollector(backgroundScope, connection().userService(args("threaded")))
            UserServiceTestSupport.await { threads.any { it.startsWith("addUserService@") } }
            collector.job.cancel()
            UserServiceTestSupport.await { threads.any { it.startsWith("removeUserService@") } }

            // Coroutine debug mode appends " @coroutine#n" to the thread name.
            val elsewhere = threads.filterNot { it.substringAfter('@').startsWith(IO_THREAD) }
            assertTrue("ran off the IO dispatcher: $elsewhere", elsewhere.isEmpty())
            assertEquals(4, threads.map { it.substringBefore('@') }.toSet().size)
        } finally {
            io.shutdownNow()
        }
    }

    /**
     * The server died, and its death has not been dispatched yet. The flow ends as that death would
     * end it, rather than failing, and the bind leaves no registration behind.
     */
    @Test
    fun aBindOnADeadServerCompletesTheFlow() = runTest {
        val fake = attached()
        fake.addFailure = DeadObjectException()

        val collector = RecordingCollector(backgroundScope, connection().userService(args("dead")))

        assertTrue("the flow did not end", collector.completed)
        assertNull(collector.failure)
        val binding = peek(args("dead"))
        assertFalse(
            "the failed bind left its listener registered",
            binding != null && synchronized(connection().userServices.lock) { binding.hasListeners() },
        )
    }

    @Test
    fun aBindThatFailsOtherwiseStillFails() = runTest {
        val fake = attached()

        fake.addFailure = RemoteException("transaction failed")
        val remote = RecordingCollector(backgroundScope, connection().userService(args("remote")))
        assertTrue(remote.failure is PorterRemoteException)

        fake.addFailure = SecurityException("Caller has no permission")
        val refused = RecordingCollector(backgroundScope, connection().userService(args("refused")))
        assertTrue(refused.failure is PorterSecurityException)
        assertFalse(refused.completed)
    }

    @Test
    fun aReplyWithoutAUidIsCompletedByAskingTheServer() {
        val fake = FakePorterService()
        fake.serverUid = 2000

        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)

        assertEquals(2000, connection().uid)
    }

    @Test
    fun aUidTheServerCannotSupplyPublishesNothing() {
        val fake = object : FakePorterService() {
            override fun getUid(): Int = throw DeadObjectException()
        }

        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)

        assertNull(Porter.connection.value)
    }

    @Test
    fun aReportedUidIsNotAskedForAgain() {
        val fake = object : FakePorterService() {
            override fun getUid(): Int = throw AssertionError("the attach reply already named the uid")
        }
        fake.attachReply.putInt(REPLY_SERVER_UID, 0)

        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE)

        assertEquals(0, connection().uid)
    }

    /** An old Shizuku server that is running is reported as such, on Shizuku's version scale. */
    @Test
    fun aShizukuServerBelowTheFloorIsReportedIncompatible() = runBlocking<Unit> {
        val fake = FakeShizukuService()
        fake.bindApplicationReply = FakeShizukuService.replyWithVersion(12)

        Porter.onBinderReceived(fake, UserServiceTestSupport.PACKAGE, PorterBackend.SHIZUKU)

        assertNull(Porter.connection.value)
        val availability = Porter.availability(RuntimeEnvironment.getApplication())
        assertTrue(availability is PorterAvailability.Incompatible)
        val why = (availability as PorterAvailability.Incompatible).incompatibility
        assertEquals(PorterBackend.SHIZUKU, why.backend)
        assertEquals(12, why.serverVersion)
        assertTrue(why.serverTooOld)
    }

    private companion object {
        const val IO_THREAD = "porter-io-test"
    }

    private object NoCallbacks : PorterWire.Callbacks {
        override fun onRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        }

        override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
        }
    }
}
