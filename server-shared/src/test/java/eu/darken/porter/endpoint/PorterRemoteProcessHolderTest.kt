package eu.darken.porter.endpoint

import android.os.IBinder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
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

/** The process handle handed to a Porter client by `newProcess`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PorterRemoteProcessHolderTest {

    @Test
    @Throws(Exception::class)
    fun aliveIsExitValueInverted() {
        val process = mock(Process::class.java)
        `when`(process.exitValue()).thenThrow(IllegalThreadStateException())
        val holder = PorterRemoteProcessHolder(process, null)

        assertTrue(holder.alive())

        doReturn(0).`when`(process).exitValue()

        assertFalse(holder.alive())
    }

    @Test
    @Throws(Exception::class)
    fun waitForTimeoutParsesTheUnitName() {
        val process = mock(Process::class.java)
        `when`(process.exitValue())
            .thenThrow(IllegalThreadStateException())
            .thenThrow(IllegalThreadStateException())
            .thenReturn(0)
        val holder = PorterRemoteProcessHolder(process, null)

        assertTrue(holder.waitForTimeout(5, "SECONDS"))
        assertThrows(IllegalArgumentException::class.java) { holder.waitForTimeout(1, "furlongs") }
    }

    @Test
    @Throws(Exception::class)
    fun waitForTranslatesInterruption() {
        val process = mock(Process::class.java)
        `when`(process.waitFor()).thenThrow(InterruptedException())
        val holder = PorterRemoteProcessHolder(process, null)

        assertThrows(IllegalStateException::class.java) { holder.waitFor() }
    }

    @Test
    @Throws(Exception::class)
    fun exitValueAndDestroyReachTheProcess() {
        val process = mock(Process::class.java)
        `when`(process.exitValue()).thenReturn(3)
        val holder = PorterRemoteProcessHolder(process, null)

        assertEquals(3, holder.exitValue())

        holder.destroy()

        verify(process).destroy()
    }

    @Test
    @Throws(Exception::class)
    fun ownerDeathDestroysALiveProcess() {
        val process = mock(Process::class.java)
        `when`(process.exitValue()).thenThrow(IllegalThreadStateException())
        val owner = mock(IBinder::class.java)
        PorterRemoteProcessHolder(process, owner)

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
        val holder = PorterRemoteProcessHolder(process, null)

        assertSame(holder.outputStream, holder.outputStream)
        assertSame(holder.inputStream, holder.inputStream)
        assertNotSame(holder.errorStream, holder.errorStream)
    }
}
