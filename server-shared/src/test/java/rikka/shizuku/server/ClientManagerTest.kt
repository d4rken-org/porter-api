package rikka.shizuku.server

import android.os.IBinder
import android.os.RemoteException
import moe.shizuku.server.IShizukuApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.ServerTestSupport.application
import rikka.shizuku.server.ServerTestSupport.entry

/** What [ClientManager] records, how it seeds `allowed`, and when it forgets a record. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClientManagerTest {

    private lateinit var config: ConfigManager
    private lateinit var clients: ClientManager<ConfigManager>

    @Before
    fun setup() {
        config = mock(ConfigManager::class.java)
        clients = ClientManager(config)
    }

    private fun add(uid: Int, pid: Int, application: IShizukuApplication): ClientRecord? =
        clients.addClient(uid, pid, application, PACKAGE, 13)

    @Test
    fun addClientSeedsAllowedFromAnAllowedEntry() {
        `when`(config.find(UID)).thenReturn(entry(true, false))

        val record = add(UID, PID, application(mock(IBinder::class.java)))

        assertNotNull(record)
        assertTrue(record!!.allowed)
    }

    @Test
    fun addClientLeavesAllowedFalseForNoEntryOrDeniedEntry() {
        assertFalse(add(UID, PID, application(mock(IBinder::class.java)))!!.allowed)

        `when`(config.find(UID)).thenReturn(entry(false, true))

        assertFalse(add(UID, PID + 1, application(mock(IBinder::class.java)))!!.allowed)
    }

    @Test
    fun addClientLinksToDeathAndDeathForgetsTheRecord() {
        val binder = mock(IBinder::class.java)
        val record = add(UID, PID, application(binder))
        assertSame(record, clients.findClient(UID, PID))

        val recipient = ArgumentCaptor.forClass(IBinder.DeathRecipient::class.java)
        verify(binder).linkToDeath(recipient.capture(), eq(0))
        recipient.value.binderDied()

        assertNull(clients.findClient(UID, PID))
    }

    @Test
    fun addClientReturnsNullWhenLinkToDeathFails() {
        val binder = mock(IBinder::class.java)
        doThrow(RemoteException("dead")).`when`(binder).linkToDeath(any(IBinder.DeathRecipient::class.java), anyInt())

        assertNull(add(UID, PID, application(binder)))
        assertNull(clients.findClient(UID, PID))
    }

    @Test
    fun findClientsReturnsEveryRecordOfTheUid() {
        val first = add(UID, PID, application(mock(IBinder::class.java)))
        val second = add(UID, PID + 1, application(mock(IBinder::class.java)))
        val other = add(UID + 1, PID + 2, application(mock(IBinder::class.java)))

        assertEquals(listOf(first, second), clients.findClients(UID))
        assertEquals(listOf(other), clients.findClients(UID + 1))
    }

    @Test
    fun requireClientThrowsIllegalStateWhenAbsent() {
        assertThrows(IllegalStateException::class.java) { clients.requireClient(UID, PID) }
    }

    @Test
    fun requireClientWithPermissionThrowsSecurityWhenNotAllowed() {
        val record = add(UID, PID, application(mock(IBinder::class.java)))!!

        assertSame(record, clients.requireClient(UID, PID, false))
        assertThrows(SecurityException::class.java) { clients.requireClient(UID, PID, true) }

        record.allowed = true

        assertSame(record, clients.requireClient(UID, PID, true))
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.probe"
        const val UID = 10200
        const val PID = 45678
    }
}
