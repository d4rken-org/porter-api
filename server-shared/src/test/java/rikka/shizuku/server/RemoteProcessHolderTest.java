package rikka.shizuku.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.IBinder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import rikka.shizuku.server.api.RemoteProcessHolder;

/** The process handle handed to a client by {@code newProcess}. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class RemoteProcessHolderTest {

    @Test
    public void aliveIsExitValueInverted() throws Exception {
        Process process = mock(Process.class);
        when(process.exitValue()).thenThrow(new IllegalThreadStateException());
        RemoteProcessHolder holder = new RemoteProcessHolder(process, null);

        assertTrue(holder.alive());

        doReturn(0).when(process).exitValue();

        assertFalse(holder.alive());
    }

    @Test
    public void waitForTimeoutParsesTheUnitName() throws Exception {
        Process process = mock(Process.class);
        when(process.exitValue())
                .thenThrow(new IllegalThreadStateException())
                .thenThrow(new IllegalThreadStateException())
                .thenReturn(0);
        RemoteProcessHolder holder = new RemoteProcessHolder(process, null);

        assertTrue(holder.waitForTimeout(5000, "MILLISECONDS"));
        assertThrows(IllegalArgumentException.class, () -> holder.waitForTimeout(1, "furlongs"));
    }

    @Test
    public void waitForTranslatesInterruption() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor()).thenThrow(new InterruptedException());
        RemoteProcessHolder holder = new RemoteProcessHolder(process, null);

        assertThrows(IllegalStateException.class, holder::waitFor);
    }

    @Test
    public void ownerDeathDestroysALiveProcess() throws Exception {
        Process process = mock(Process.class);
        when(process.exitValue()).thenThrow(new IllegalThreadStateException());
        IBinder owner = mock(IBinder.class);
        new RemoteProcessHolder(process, owner);

        ArgumentCaptor<IBinder.DeathRecipient> recipient =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        verify(owner).linkToDeath(recipient.capture(), eq(0));

        recipient.getValue().binderDied();
        verify(process).destroy();

        doReturn(0).when(process).exitValue();
        recipient.getValue().binderDied();
        verify(process, times(1)).destroy();
    }

    @Test
    public void outputAndInputStreamsAreMemoisedAndErrorIsNot() {
        Process process = mock(Process.class);
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        RemoteProcessHolder holder = new RemoteProcessHolder(process, null);

        assertSame(holder.getOutputStream(), holder.getOutputStream());
        assertSame(holder.getInputStream(), holder.getInputStream());
        assertNotSame(holder.getErrorStream(), holder.getErrorStream());
    }
}
