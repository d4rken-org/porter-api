package eu.darken.porter.sdk

import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol.USER_SERVICE_RESULT_NOT_RUNNING
import eu.darken.porter.sdk.UserServiceTestSupport.PACKAGE
import eu.darken.porter.sdk.UserServiceTestSupport.args
import eu.darken.porter.sdk.UserServiceTestSupport.connection
import eu.darken.porter.server.IPorterServiceConnection
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a caller that stops waiting sees against a server that is alive but answers nothing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterCancellationTest {

    private val io = Executors.newCachedThreadPool()

    /** Holds every wedged call until counted down; a regression then fails on time instead of hanging. */
    private val release = CountDownLatch(1)

    @Before
    fun setup() {
        Porter.ioDispatcher = io.asCoroutineDispatcher()
        Thread({ if (!release.await(WEDGE_LIMIT_MS, TimeUnit.MILLISECONDS)) release.countDown() }, "wedge-limit").apply {
            isDaemon = true
            start()
        }
    }

    @After
    fun teardown() {
        release.countDown()
        Porter.resetForTest()
        io.shutdownNow()
    }

    private inner class WedgedService : ScriptedPorterService() {
        @Volatile
        var wedgeProperty = false

        @Volatile
        var wedgeAdd = false

        @Volatile
        var wedgeRemove = false

        val addEntered = CountDownLatch(1)

        val propertyEntered = CountDownLatch(1)

        @Volatile
        var propertyWrites = 0

        /** "add" and "remove", in the order the server applied them. */
        val userServiceOps: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun getSystemProperty(name: String, defaultValue: String?): String? {
            propertyEntered.countDown()
            if (wedgeProperty) release.await()
            return defaultValue
        }

        override fun setSystemProperty(name: String, value: String) {
            propertyWrites++
        }

        override fun addUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
            addEntered.countDown()
            if (wedgeAdd) release.await()
            userServiceOps.add("add")
            return super.addUserService(conn, args)
        }

        override fun removeUserService(conn: IPorterServiceConnection?, args: Bundle): Int {
            if (wedgeRemove) release.await()
            userServiceOps.add("remove")
            return super.removeUserService(conn, args)
        }
    }

    private inline fun elapsedMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    @Test
    fun aTimeoutEndsTheWaitOnACallTheServerNeverAnswers() = runBlocking<Unit> {
        val server = WedgedService().apply { wedgeProperty = true }
        Porter.onBinderReceived(server, PACKAGE)

        var answer: String? = "unset"
        val waited = elapsedMs { answer = withTimeoutOrNull(100) { connection().getSystemProperty("ro.x", "default") } }

        assertNull(answer)
        assertTrue("waited ${waited}ms", waited < WEDGE_LIMIT_MS / 2)
    }

    /** The bind the collector gave up on still lands, and is dropped on the server once it has. */
    @Test
    fun cancellingDuringAWedgedBindReturnsAndDropsTheLateBinding() = runBlocking<Unit> {
        val server = WedgedService().apply { wedgeAdd = true }
        Porter.onBinderReceived(server, PACKAGE)
        val collector = RecordingCollector(this, connection().userService(args("wedged-bind")))
        assertTrue(server.addEntered.await(2, TimeUnit.SECONDS))

        val waited = elapsedMs { withTimeout(WEDGE_LIMIT_MS) { collector.job.cancelAndJoin() } }
        assertTrue("waited ${waited}ms", waited < WEDGE_LIMIT_MS / 2)
        assertEquals(0, server.userServiceRemoves)

        release.countDown()

        UserServiceTestSupport.await { server.userServiceRemoves == 1 }
        assertSame(server.userServiceConnection!!.asBinder(), server.removedUserServiceConnection!!.asBinder())
    }

    /** A call that never started is not made once its caller stopped waiting: it would land stale. */
    @Test
    fun aQueuedCallWhoseCallerGaveUpIsNeverMade() = runBlocking<Unit> {
        val single = Executors.newSingleThreadExecutor()
        try {
            Porter.ioDispatcher = single.asCoroutineDispatcher()
            val server = WedgedService().apply { wedgeProperty = true }
            Porter.onBinderReceived(server, PACKAGE)
            val occupying = launch(Dispatchers.Default) { connection().getSystemProperty("ro.x") }
            assertTrue(server.propertyEntered.await(2, TimeUnit.SECONDS))

            assertNull(withTimeoutOrNull(100) { connection().setSystemProperty("debug.x", "1") })
            release.countDown()
            occupying.join()
            // Anything queued behind the released call has run by the time this one does.
            connection().getSystemProperty("ro.y")

            assertEquals(0, server.propertyWrites)
        } finally {
            single.shutdownNow()
        }
    }

    /**
     * The collector gave up while its bind was in flight, and Porter was replaced before that bind
     * landed on the old server, which still runs. Whatever the replacement dropped there was dropped
     * before the bind registered, so the bind has to be undone once it lands.
     */
    @Test
    fun aBindThatLandsAfterItsCollectorLeftAndTheConnectionWasReplacedIsDropped() = runBlocking<Unit> {
        val old = WedgedService().apply { wedgeAdd = true }
        Porter.onBinderReceived(old, PACKAGE)
        val collector = RecordingCollector(this, connection().userService(args("late-bind")))
        assertTrue(old.addEntered.await(2, TimeUnit.SECONDS))
        collector.job.cancelAndJoin()

        Porter.onBinderReceived(WedgedService(), PACKAGE)
        release.countDown()

        UserServiceTestSupport.await { old.userServiceOps.lastOrNull() == "remove" && "add" in old.userServiceOps }
        assertEquals(listOf("add", "remove"), old.userServiceOps.toList())
        assertSame(old.userServiceConnection!!.asBinder(), old.removedUserServiceConnection!!.asBinder())
    }

    /** A no-create bind registers on the record of a stopped service, and answers "not running". */
    @Test
    fun aNoCreateBindThatLandsAfterItsCollectorLeftIsDroppedThoughNotRunning() = runBlocking<Unit> {
        val server = WedgedService().apply {
            wedgeAdd = true
            userServiceResult = USER_SERVICE_RESULT_NOT_RUNNING
        }
        Porter.onBinderReceived(server, PACKAGE)
        val collector = RecordingCollector(this, connection().userService(args("not-running"), start = false))
        assertTrue(server.addEntered.await(2, TimeUnit.SECONDS))
        collector.job.cancelAndJoin()

        release.countDown()

        UserServiceTestSupport.await { server.userServiceOps.lastOrNull() == "remove" }
        assertEquals(listOf("add", "remove"), server.userServiceOps.toList())
    }

    @Test
    fun cancellingWhileTheUnbindIsWedgedReturns() = runBlocking<Unit> {
        val server = WedgedService()
        Porter.onBinderReceived(server, PACKAGE)
        val collector = RecordingCollector(this, connection().userService(args("wedged-unbind")))
        UserServiceTestSupport.await { server.adds == 1 }
        server.wedgeRemove = true

        val waited = elapsedMs { withTimeout(WEDGE_LIMIT_MS) { collector.job.cancelAndJoin() } }
        assertTrue("waited ${waited}ms", waited < WEDGE_LIMIT_MS / 2)

        release.countDown()

        UserServiceTestSupport.await { server.userServiceRemoves == 1 }
    }

    private companion object {
        const val WEDGE_LIMIT_MS = 4000L
    }
}
