package eu.darken.porter.sdk

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_API_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.ATTACH_APPLICATION_PACKAGE_NAME
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_PERMISSION_GRANTED
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_PATCH_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_SECONTEXT
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_UID
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SERVER_VERSION
import eu.darken.porter.sdk.ShizukuProtocol.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_ALLOWED
import eu.darken.porter.sdk.ShizukuProtocol.REQUEST_PERMISSION_REPLY_IS_ONETIME
import eu.darken.porter.sdk.ShizukuProtocol.USER_SERVICE_ARG_TOKEN
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the Shizuku wire puts on the wire, read back by the stubs the real server is built from. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class ShizukuProtocolWireTest {

    private val callbacks = RecordingCallbacks()

    private class RecordingCallbacks : PorterWire.Callbacks {

        var results = 0
        var requestCode = -1
        var allowed = false

        override fun onRequestPermissionResult(requestCode: Int, allowed: Boolean) {
            results++
            this.requestCode = requestCode
            this.allowed = allowed
        }

        override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
        }
    }

    @After
    fun teardown() {
        Porter.resetForTest()
    }

    private fun assertCodes(fake: FakeShizukuService, vararg expected: Int) {
        assertEquals(expected.toList(), fake.codes)
    }

    @Test
    fun attachSendsTheApiVersionAndPackageName() {
        val fake = FakeShizukuService()

        ShizukuProtocolWire(fake, callbacks).attach(PACKAGE)

        assertCodes(fake, 18)
        val args = fake.attachArgs
        assertNotNull(args)
        assertEquals(13, args!!.getInt(ATTACH_APPLICATION_API_VERSION))
        assertEquals(PACKAGE, args.getString(ATTACH_APPLICATION_PACKAGE_NAME))
    }

    @Test
    fun attachReadsEveryFieldOfTheBindApplicationReply() {
        val fake = FakeShizukuService()
        val state = Bundle()
        state.putInt(BIND_APPLICATION_SERVER_UID, SERVER_UID)
        state.putInt(BIND_APPLICATION_SERVER_VERSION, 13)
        state.putInt(BIND_APPLICATION_SERVER_PATCH_VERSION, PATCH_VERSION)
        state.putString(BIND_APPLICATION_SERVER_SECONTEXT, SECONTEXT)
        state.putBoolean(BIND_APPLICATION_PERMISSION_GRANTED, true)
        state.putBoolean(BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, true)
        fake.bindApplicationReply = state

        val reply = ShizukuProtocolWire(fake, callbacks).attach(PACKAGE)

        assertNotNull(reply)
        assertEquals(SERVER_UID, reply!!.serverUid)
        assertEquals(13, reply.protocolVersion)
        assertEquals(PATCH_VERSION, reply.patchVersion)
        assertEquals(SECONTEXT, reply.seLinuxContext)
        assertEquals(CAPABILITIES_NONE, reply.capabilities)
        assertTrue(reply.permissionGranted)
        assertTrue(reply.shouldShowRequestPermissionRationale)
    }

    @Test
    fun aServerBelowTheFloorIsRefused() {
        val fake = FakeShizukuService()
        fake.bindApplicationReply = FakeShizukuService.replyWithVersion(12)
        val wire = ShizukuProtocolWire(fake, callbacks)

        val thrown = assertThrows(IllegalStateException::class.java) { wire.attach(PACKAGE) }

        assertTrue(thrown.message!!.contains("12"))
        assertTrue(thrown.message!!.contains("13"))
    }

    @Test
    fun aServerThatNeverAnswersAttachTimesOut() {
        val fake = FakeShizukuService()
        fake.suppressBindApplication = true
        val wire = ShizukuProtocolWire(fake, callbacks, 50)

        assertThrows(IllegalStateException::class.java) { wire.attach(PACKAGE) }
    }

    /**
     * The production path: on a device the reply is dispatched to another binder thread, so it can
     * land after the wait has begun. A local transact answers on the calling thread, which leaves
     * the latch at zero before every other test here waits on it.
     */
    @Test
    fun aReplyArrivingFromAnotherThreadCompletesTheAttach() {
        val fake = FakeShizukuService()
        fake.deferBindApplicationMs = 50

        val reply = ShizukuProtocolWire(fake, callbacks, 5000).attach(PACKAGE)

        assertNotNull(reply)
        assertEquals(13, reply!!.protocolVersion)
    }

    @Test
    fun aResentBindApplicationDoesNotDisturbACompletedAttach() {
        val fake = FakeShizukuService()
        val wire = ShizukuProtocolWire(fake, callbacks)
        val first = wire.attach(PACKAGE)

        val later = FakeShizukuService.replyWithVersion(99)
        later.putInt(BIND_APPLICATION_SERVER_UID, 1)
        fake.pushBindApplication(later)

        // Attaching again decodes the state the wire kept, which is the one the handshake used.
        val again = wire.attach(PACKAGE)
        assertNotNull(first)
        assertNotNull(again)
        assertEquals(first!!.protocolVersion, again!!.protocolVersion)
        assertEquals(first.serverUid, again.serverUid)
    }

    @Test
    fun forwardWritesTheShizukuEnvelope() {
        val fake = FakeShizukuService()
        val target: IBinder = Binder()

        val data = Parcel.obtain()
        try {
            data.writeInt(20816)
            ShizukuProtocolWire(fake, callbacks).forward(target, 7, data, null, 42)
        } finally {
            data.recycle()
        }

        assertCodes(fake, 1)
        assertSame(target, fake.forwardedTarget)
        assertEquals(7, fake.forwardedCode)
        assertEquals(42, fake.forwardedFlags)
        assertEquals(20816, fake.forwardedPayload)
    }

    @Test
    fun aPermissionResultReachesTheCallbacks() {
        val fake = FakeShizukuService()
        ShizukuProtocolWire(fake, callbacks).attach(PACKAGE)

        val result = Bundle()
        result.putBoolean(REQUEST_PERMISSION_REPLY_ALLOWED, true)
        fake.pushRequestPermissionResult(REQUEST_CODE, result)

        assertEquals(1, callbacks.results)
        assertEquals(REQUEST_CODE, callbacks.requestCode)
        assertTrue(callbacks.allowed)
    }

    @Test
    fun theMechanicalCallsUseTheDocumentedCodes() {
        val fake = FakeShizukuService()
        fake.serverUid = SERVER_UID
        fake.seLinuxContext = SECONTEXT
        fake.remotePermission = PackageManager.PERMISSION_GRANTED
        fake.systemProperty = "answered"
        fake.selfPermission = true
        fake.rationale = true
        fake.flags = 6
        val wire = ShizukuProtocolWire(fake, callbacks)

        assertEquals(SERVER_UID, wire.getUid())
        assertEquals(SECONTEXT, wire.getSELinuxContext())
        assertEquals(PackageManager.PERMISSION_GRANTED, wire.checkPermission("android.permission.DUMP"))
        assertEquals("answered", wire.getSystemProperty("ro.asked", "fallback"))
        wire.setSystemProperty("ro.written", "value")
        wire.requestPermission(REQUEST_CODE)
        assertTrue(wire.checkSelfPermission())
        assertTrue(wire.shouldShowRequestPermissionRationale())
        wire.exit()
        assertEquals(6, wire.getFlagsForUid(TARGET_UID, 2))
        wire.updateFlagsForUid(TARGET_UID, 2, 2)

        assertCodes(fake, 4, 9, 5, 10, 11, 15, 16, 17, 101, 106, 107)
        assertEquals("android.permission.DUMP", fake.checkedPermission)
        assertEquals("ro.asked", fake.queriedPropertyName)
        assertEquals("fallback", fake.queriedPropertyDefault)
        assertEquals("ro.written", fake.setPropertyName)
        assertEquals("value", fake.setPropertyValue)
        assertEquals(REQUEST_CODE, fake.requestedPermissionCode)
        assertEquals(1, fake.exitCalls)
        assertEquals(TARGET_UID, fake.flagsUid)
        assertEquals(2, fake.flagsMask)
        assertEquals(2, fake.flagsValue)
    }

    @Test
    fun attachUserServiceCarriesTheTokenBundle() {
        val fake = FakeShizukuService()
        val host: IBinder = Binder()

        ShizukuProtocolWire(fake, callbacks).attachUserService(host, TOKEN)

        assertCodes(fake, 102)
        assertSame(host, fake.attachedUserServiceBinder)
        val options = fake.attachedUserServiceOptions
        assertNotNull(options)
        assertEquals(TOKEN, options!!.getString(USER_SERVICE_ARG_TOKEN))
    }

    @Test
    fun permissionConfirmationIsOneWay() {
        val fake = FakeShizukuService()

        ShizukuProtocolWire(fake, callbacks)
            .dispatchPermissionConfirmationResult(TARGET_UID, TARGET_PID, REQUEST_CODE, true, false)

        assertCodes(fake, 105)
        assertEquals(IBinder.FLAG_ONEWAY, fake.lastTransactFlags)
        assertFalse(fake.lastTransactExpectedReply)
        assertEquals(TARGET_UID, fake.confirmationUid)
        assertEquals(TARGET_PID, fake.confirmationPid)
        assertEquals(REQUEST_CODE, fake.confirmationRequestCode)
        val data = fake.confirmationData
        assertNotNull(data)
        assertTrue(data!!.getBoolean(REQUEST_PERMISSION_REPLY_ALLOWED))
        assertFalse(data.getBoolean(REQUEST_PERMISSION_REPLY_IS_ONETIME))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val TOKEN = "user-service-token"
        const val SECONTEXT = "u:r:shell:s0"
        const val SERVER_UID = 2000
        const val PATCH_VERSION = 6
        const val REQUEST_CODE = 11
        const val TARGET_UID = 1010200
        const val TARGET_PID = 45678
    }
}
