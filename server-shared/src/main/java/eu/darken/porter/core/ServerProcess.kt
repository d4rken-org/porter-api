package eu.darken.porter.core

import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.concurrent.TimeUnit
import rikka.shizuku.server.util.Logger
import rikka.shizuku.server.util.ParcelFileDescriptorUtil

/**
 * A process the server spawned on someone's behalf, and the pipes handed back for it.
 *
 * [getOutputStream] and [getInputStream] answer with the same descriptor every time;
 * [getErrorStream] opens a fresh pipe and a fresh transfer thread on each call, so two callers end
 * up competing for the same bytes.
 */
class ServerProcess(private val process: Process, ownerToken: IBinder?) {

    private var input: ParcelFileDescriptor? = null
    private var output: ParcelFileDescriptor? = null

    init {
        if (ownerToken != null) {
            try {
                val deathRecipient = IBinder.DeathRecipient {
                    try {
                        if (alive()) {
                            destroy()
                            LOGGER.i("destroy process because the owner is dead")
                        }
                    } catch (e: Throwable) {
                        LOGGER.w(e, "failed to destroy process")
                    }
                }
                ownerToken.linkToDeath(deathRecipient, 0)
            } catch (e: Throwable) {
                LOGGER.w(e, "linkToDeath")
            }
        }
    }

    fun getOutputStream(): ParcelFileDescriptor = output ?: try {
        ParcelFileDescriptorUtil.pipeTo(process.outputStream)
    } catch (e: IOException) {
        throw IllegalStateException(e)
    }.also { output = it }

    fun getInputStream(): ParcelFileDescriptor = input ?: try {
        ParcelFileDescriptorUtil.pipeFrom(process.inputStream)
    } catch (e: IOException) {
        throw IllegalStateException(e)
    }.also { input = it }

    fun getErrorStream(): ParcelFileDescriptor = try {
        ParcelFileDescriptorUtil.pipeFrom(process.errorStream)
    } catch (e: IOException) {
        throw IllegalStateException(e)
    }

    fun waitFor(): Int = try {
        process.waitFor()
    } catch (e: InterruptedException) {
        throw IllegalStateException(e)
    }

    fun exitValue(): Int = process.exitValue()

    fun destroy() {
        process.destroy()
    }

    fun alive(): Boolean = try {
        exitValue()
        false
    } catch (e: IllegalThreadStateException) {
        true
    }

    fun waitForTimeout(timeout: Long, unit: TimeUnit): Boolean {
        val startTime = System.nanoTime()
        var rem = unit.toNanos(timeout)

        do {
            try {
                exitValue()
                return true
            } catch (ex: IllegalThreadStateException) {
                if (rem > 0) {
                    try {
                        Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100L))
                    } catch (e: InterruptedException) {
                        throw IllegalStateException()
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime)
        } while (rem > 0)
        return false
    }

    private companion object {
        val LOGGER = Logger("ServerProcess")
    }
}
