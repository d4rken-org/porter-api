package eu.darken.porter.core

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import rikka.shizuku.server.util.Logger

/**
 * A process pinned by pid and start time, so that a pid the kernel has handed to someone else
 * since is never taken for it.
 *
 * ```
 * /proc/4242/stat   : 4242 (app_process) S 1 … <field 22: 918273> …
 * /proc/4242/status : Uid:	2000	2000	2000	2000
 * ```
 * captures as `HostProcess(pid = 4242, startTime = 918273, uid = 2000)`.
 */
class HostProcess internal constructor(
    val pid: Int,
    val startTime: Long,
    val uid: Int,
    private val procRoot: File,
) {

    /** Whether [pid] is still this process: same start time, same uid. */
    fun isSameProcess(): Boolean {
        val now = capture(pid, procRoot) ?: return false
        return now.startTime == startTime && now.uid == uid
    }

    /**
     * SIGKILLs the process if [pid] still names it. Between the check and the kill the pid could in
     * principle be recycled; the server has no pidfd to close that window.
     */
    fun killIfSame(): Boolean {
        if (!isSameProcess()) return false
        return try {
            Os.kill(pid, OsConstants.SIGKILL)
            LOGGER.i("killed user service host %d", pid)
            true
        } catch (e: ErrnoException) {
            LOGGER.w(e, "kill %d", pid)
            false
        }
    }

    fun sameAs(other: HostProcess): Boolean = pid == other.pid && startTime == other.startTime && uid == other.uid

    override fun toString(): String = "pid=$pid uid=$uid"

    companion object {

        private val LOGGER = Logger("HostProcess")

        fun capture(pid: Int, procRoot: File = File("/proc")): HostProcess? {
            if (pid <= 0) return null
            return try {
                val stat = File(procRoot, "$pid/stat").readText()
                // The command name is in parentheses and may itself contain spaces or parentheses.
                val fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(' ')
                // Field 22 of stat; the remainder after the name starts at field 3.
                val startTime = fields.getOrNull(22 - 3)?.toLongOrNull() ?: return null
                val uid = File(procRoot, "$pid/status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }
                    ?.substringAfter("Uid:")?.trim()?.split('\t', ' ')?.firstOrNull()?.toIntOrNull()
                    ?: return null
                HostProcess(pid, startTime, uid, procRoot)
            } catch (e: IOException) {
                null
            } catch (e: StringIndexOutOfBoundsException) {
                null
            }
        }
    }
}
