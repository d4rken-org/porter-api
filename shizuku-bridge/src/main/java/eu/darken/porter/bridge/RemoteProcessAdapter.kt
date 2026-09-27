package eu.darken.porter.bridge

import android.os.ParcelFileDescriptor
import eu.darken.porter.sdk.extras.PorterShellProcess
import moe.shizuku.server.IRemoteProcess
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A Porter shell process as Shizuku's `IRemoteProcess`, which hands out descriptors where the
 * shell process has streams: copies of the descriptors behind those streams.
 *
 * Upstream asks for stdin and stdout once and keeps them, so the caller gets this side's only copy,
 * and closing stdin is what ends the command's input. It asks for stderr on every access, so every
 * caller gets a copy of the same pipe, and this side keeps one until [destroy]. Upstream keeps this
 * object for the life of the process: stdin nobody took is closed once the command is seen to exit.
 */
internal class RemoteProcessAdapter(private val process: PorterShellProcess) : IRemoteProcess.Stub() {

    private var stdin: FileOutputStream?
    private var stdout: FileInputStream?
    private val stderr: FileInputStream

    init {
        try {
            stdin = process.outputStream as FileOutputStream
            stdout = process.inputStream as FileInputStream
            stderr = process.errorStream as FileInputStream
        } catch (e: ClassCastException) {
            process.destroy()
            throw IllegalStateException("the shell process streams are not file streams", e)
        }
    }

    @Synchronized
    override fun getOutputStream(): ParcelFileDescriptor = asShizuku {
        val stream = stdin ?: throw IllegalStateException("stdin was already handed over, or the command exited")
        ParcelFileDescriptor.dup(stream.fd).also {
            stdin = null
            stream.closeQuietly()
        }
    }

    @Synchronized
    override fun getInputStream(): ParcelFileDescriptor = asShizuku {
        val stream = stdout ?: throw IllegalStateException("stdout was already handed over")
        ParcelFileDescriptor.dup(stream.fd).also {
            stdout = null
            stream.closeQuietly()
        }
    }

    override fun getErrorStream(): ParcelFileDescriptor = asShizuku { ParcelFileDescriptor.dup(stderr.fd) }

    override fun waitFor(): Int = asShizuku { process.waitFor() }.also { exited() }

    override fun exitValue(): Int = asShizuku { process.exitValue() }.also { exited() }

    /** Pipes the caller took stay open until the caller closes them. */
    override fun destroy() {
        asShizuku { process.destroy() }
    }

    override fun alive(): Boolean = try {
        exitValue()
        false
    } catch (e: IllegalThreadStateException) {
        true
    }

    override fun waitForTimeout(timeout: Long, unitName: String?): Boolean {
        var remaining = TimeUnit.valueOf(unitName ?: "MILLISECONDS").toNanos(timeout)
        var last = System.nanoTime()
        while (alive()) {
            if (remaining <= 0) return false
            Thread.sleep(minOf(POLL_MS, TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1)))
            val now = System.nanoTime()
            remaining -= now - last
            last = now
        }
        return true
    }

    /** Nothing reads stdin any more. */
    @Synchronized
    private fun exited() {
        stdin?.closeQuietly()
        stdin = null
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
        }
    }

    private companion object {
        const val POLL_MS = 50L
    }
}
