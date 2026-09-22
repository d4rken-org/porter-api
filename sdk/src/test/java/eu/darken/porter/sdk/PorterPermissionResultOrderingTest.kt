package eu.darken.porter.sdk

import android.os.Bundle
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.Dispatchers

/**
 * A permission result and a permission state push both arrive on binder threads, and the caller
 * of [PorterConnection.requestPermission] resumes on its own dispatcher some time after the result.
 * The state is the order the server sent things in, not the order the caller got to run in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterPermissionResultOrderingTest {

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
    fun aDenialPushedAfterAnAllowedResultIsTheState() = runTest {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!

        val result = async(start = CoroutineStart.UNDISPATCHED) { connection.requestPermission() }
        fake.pushRequestPermissionResult(fake.requestedPermissionCode, allowed = true)
        assertEquals("the result was not applied as it arrived", PermissionState.Granted, connection.permission.value)

        // Pushed before the caller's coroutine has resumed.
        fake.pushPermissionState(permissionState(granted = false, permanentlyDenied = true))

        assertEquals(PermissionState.Denied(permanentlyDenied = true), result.await())
        assertEquals(PermissionState.Denied(permanentlyDenied = true), connection.permission.value)
    }

    @Test
    fun aGrantPushedAfterADeniedResultIsTheState() = runTest {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!

        val result = async(start = CoroutineStart.UNDISPATCHED) { connection.requestPermission() }
        fake.pushRequestPermissionResult(fake.requestedPermissionCode, allowed = false)
        assertEquals(PermissionState.Denied(permanentlyDenied = false), connection.permission.value)

        // Pushed before the caller's coroutine has resumed and asked whether the denial is permanent.
        fake.pushPermissionState(permissionState(granted = true, permanentlyDenied = false))

        assertEquals(PermissionState.Granted, result.await())
        assertEquals("the answer about the denial overwrote the grant that followed it", PermissionState.Granted, connection.permission.value)
    }

    @Test
    fun anAllowedResultIsTheStateWhenNothingNewerFollows() = runTest {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        val connection = Porter.connection.value!!

        val result = async(start = CoroutineStart.UNDISPATCHED) { connection.requestPermission() }
        fake.pushRequestPermissionResult(fake.requestedPermissionCode, allowed = true)

        assertEquals(PermissionState.Granted, result.await())
        assertEquals(PermissionState.Granted, connection.permission.value)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"

        fun permissionState(granted: Boolean, permanentlyDenied: Boolean): Bundle = Bundle().apply {
            putBoolean(REPLY_PERMISSION_GRANTED, granted)
            putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, permanentlyDenied)
        }
    }
}
