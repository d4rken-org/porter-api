package eu.darken.porter.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostProcessTest {

    @get:Rule
    val proc = TemporaryFolder()

    private fun process(pid: Int, name: String, startTime: Long, uid: Int) {
        val dir = File(proc.root, "$pid").apply { mkdirs() }
        // Fields 3 to 21 are filler; field 22 is the start time.
        val filler = (3..21).joinToString(" ") { if (it == 3) "S" else "0" }
        File(dir, "stat").writeText("$pid ($name) $filler $startTime 0 0\n")
        File(dir, "status").writeText("Name:\t$name\nUid:\t$uid\t$uid\t$uid\t$uid\nGid:\t$uid\t$uid\t$uid\t$uid\n")
    }

    @Test
    fun capturesStartTimeAndRealUid() {
        process(4242, "app_process", 918273, 2000)

        val host = HostProcess.capture(4242, proc.root)!!

        assertEquals(4242, host.pid)
        assertEquals(918273L, host.startTime)
        assertEquals(2000, host.uid)
    }

    @Test
    fun aNameWithSpacesAndParenthesesDoesNotShiftTheFields() {
        process(4242, "a) b (c", 918273, 2000)

        assertEquals(918273L, HostProcess.capture(4242, proc.root)!!.startTime)
    }

    @Test
    fun aRecycledPidIsNotTheSameProcess() {
        process(4242, "app_process", 918273, 2000)
        val host = HostProcess.capture(4242, proc.root)!!
        assertTrue(host.isSameProcess())

        process(4242, "app_process", 918999, 2000)

        assertFalse(host.isSameProcess())
        assertFalse(host.killIfSame())
    }

    @Test
    fun aGoneProcessIsNeitherCapturedNorTheSame() {
        process(4242, "app_process", 918273, 2000)
        val host = HostProcess.capture(4242, proc.root)!!
        File(proc.root, "4242").deleteRecursively()

        assertFalse(host.isSameProcess())
        assertNull(HostProcess.capture(4242, proc.root))
        assertNull(HostProcess.capture(0, proc.root))
    }
}
