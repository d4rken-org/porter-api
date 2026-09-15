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
import java.util.concurrent.TimeUnit;

import eu.darken.porter.core.ServerProcess;
import rikka.shizuku.server.api.RemoteProcessHolder;

/** The neutral process handle, and the one thing the Shizuku holder still decides for itself. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class ServerProcessTest {

    @Test
    public void aliveIsExitValueInverted() {
        Process process = mock(Process.class);
        when(process.exitValue()).thenThrow(new IllegalThreadStateException());
        ServerProcess server = new ServerProcess(process, null);

        assertTrue(server.alive());

        doReturn(0).when(process).exitValue();

        assertFalse(server.alive());
    }

    @Test
    public void waitForTimeoutPollsUntilTheProcessExits() {
        Process process = mock(Process.class);
        when(process.exitValue())
                .thenThrow(new IllegalThreadStateException())
                .thenThrow(new IllegalThreadStateException())
                .thenReturn(0);
        ServerProcess server = new ServerProcess(process, null);

        assertTrue(server.waitForTimeout(5000, TimeUnit.MILLISECONDS));
    }

    @Test
    public void waitForTranslatesInterruption() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor()).thenThrow(new InterruptedException());
        ServerProcess server = new ServerProcess(process, null);

        assertThrows(IllegalStateException.class, server::waitFor);
    }

    @Test
    public void ownerDeathDestroysALiveProcess() throws Exception {
        Process process = mock(Process.class);
        when(process.exitValue()).thenThrow(new IllegalThreadStateException());
        IBinder owner = mock(IBinder.class);
        new ServerProcess(process, owner);

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
        ServerProcess server = new ServerProcess(process, null);

        assertSame(server.getOutputStream(), server.getOutputStream());
        assertSame(server.getInputStream(), server.getInputStream());
        assertNotSame(server.getErrorStream(), server.getErrorStream());
    }

    @Test
    public void theHolderTranslatesTheUnitName() throws Exception {
        Process process = mock(Process.class);
        when(process.exitValue()).thenReturn(0);
        RemoteProcessHolder holder = new RemoteProcessHolder(new ServerProcess(process, null));

        assertTrue(holder.waitForTimeout(5000, "MILLISECONDS"));
        assertThrows(IllegalArgumentException.class, () -> holder.waitForTimeout(1, "furlongs"));
    }
}
