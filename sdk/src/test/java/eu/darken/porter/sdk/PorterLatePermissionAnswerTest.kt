package eu.darken.porter.sdk

import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_ALLOWED
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The manager's prompt outlives a [PorterConnection.requestPermission] whose caller stopped waiting,
 * as one started from an activity's scope does across a rotation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterLatePermissionAnswerTest {

    private val io = Executors.newCachedThreadPool()

    @Before
    fun inlineServerCalls() {
        // Server calls run inline, so each step below has happened when the next one asserts.
        Porter.ioDispatcher = Dispatchers.Unconfined
    }

    @After
    fun teardown() {
        Porter.resetForTest()
        io.shutdownNow()
    }

    /**
     * Starts a request and stops waiting for it once the server has it. Cancelled on an unconfined
     * caller and joined, so the request has given up its wait before anything answers it.
     */
    private suspend fun requestAndCancel(connection: PorterConnection, requested: () -> Int): Int {
        val caller = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            connection.requestPermission()
        }
        val code = requested()
        assertTrue("the request never reached the server", code > 0)
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled)
        return code
    }

    @Test
    fun aGrantAnsweredAfterTheCallerStoppedWaitingIsTheState() = runTest {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!
        val code = requestAndCancel(connection) { fake.requestedPermissionCode }

        fake.pushRequestPermissionResult(code, allowed = true)

        assertEquals(PermissionState.Granted, connection.permission.value)
    }

    @Test
    fun aGrantAnsweredOnTheShizukuWireAfterTheCallerStoppedWaitingIsTheState() = runTest {
        val fake = FakeShizukuService()
        Porter.onBinderReceived(fake, PACKAGE, PorterBackend.SHIZUKU)
        val connection = Porter.connection.value!!
        val code = requestAndCancel(connection) { fake.requestedPermissionCode }

        fake.pushRequestPermissionResult(code, Bundle().apply { putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, true) })

        assertEquals(PermissionState.Granted, connection.permission.value)
    }

    @Test
    fun aDenialAnsweredAfterTheCallerStoppedWaitingIsTheState() = runTest {
        val fake = FakePorterService().apply { attachReply.putBoolean(REPLY_PERMISSION_GRANTED, true) }
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!
        assertEquals(PermissionState.Granted, connection.permission.value)
        val code = requestAndCancel(connection) { fake.requestedPermissionCode }

        fake.pushRequestPermissionResult(code, allowed = false)

        assertEquals(PermissionState.Denied(permanentlyDenied = false), connection.permission.value)
    }

    /**
     * The replacement has published but the replaced connection is not marked lost yet, so only its
     * no longer being current keeps the answer from becoming its state.
     */
    @Test
    fun anAnswerArrivingWhileAReplacementPublishesIsNotTheReplacedConnectionsState() = runTest {
        val replaced = FakePorterService()
        Porter.onBinderReceived(replaced, PACKAGE)
        val connection = Porter.connection.value!!
        val code = requestAndCancel(connection) { replaced.requestedPermissionCode }

        var deliveredWhileNotCurrent: Boolean? = null
        // Resumed inline by the publication, under Porter's lock and ahead of the loss.
        val watcher: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            Porter.connection.collect { published ->
                if (published != null && published !== connection && deliveredWhileNotCurrent == null) {
                    deliveredWhileNotCurrent = !Porter.isCurrent(connection)
                    replaced.pushRequestPermissionResult(code, allowed = true)
                }
            }
        }
        try {
            Porter.onBinderReceived(FakePorterService(), PACKAGE)
        } finally {
            watcher.cancel()
        }

        assertEquals("the answer was not delivered during the publication", true, deliveredWhileNotCurrent)
        assertNotEquals(PermissionState.Granted, connection.permission.value)
    }

    private class BlockingRequest : FakePorterService() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)

        override fun requestPermission(requestCode: Int) {
            super.requestPermission(requestCode)
            entered.countDown()
            // Bounded, so a regression fails on time instead of hanging.
            release.await(BLOCK_LIMIT_MS, TimeUnit.MILLISECONDS)
            returned.countDown()
        }
    }

    @Test
    fun aGrantAnsweredAfterTheCallerStoppedWaitingOnTheRequestCallIsTheState() = runBlocking<Unit> {
        Porter.ioDispatcher = io.asCoroutineDispatcher()
        val fake = BlockingRequest()
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!

        val caller = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            connection.requestPermission()
        }
        assertTrue("the request never reached the server", fake.entered.await(2, TimeUnit.SECONDS))
        withTimeout(BLOCK_LIMIT_MS / 2) { caller.cancelAndJoin() }
        assertTrue(caller.isCancelled)
        assertEquals("the request call returned before the caller stopped waiting", 1L, fake.returned.count)

        fake.release.countDown()
        assertTrue(fake.returned.await(2, TimeUnit.SECONDS))
        fake.pushRequestPermissionResult(fake.requestedPermissionCode, allowed = true)

        assertEquals(PermissionState.Granted, connection.permission.value)
    }

    /** Answers the cancelled request from inside the check, ahead of the check's own stale reply. */
    private class AnswerDuringCheck : FakePorterService() {
        var lateAnswerCode = -1

        override fun checkSelfPermission(): Boolean {
            val code = lateAnswerCode
            if (code > 0) {
                lateAnswerCode = -1
                pushRequestPermissionResult(code, allowed = true)
            }
            return super.checkSelfPermission()
        }
    }

    @Test
    fun aGrantAnsweredDuringACheckWinsOverTheChecksStaleReply() = runTest {
        val fake = AnswerDuringCheck()
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!
        assertEquals(PermissionState.Denied(permanentlyDenied = false), connection.permission.value)
        fake.lateAnswerCode = requestAndCancel(connection) { fake.requestedPermissionCode }

        val checked = connection.checkPermission()

        assertFalse("the check never asked the server", fake.lateAnswerCode > 0)
        assertEquals(PermissionState.Granted, checked)
        assertEquals(PermissionState.Granted, connection.permission.value)
    }

    @Test
    fun aStatePushedAfterALateAnswerIsTheState() = runTest {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!
        val code = requestAndCancel(connection) { fake.requestedPermissionCode }
        fake.pushRequestPermissionResult(code, allowed = true)

        fake.pushPermissionState(
            Bundle().apply {
                putBoolean(REPLY_PERMISSION_GRANTED, false)
                putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, true)
            },
        )

        assertEquals(PermissionState.Denied(permanentlyDenied = true), connection.permission.value)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val BLOCK_LIMIT_MS = 4000L
    }
}
