package eu.darken.porter.endpoint

import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import eu.darken.porter.protocol.PorterProtocol
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PACKAGE_NAME
import eu.darken.porter.protocol.PorterProtocol.ATTACH_PROTOCOL_VERSION
import eu.darken.porter.protocol.PorterProtocol.REPLY_PERMISSION_GRANTED
import eu.darken.porter.protocol.PorterProtocol.REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE
import eu.darken.porter.server.IPorterApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import rikka.shizuku.server.ClientManager
import rikka.shizuku.server.ConfigManager
import rikka.shizuku.server.ServerTestSupport
import rikka.shizuku.server.util.HandlerUtil

/** What a Porter client is told when the grant behind its record changes after it attached. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterEndpointPermissionStateTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>
    private lateinit var endpoint: PorterEndpoint

    @Before
    fun setup() {
        HandlerUtil.mainHandler = mock(Handler::class.java)
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
        endpoint = PorterEndpoint(
            ServerTestSupport.newCore(clients, ServerTestSupport.TestUserServiceManager(), config, ServerTestSupport.TestPolicy()) {
                listOf(PACKAGE)
            },
        )

        ShadowBinder.setCallingUid(CLIENT_UID)
        ShadowBinder.setCallingPid(CLIENT_PID)
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
    }

    @Test
    @Throws(Exception::class)
    fun anAttachedClientIsSentBothFlagsOfEveryStateChange() {
        val application = porterApplication(mock(IBinder::class.java))
        endpoint.attach(application, attachArgs(PACKAGE))

        val record = clients.findClient(CLIENT_UID, CLIENT_PID)
        assertNotNull(record)

        record!!.callback.onPermissionStateChanged(false, true)
        record.callback.onPermissionStateChanged(true, false)

        val state = ArgumentCaptor.forClass(Bundle::class.java)
        verify(application, times(2)).dispatchPermissionStateChanged(state.capture())

        val sent = state.allValues
        assertEquals(2, sent.size)

        assertFalse(sent[0].getBoolean(REPLY_PERMISSION_GRANTED))
        assertTrue(sent[0].getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE))

        assertTrue(sent[1].getBoolean(REPLY_PERMISSION_GRANTED))
        assertFalse(sent[1].getBoolean(REPLY_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"

        /** User 10, app id 10200, so the user id derived from it is not the trivial zero. */
        const val CLIENT_UID = 1010200
        const val CLIENT_PID = 45678

        fun porterApplication(binder: IBinder): IPorterApplication {
            val application = mock(IPorterApplication::class.java)
            `when`(application.asBinder()).thenReturn(binder)
            return application
        }

        fun attachArgs(packageName: String): Bundle = Bundle().apply {
            putString(ATTACH_PACKAGE_NAME, packageName)
            putInt(ATTACH_PROTOCOL_VERSION, PorterProtocol.VERSION)
        }
    }
}
