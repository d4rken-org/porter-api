package eu.darken.porter.sdk

import android.content.ComponentName
import android.os.Bundle
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_PERMISSION_GRANTED
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Shizuku wire has no permission-state callback of its own, so a server announces a change by
 * re-sending the state it attached with. What that resend reports, and what it must leave alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class ShizukuProtocolWirePermissionStateTest {

    private val callbacks = RecordingCallbacks()

    private class RecordingCallbacks : PorterWire.Callbacks {

        var states = 0
        var granted = false
        var rationale = false

        override fun onRequestPermissionResult(requestCode: Int, allowed: Boolean) {
        }

        override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
            states++
            this.granted = granted
            this.rationale = shouldShowRationale
        }
    }

    private fun permissionState(granted: Boolean, rationale: Boolean): Bundle {
        val state = Bundle()
        state.putInt(BIND_APPLICATION_SERVER_VERSION, ShizukuProtocol.MINIMUM_VERSION)
        state.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, granted)
        state.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, rationale)
        return state
    }

    /** A wire that has completed the handshake against a server sending [state]. */
    private fun attached(fake: FakeShizukuService, state: Bundle): ShizukuProtocolWire {
        fake.bindApplicationReply = state
        val wire = ShizukuProtocolWire(fake, callbacks)
        wire.attach(PACKAGE)
        return wire
    }

    private fun args(tag: String): UserServiceArgs =
        UserServiceArgs(ComponentName(PACKAGE, CLASS), processNameSuffix = "probe", tag = tag)

    @Test
    fun aResendCarryingAGrantReportsIt() {
        val fake = FakeShizukuService()
        attached(fake, permissionState(granted = false, rationale = true))

        fake.pushBindApplication(permissionState(granted = true, rationale = false))

        assertEquals(1, callbacks.states)
        assertTrue(callbacks.granted)
        assertFalse(callbacks.rationale)
    }

    @Test
    fun aResendCarryingARevocationReportsIt() {
        val fake = FakeShizukuService()
        attached(fake, permissionState(granted = true, rationale = false))

        fake.pushBindApplication(permissionState(granted = false, rationale = true))

        assertEquals(1, callbacks.states)
        assertFalse(callbacks.granted)
        assertTrue(callbacks.rationale)
    }

    @Test
    fun aResendWithNoStateAtAllReportsNeitherGrantNorRationale() {
        val fake = FakeShizukuService()
        attached(fake, permissionState(granted = true, rationale = true))

        fake.pushBindApplication(null)

        assertEquals(1, callbacks.states)
        assertFalse(callbacks.granted)
        assertFalse(callbacks.rationale)
    }

    /**
     * The handshake state is what the unbind gate reads its version and patch level from, and the
     * gate is driven either side of a resend that would open it if the resend were taken.
     */
    @Test
    fun aResendDoesNotMoveTheVersionsTheUnbindGateReads() {
        val fake = FakeShizukuService()
        val state = permissionState(granted = false, rationale = false)
        state.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, GATED_PATCH_VERSION - 1)
        val wire = attached(fake, state)

        assertEquals(0, wire.removeUserService(null, args("kept"), false))
        assertTrue(fake.userServiceRemoves.isEmpty())

        val resend = permissionState(granted = true, rationale = false)
        resend.putInt(BIND_APPLICATION_SERVER_VERSION, 14)
        resend.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, GATED_PATCH_VERSION)
        fake.pushBindApplication(resend)

        assertEquals(1, callbacks.states)
        assertEquals(0, wire.removeUserService(null, args("still kept"), false))
        assertTrue(fake.userServiceRemoves.isEmpty())
    }

    @Test
    fun theFirstPushCompletesTheHandshakeAndReportsNothingThroughThisPath() {
        val fake = FakeShizukuService()
        fake.bindApplicationReply = permissionState(granted = true, rationale = true)

        val reply = ShizukuProtocolWire(fake, callbacks).attach(PACKAGE)

        assertNotNull(reply)
        assertTrue(reply!!.permissionGranted)
        assertTrue(reply.shouldShowRequestPermissionRationale)
        assertEquals(0, callbacks.states)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CLASS = "ProbeService"

        /** The oldest server that is asked to drop a connection rather than kill the service. */
        const val GATED_PATCH_VERSION = 4
    }
}
