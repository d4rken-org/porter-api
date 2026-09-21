package rikka.shizuku.server

import android.os.Bundle
import android.os.RemoteException
import moe.shizuku.server.IShizukuApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.ShizukuApiConstants

/** The permission-result callback [ClientRecord] sends, and what a failing send does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClientRecordTest {

    @Test
    fun dispatchRequestPermissionResultSendsTheAllowedKey() {
        val application = mock(IShizukuApplication::class.java)
        val record = ClientRecord(UID, PID, application, "eu.darken.porter.probe", 13)

        record.dispatchRequestPermissionResult(21, true)

        val reply = ArgumentCaptor.forClass(Bundle::class.java)
        verify(application).dispatchRequestPermissionResult(eq(21), reply.capture())
        assertEquals(setOf(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED), reply.value.keySet())
        assertTrue(reply.value.getBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED))
    }

    @Test
    fun aThrowingClientIsLoggedNotPropagated() {
        val application = mock(IShizukuApplication::class.java)
        doThrow(RemoteException("dead"))
            .`when`(application).dispatchRequestPermissionResult(anyInt(), any(Bundle::class.java))
        val record = ClientRecord(UID, PID, application, "eu.darken.porter.probe", 13)

        record.dispatchRequestPermissionResult(23, false)

        verify(application).dispatchRequestPermissionResult(eq(23), any(Bundle::class.java))
    }

    private companion object {
        const val UID = 10200
        const val PID = 45678
    }
}
