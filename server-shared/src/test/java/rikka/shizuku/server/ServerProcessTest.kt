package rikka.shizuku.server

import android.os.IBinder
import eu.darken.porter.core.ServerProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import rikka.shizuku.server.api.RemoteProcessHolder

/** The neutral process handle, and the one thing the Shizuku holder still decides for itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ServerProcessTest {

    @Test
    fun aliveIsExitValueInverted() {
        val process = mock(Process::class.java)
        `when`(process.exitValue()).thenThrow(IllegalThreadStateException())
        val server = ServerProcess(process, null)

        assertTrue(server.alive())

        doReturn(0).`when`(process).exitValue()

        assertFalse(server.alive())
    }

    @Test
    fun waitForTimeoutPollsUntilTheProcessExits() {
        val process = mock(Process::class.java)
        `when`(process.exitValue())
            .thenThrow(IllegalThreadStateException())
            .thenThrow(IllegalThreadStateException())
            .thenReturn(0)
        val server = ServerProcess(process, null)

        assertTrue(server.waitForTimeout(5000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun waitForTranslatesInterruption() {
        val process = mock(Process::class.java)
        `when`(process.waitFor()).thenThrow(InterruptedException())
        val server = ServerProcess(process, null)

        assertThrows(IllegalStateException::class.java) { server.waitFor() }
    }

    @Test
    fun ownerDeathDestroysALiveProcess() {
        val process = mock(Process::class.java)
        `when`(process.exitValue()).thenThrow(IllegalThreadStateException())
        val owner = mock(IBinder::class.java)
        ServerProcess(process, owner)

        val recipient = ArgumentCaptor.forClass(IBinder.DeathRecipient::class.java)
        verify(owner).linkToDeath(recipient.capture(), eq(0))

        recipient.value.binderDied()
        verify(process).destroy()

        doReturn(0).`when`(process).exitValue()
        recipient.value.binderDied()
        verify(process, times(1)).destroy()
    }

    @Test
    fun outputAndInputStreamsAreMemoisedAndErrorIsNot() {
        val process = mock(Process::class.java)
        `when`(process.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(process.inputStream).thenReturn(ByteArrayInputStream(ByteArray(0)))
        `when`(process.errorStream).thenReturn(ByteArrayInputStream(ByteArray(0)))
        val server = ServerProcess(process, null)

        assertSame(server.getOutputStream(), server.getOutputStream())
        assertSame(server.getInputStream(), server.getInputStream())
        assertNotSame(server.getErrorStream(), server.getErrorStream())
    }

    @Test
    fun theHolderTranslatesTheUnitName() {
        val process = mock(Process::class.java)
        `when`(process.exitValue()).thenReturn(0)
        val holder = RemoteProcessHolder(ServerProcess(process, null))

        assertTrue(holder.waitForTimeout(5000, "MILLISECONDS"))
        assertThrows(IllegalArgumentException::class.java) { holder.waitForTimeout(1, "furlongs") }
    }
}
