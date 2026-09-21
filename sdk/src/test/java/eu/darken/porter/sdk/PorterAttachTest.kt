package eu.darken.porter.sdk

import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.CAPABILITIES_NONE
import eu.darken.porter.protocol.PorterProtocol.REPLY_CAPABILITIES
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_SECONTEXT
import eu.darken.porter.protocol.PorterProtocol.REPLY_SERVER_UID
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBinder

/** What `onBinderReceived` sends, what it believes of the reply and what it forgets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class PorterAttachTest {

    private val recorder = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun teardown() {
        recorder.cancel()
        Porter.resetForTest()
    }

    /** Every value the connection flow publishes from now on, the current one first. */
    private fun published(): List<PorterConnection?> {
        val seen = ArrayList<PorterConnection?>()
        recorder.launch { Porter.connection.collect { seen.add(it) } }
        return seen
    }

    private fun current(): PorterConnection = checkNotNull(Porter.connection.value)

    @Test
    fun attachSendsThePackageAndVersionAndReadsTheReply() {
        val fake = attached()

        assertEquals(PACKAGE, fake.attachArgs!!.getString(ATTACH_PACKAGE_NAME))
        assertEquals(PorterProtocol.VERSION, fake.attachArgs!!.getInt(ATTACH_PROTOCOL_VERSION))

        val connection = current()
        assertTrue(connection.isAlive)
        assertEquals(SERVER_UID, connection.uid)
        val server = connection.serverInfo
        assertEquals(PorterBackend.PORTER, server.backend)
        assertEquals(1, server.version)
        assertNull("the Porter protocol has no patch level", server.patchVersion)
        assertEquals(CONTEXT, connection.seLinuxContext)
        assertEquals(PermissionState.Granted, connection.checkPermission())
    }

    @Test
    fun aSparseReplyLeavesNoStaleServerState() {
        attached()

        val sparse = FakePorterService()
        Porter.onBinderReceived(sparse, PACKAGE)

        val connection = current()
        assertEquals(0, connection.serverInfo.version)
        assertEquals(-1, connection.uid)
        assertNull(connection.seLinuxContext)
        assertEquals(PermissionState.Denied(shouldShowRationale = false), connection.checkPermission())
        assertEquals("the grant was asked for, not remembered", 1, sparse.selfPermissionQueries)
    }

    @Test
    fun theReceivedListenersFireAndAStickyOneCatchesUp() {
        val seen = published()
        assertEquals(listOf(null), seen)

        val fake = attached()

        assertEquals(2, seen.size)
        assertSame(fake, seen[1]!!.binder)
        // Whoever asks afterwards reads the connection straight from the flow.
        assertSame(seen[1], Porter.connection.value)
    }

    @Test
    fun anAttachThatIsRefusedLeavesNoConnection() {
        val fake = FakePorterService()
        fake.attachFailure = SecurityException("not an attached client")
        val seen = published()

        Porter.onBinderReceived(fake, PACKAGE)

        assertNull(Porter.connection.value)
        assertEquals(listOf(null), seen)
    }

    @Test
    fun redeliveringTheSameBinderKeepsTheGrantAndANewOneDropsIt() {
        val fake = attached()
        assertEquals(PermissionState.Granted, current().checkPermission())

        Porter.onBinderReceived(fake, PACKAGE)

        assertEquals(1, fake.attachCount)
        assertEquals(PermissionState.Granted, current().checkPermission())

        Porter.onBinderReceived(FakePorterService(), PACKAGE)

        assertEquals(PermissionState.Denied(shouldShowRationale = false), current().checkPermission())
    }

    @Test
    fun binderDeathFiresTheDeadListenersAndDropsTheGrant() {
        val seen = published()
        val fake = attached()
        val connection = current()

        val shadow: ShadowBinder = Shadow.extract(fake)
        val recipients: List<IBinder.DeathRecipient> = shadow.deathRecipients
        assertEquals(1, recipients.size)
        recipients[0].binderDied()

        assertEquals(listOf(null, connection, null), seen)
        assertNull(Porter.connection.value)
    }

    @Test
    fun aPermissionResultReachesTheListener() = runTest {
        val fake = attached()
        val connection = current()

        val result = async(start = CoroutineStart.UNDISPATCHED) { connection.requestPermission() }
        assertTrue("the request has to reach the server", fake.requestedPermissionCode > 0)
        fake.pushRequestPermissionResult(fake.requestedPermissionCode, allowed = true)

        assertEquals(PermissionState.Granted, result.await())
        assertEquals(PermissionState.Granted, connection.permission.value)
    }

    @Test
    fun aPausedGrantReplacesTheCachedAnswer() {
        val fake = attached()
        val connection = current()
        assertEquals(PermissionState.Granted, connection.checkPermission())

        fake.application!!.dispatchPermissionStateChanged(permissionState(false, true))

        assertEquals(PermissionState.Denied(shouldShowRationale = true), connection.permission.value)
        assertEquals(PermissionState.Denied(shouldShowRationale = true), connection.checkPermission())
    }

    @Test
    fun aPauseArrivingDuringAttachOutlivesTheReply() {
        val fake = FakePorterService()
        fake.attachReply = fullReply()
        fake.attachTimePermissionPush = permissionState(false, true)

        Porter.onBinderReceived(fake, PACKAGE)

        val connection = current()
        assertEquals(PermissionState.Denied(shouldShowRationale = true), connection.checkPermission())
        assertEquals("only the permission keys lose to a push", SERVER_UID, connection.uid)
    }

    @Test
    fun aGrantArrivingDuringACheckOutlivesTheAnswer() {
        val fake = FakePorterService()
        Porter.onBinderReceived(fake, PACKAGE)
        fake.selfPermission = false
        fake.checkTimePermissionPush = permissionState(true, false)

        assertEquals(PermissionState.Granted, current().checkPermission())
    }

    @Test
    fun aResumedGrantComesBackThroughTheSameChannel() {
        val fake = attached()
        val connection = current()
        fake.application!!.dispatchPermissionStateChanged(permissionState(false, true))
        assertEquals(PermissionState.Denied(shouldShowRationale = true), connection.checkPermission())

        fake.application!!.dispatchPermissionStateChanged(permissionState(true, false))

        assertEquals(PermissionState.Granted, connection.checkPermission())
        assertFalse(connection.permission.value is PermissionState.Denied)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val CONTEXT = "u:r:shell:s0"
        const val SERVER_UID = 2000

        fun fullReply(): Bundle = Bundle().apply {
            putInt(REPLY_PROTOCOL_VERSION, 1)
            putInt(REPLY_SERVER_UID, SERVER_UID)
            putString(REPLY_SERVER_SECONTEXT, CONTEXT)
            putBoolean(REPLY_PERMISSION_GRANTED, true)
            putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, false)
            putLong(REPLY_CAPABILITIES, CAPABILITIES_NONE)
        }

        fun attached(): FakePorterService {
            val fake = FakePorterService()
            fake.attachReply = fullReply()
            Porter.onBinderReceived(fake, PACKAGE)
            return fake
        }

        fun permissionState(granted: Boolean, shouldShowRationale: Boolean): Bundle = Bundle().apply {
            putBoolean(REPLY_PERMISSION_GRANTED, granted)
            putBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, shouldShowRationale)
        }
    }
}
