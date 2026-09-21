package rikka.shizuku.server

import android.os.IBinder
import eu.darken.porter.core.CallerIdentity
import eu.darken.porter.core.ClientCallback
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.server.ServerTestSupport.application
import rikka.shizuku.server.ServerTestSupport.entry
import rikka.shizuku.server.legacy.LegacyClientCallback

/** The neutral attach entry point of [ClientManager]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClientManagerAttachTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>

    @Before
    fun setup() {
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        // Whatever Binder would report is a different caller, so a fallback to it would show up.
        ShadowBinder.setCallingUid(HANDED_UID + 1)
        ShadowBinder.setCallingPid(HANDED_PID + 1)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    private fun callback(binder: IBinder): ClientCallback = object : ClientCallback {
        override fun asBinder(): IBinder = binder

        override fun onPermissionResult(requestCode: Int, allowed: Boolean) {
        }

        override fun onPermissionStateChanged(granted: Boolean, shouldShowRationale: Boolean) {
        }
    }

    @Test
    fun attachUsesTheHandedIdentityAndCallback() {
        `when`(config.find(HANDED_UID)).thenReturn(entry(true, false))
        val binder = mock(IBinder::class.java)
        val callback = callback(binder)

        val record = clients.attach(CallerIdentity(HANDED_UID, HANDED_PID), callback, PACKAGE, 13)

        assertNotNull(record)
        assertSame(record, clients.findClient(HANDED_UID, HANDED_PID))
        assertNull(clients.findClient(HANDED_UID + 1, HANDED_PID + 1))
        assertNull(record!!.client)
        assertSame(callback, record.callback)
        assertTrue(record.allowed)

        val recipient = ArgumentCaptor.forClass(IBinder.DeathRecipient::class.java)
        verify(binder).linkToDeath(recipient.capture(), eq(0))
        recipient.value.binderDied()

        assertNull(clients.findClient(HANDED_UID, HANDED_PID))
    }

    @Test
    fun attachThroughTheLegacyOverloadKeepsTheApplication() {
        val application = application(mock(IBinder::class.java))

        val record = clients.addClient(HANDED_UID, HANDED_PID, application, PACKAGE, 13)

        assertNotNull(record)
        assertSame(application, record!!.client)
        assertSame(application, (record.callback as LegacyClientCallback).application)
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val HANDED_UID = 10200
        const val HANDED_PID = 45678
    }
}
