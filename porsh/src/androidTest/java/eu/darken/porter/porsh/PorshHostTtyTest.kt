package eu.darken.porter.porsh

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PorshHostTtyTest {

    /**
     * Shizuku's rish, run as `rish -c cmd | grep x` from a terminal, marks stderr as a tty and sends
     * no stderr pipe. The child's stderr then lands on the host's pty, which has to be drained or a
     * megabyte of it blocks the child long before `printf done`.
     */
    @Test
    fun ttyStderrWithoutTtyStdout_doesNotBlockTheChild() {
        System.loadLibrary("porsh")
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val script = "dd if=/dev/zero bs=4096 count=256 >&2 2>/dev/null; printf done"
        val host = PorshHost(
            arrayOf("-c", script), null, null, PorshConstants.ATTY_ERR.toByte(),
            stdin[0], stdout[1], null,
        )

        host.start()
        stdin[1].close()
        try {
            assertEquals(0, host.awaitExitCode(10_000))
        } finally {
            if (!host.hasExited()) Os.kill(-host.pid, OsConstants.SIGKILL)
        }

        val output = ParcelFileDescriptor.AutoCloseInputStream(stdout[0]).use { it.readBytes() }
        assertEquals("done", output.decodeToString())
    }

    /** The same client with stdin on the terminal too: two relays share the pty, each closes once. */
    @Test
    fun ttyStdinAndStderr_closesThePtyOnce() {
        System.loadLibrary("porsh")
        val stdin = ParcelFileDescriptor.createPipe()
        val stdout = ParcelFileDescriptor.createPipe()
        val tty = PorshConstants.ATTY_IN or PorshConstants.ATTY_ERR
        val host = PorshHost(
            arrayOf("-c", "printf err >&2; printf out; exit 3"), null, null, tty.toByte(),
            stdin[0], stdout[1], null,
        )

        host.start()
        try {
            assertEquals(3, host.awaitExitCode(10_000))
        } finally {
            if (!host.hasExited()) Os.kill(-host.pid, OsConstants.SIGKILL)
        }
        val output = ParcelFileDescriptor.AutoCloseInputStream(stdout[0]).use { it.readBytes() }
        assertEquals("out", output.decodeToString())

        // Take the numbers the session released, then end its stdin: a relay closing a number it
        // no longer owns takes one of these probes with it. New descriptors take the lowest free
        // numbers, so there are enough probes to reach past any older gaps.
        Thread.sleep(200)
        val probes = List(256) { ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY) }
        stdin[1].close()
        Thread.sleep(200)
        try {
            probes.forEach { Os.fstat(it.fileDescriptor) }
        } finally {
            probes.forEach { it.close() }
        }
    }
}
