package eu.darken.porter.sdk.extras

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import eu.darken.porter.sdk.extras.internal.IPorterShellProcess
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A process the shell service started, with its three pipes taken over into this one. */
internal class RemoteShellProcess(private val remote: IPorterShellProcess) : Process() {

    private val stdin: OutputStream
    private val stdout: InputStream
    private val stderr: InputStream

    init {
        val taken = ArrayList<Closeable>(3)
        try {
            stdin = ParcelFileDescriptor.AutoCloseOutputStream(shellCall { remote.takeStdin() }).also { taken += it }
            stdout = ParcelFileDescriptor.AutoCloseInputStream(shellCall { remote.takeStdout() }).also { taken += it }
            stderr = ParcelFileDescriptor.AutoCloseInputStream(shellCall { remote.takeStderr() }).also { taken += it }
        } catch (e: Throwable) {
            taken.forEach { it.closeQuietly() }
            destroyRemote()
            throw e
        }
    }

    override fun getOutputStream(): OutputStream = stdin

    override fun getInputStream(): InputStream = stdout

    override fun getErrorStream(): InputStream = stderr

    override fun waitFor(): Int {
        // In slices, so no binder thread of the service is held for the whole run.
        while (!shellCall { remote.waitFor(WAIT_SLICE_MS) }) {
            if (Thread.interrupted()) throw InterruptedException()
        }
        return shellCall { remote.exitValue() }
    }

    override fun exitValue(): Int {
        if (shellCall { remote.alive() }) throw IllegalThreadStateException("the process has not exited")
        return shellCall { remote.exitValue() }
    }

    override fun destroy() {
        // Local first: a service that stopped answering must not keep these open.
        stdin.closeQuietly()
        stdout.closeQuietly()
        stderr.closeQuietly()
        destroyRemote()
    }

    private fun destroyRemote() {
        try {
            remote.destroy()
        } catch (e: RemoteException) {
            // Nothing is left to ask. A process whose service died without stopping it keeps running.
        }
    }

    private companion object {
        const val WAIT_SLICE_MS = 250L
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (e: java.io.IOException) {
        }
    }
}
