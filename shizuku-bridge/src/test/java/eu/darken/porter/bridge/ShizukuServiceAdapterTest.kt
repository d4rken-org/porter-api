package eu.darken.porter.bridge

import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import eu.darken.porter.sdk.Porter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLooper
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuRemoteProcess
import rikka.shizuku.ShizukuSystemProperties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Upstream's unchanged client, reaching a fake Porter server through the adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
internal class ShizukuServiceAdapterTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: FakePorterServer
    private lateinit var adapter: ShizukuServiceAdapter

    /** Every command a test started, destroyed after it whatever the test left. */
    private val started = java.util.Collections.synchronizedList(ArrayList<() -> Unit>())

    @Before
    fun setup() {
        server = FakePorterServer()
        Porter.onBinderReceived(server, PACKAGE)
        adapter = ShizukuServiceAdapter(Porter.connection.value!!, scope)
        adapter.publish()
        settleUpstream()
    }

    @After
    fun teardown() {
        ShadowBinder.reset()
        started.toList().forEach { runCatching(it) }
        adapter.retire()
        settleUpstream()
        Shizuku.onBinderReceived(null, null)
        ShadowLooper.shadowMainLooper().idle()
        Porter.resetForTest()
        scope.cancel()
    }

    @Test
    fun attachReportsShizukuApiLevelRatherThanPorterProtocolVersion() {
        assertEquals(13, Shizuku.getVersion())
        assertFalse(Shizuku.isPreV11())
        assertEquals(2000, Shizuku.getUid())
        assertEquals(PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())
    }

    @Test
    fun forwardHandsOnOnlyTheCallersParcel() {
        val target = Binder()
        val carried = Binder()
        var seen: String? = null
        server.onForward = { forwardedTarget, code, flags, payload, reply ->
            assertSame(target, forwardedTarget)
            assertEquals(7, code)
            assertEquals(0, flags)
            payload.enforceInterface("test.Target")
            assertEquals(42, payload.readInt())
            assertSame(carried, payload.readStrongBinder())
            seen = payload.readString()
            assertEquals(0, payload.dataAvail())
            reply!!.writeInt(99)
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("test.Target")
            data.writeInt(42)
            data.writeStrongBinder(carried)
            data.writeString("payload")
            ShizukuBinderWrapper(target).transact(7, data, reply, 0)

            assertEquals("payload", seen)
            // The target's reply as written, with no header of the forwarding added to it.
            assertEquals(4, reply.dataSize())
            assertEquals(99, reply.readInt())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun forwardKeepsTheCallersFlags() {
        var flags = -1
        server.onForward = { _, _, forwardedFlags, _, _ -> flags = forwardedFlags }

        val data = Parcel.obtain()
        try {
            ShizukuBinderWrapper(Binder()).transact(1, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }

        assertEquals(IBinder.FLAG_ONEWAY, flags)
    }

    @Test
    fun refusedForwardArrivesAsThePlatformsSecurityException() {
        server.forwardFailure = SecurityException("not granted")

        // What an AIDL proxy does with a forwarded call: transact, then read the reply's exception.
        // The refusal has to surface as the platform's type by one of the two.
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            ShizukuBinderWrapper(Binder()).transact(1, data, reply, 0)
            reply.readException()
            fail("the forward was refused")
        } catch (e: SecurityException) {
            assertEquals(SecurityException::class.java, e.javaClass)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun refusedCallArrivesAsThePlatformsSecurityException() {
        server.propertyFailure = SecurityException("not granted")

        try {
            ShizukuSystemProperties.get("ro.build.version.sdk", "?")
            fail("the read was refused")
        } catch (e: SecurityException) {
            assertEquals(SecurityException::class.java, e.javaClass)
        }
    }

    @Test
    fun deadServerArrivesAsARemoteException() {
        server.propertyFailure = DeadObjectException()

        try {
            ShizukuSystemProperties.get("ro.build.version.sdk", "?")
            fail("the server was gone")
        } catch (e: RemoteException) {
            // The server's own exception, not the SDK's wrapper around it.
            assertEquals(DeadObjectException::class.java, e.javaClass)
        }
    }

    @Test
    fun aServersOwnFailureArrivesAsItself() {
        server.propertyFailure = IllegalStateException("the server could not read it")

        try {
            ShizukuSystemProperties.get("ro.build.version.sdk", "?")
            fail("the read failed on the server")
        } catch (e: IllegalStateException) {
            assertEquals("the server could not read it", e.message)
        }
    }

    @Test
    fun retireLooksToUpstreamLikeTheServerDied() {
        var dead = 0
        val deadListener = Shizuku.OnBinderDeadListener { dead++ }
        Shizuku.addBinderDeadListener(deadListener)
        try {
            adapter.retire()
            settleUpstream()

            assertEquals(1, dead)
            assertFalse(Shizuku.pingBinder())
            // Upstream's own death recipient clears its readiness; a sticky listener added now must
            // wait for a binder instead of hearing about the dead one.
            var fired = false
            val sticky = Shizuku.OnBinderReceivedListener { fired = true }
            Shizuku.addBinderReceivedListenerSticky(sticky)
            ShadowLooper.shadowMainLooper().idle()
            assertFalse(fired)
            Shizuku.removeBinderReceivedListener(sticky)
        } finally {
            Shizuku.removeBinderDeadListener(deadListener)
        }
    }

    @Test
    fun processStreamsAreHandedOverOnce() {
        val process = offMain { tracked(adapter.newProcess(arrayOf("sh", "-c", "exit 3"), null, null)) }

        process.outputStream.close()
        try {
            process.outputStream
            fail("stdin was handed over twice")
        } catch (e: IllegalStateException) {
            // The caller holds the only copy, so closing it ends the command's input.
        }
        assertEquals(3, offMain { process.waitFor() })
    }

    @Test
    fun anEnvironmentReplacesTheInheritedOne() {
        // As Runtime.exec does on a Shizuku server: HOME is gone, X is there.
        val process = offMain {
            tracked(adapter.newProcess(arrayOf("sh", "-c", "[ -z \"\$HOME\" ] && exit \$X"), arrayOf("X=5"), null))
        }

        assertEquals(5, offMain { process.waitFor() })
    }

    @Test
    fun newProcessWithoutAGrantIsRefusedAsSecurityException() {
        server.refuseBinds = true

        try {
            offMain { adapter.newProcess(arrayOf("true"), null, null) }
            fail("the shell service bind was refused")
        } catch (e: SecurityException) {
            assertEquals(SecurityException::class.java, e.javaClass)
        }
    }

    @Test
    fun callsAfterRetireFailAsADeadBinderWould() {
        adapter.retire()
        settleUpstream()

        val calls = listOf<Pair<String, () -> Unit>>(
            "getUid" to { adapter.getUid() },
            "getSystemProperty" to { adapter.getSystemProperty("ro.build.version.sdk", null) },
            "newProcess" to { adapter.newProcess(arrayOf("true"), null, null) },
            "linkToDeath" to { adapter.linkToDeath({}, 0) },
        )
        for ((name, call) in calls) {
            try {
                call()
                fail("$name answered after retire")
            } catch (e: DeadObjectException) {
            }
        }
    }

    @Test
    fun retireTellsEachRecipientOnce() {
        var died = 0
        adapter.linkToDeath({ died++ }, 0)

        adapter.retire()
        adapter.retire()
        settleUpstream()

        assertEquals(1, died)
    }

    @Test
    fun aPermissionAnswerReachesUpstreamWithItsRequestCode() {
        val results = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
        val listener = Shizuku.OnRequestPermissionResultListener { code, result -> results += code to result }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(7)
            until { server.requestedCode != null }
            server.answerPermissionRequest(allowed = true)
            until { results.isNotEmpty() }

            assertEquals(listOf(7 to PackageManager.PERMISSION_GRANTED), results.toList())
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    @Test
    fun aPermissionAnswerAfterRetireReachesNoOne() {
        assertEquals(emptyList<Pair<Int, Int>>(), permissionResults {
            Shizuku.requestPermission(7)
            until { server.requestedCode != null }
            adapter.retire()
            server.answerPermissionRequest(allowed = true)
        })
    }

    @Test
    fun stderrCanBeAskedForAgain() {
        // Upstream asks the adapter again on every getErrorStream().
        val process = offMain { tracked(adapter.newProcess(arrayOf("sh", "-c", "echo err >&2"), null, null)) }
        assertEquals(0, offMain { process.waitFor() })

        val first = process.errorStream
        val second = process.errorStream
        first.close()

        assertEquals("err\n", ParcelFileDescriptor.AutoCloseInputStream(second).use { String(it.readBytes()) })
    }

    @Test
    fun outputNobodyAskedForIsThereAfterTheCommandExited() {
        val process = offMain { shizukuProcess("sh", "-c", "echo out; echo err >&2") }

        assertEquals(0, offMain { process.waitFor() })

        assertEquals("out\n", process.inputStream.use { String(it.readBytes()) })
        assertEquals("err\n", process.errorStream.use { String(it.readBytes()) })
    }

    @Test
    fun anEmptyCommandIsRefused() {
        // Otherwise the environment prefix alone would run.
        try {
            adapter.newProcess(emptyArray(), arrayOf("X=1"), null)
            fail("an empty command ran")
        } catch (e: IllegalArgumentException) {
        }
    }

    @Test
    fun aCallFromAnotherProcessIsRefused() {
        // Upstream's provider hands the current binder to whoever may call it.
        ShadowBinder.setCallingPid(Process.myPid() + 1)

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            ShizukuBinderWrapper(Binder()).transact(1, data, reply, 0)
            reply.readException()
            fail("another process used this app's grant")
        } catch (e: SecurityException) {
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun aForwardAfterUpstreamTookAnOlderServerIsRefused() {
        var forwarded = false
        server.onForward = { _, _, _, _, _ -> forwarded = true }
        // What upstream does when a server before API 11 attached, even after this adapter was published.
        val preV11 = Shizuku::class.java.getDeclaredField("preV11").apply { isAccessible = true }
        preV11.setBoolean(null, true)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            ShizukuBinderWrapper(Binder()).transact(1, data, reply, 0)
            reply.readException()
            fail("a parcel in the older layout was forwarded")
        } catch (e: IllegalStateException) {
            assertFalse(forwarded)
        } finally {
            // Upstream never leaves this mode by itself, and every later test shares it.
            preV11.setBoolean(null, false)
            data.recycle()
            reply.recycle()
        }
    }

    @Test
    fun aDeniedRequestReachesUpstreamAsDenied() {
        assertEquals(listOf(7 to PackageManager.PERMISSION_DENIED), permissionResults {
            Shizuku.requestPermission(7)
            until { server.requestedCode != null }
            server.answerPermissionRequest(allowed = false)
        })
    }

    @Test
    fun aRequestTheServerRefusesReachesUpstreamAsDenied() {
        server.permissionFailure = SecurityException("no dialog")

        assertEquals(listOf(7 to PackageManager.PERMISSION_DENIED), permissionResults {
            Shizuku.requestPermission(7)
        })
    }

    @Test
    fun aRequestWhoseConnectionIsReplacedReachesNoOne() {
        // As with a Shizuku server that died with its dialog up: the caller hears nothing.
        assertEquals(emptyList<Pair<Int, Int>>(), permissionResults {
            Shizuku.requestPermission(7)
            until { server.requestedCode != null }
            Porter.onBinderReceived(FakePorterServer(), PACKAGE)
        })
    }

    @Test
    fun aPublishThatLosesTheRaceWithRetirementStillEndsInADeath() {
        var dead = 0
        val deadListener = Shizuku.OnBinderDeadListener { dead++ }
        Shizuku.addBinderDeadListener(deadListener)
        val fresh = ShizukuServiceAdapter(Porter.connection.value!!, scope)
        val gate = CountDownLatch(1)
        ShizukuServiceAdapter.UPSTREAM.execute { gate.await() }
        try {
            // A publish that saw the adapter alive, then reached upstream after it retired.
            fresh.retire()
            Shizuku.onBinderReceived(fresh, PACKAGE)
            gate.countDown()
            settleUpstream()

            assertEquals(1, dead)
            assertFalse(Shizuku.pingBinder())
        } finally {
            gate.countDown()
            Shizuku.removeBinderDeadListener(deadListener)
        }
    }

    @Test
    fun listenersThatCallBackIntoTheBridgeDoNotStallIt() {
        // An adapter whose work runs on the main thread, where upstream runs its listeners.
        adapter.retire()
        settleUpstream()
        val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val onMain = ShizukuServiceAdapter(Porter.connection.value!!, main)
        var received = false
        var answered = false
        var dead = false
        val receivedListener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.getUid()
                Shizuku.removeBinderReceivedListener(this)
                received = true
            }
        }
        val resultListener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                Shizuku.checkSelfPermission()
                Shizuku.removeRequestPermissionResultListener(this)
                answered = true
            }
        }
        val deadListener = object : Shizuku.OnBinderDeadListener {
            override fun onBinderDead() {
                onMain.pingBinder()
                Shizuku.removeBinderDeadListener(this)
                dead = true
            }
        }
        Shizuku.addBinderReceivedListener(receivedListener)
        Shizuku.addRequestPermissionResultListener(resultListener)
        Shizuku.addBinderDeadListener(deadListener)
        try {
            onMain.publish()
            until { received }

            Shizuku.requestPermission(7)
            until { server.requestedCode != null }
            server.answerPermissionRequest(allowed = true)
            until { answered }

            onMain.retire()
            until { dead }
        } finally {
            Shizuku.removeBinderReceivedListener(receivedListener)
            Shizuku.removeRequestPermissionResultListener(resultListener)
            Shizuku.removeBinderDeadListener(deadListener)
            onMain.retire()
            main.cancel()
        }
    }

    @Test
    fun aRunningCommandIsAliveUntilItIsDestroyed() {
        val process = offMain { tracked(adapter.newProcess(arrayOf("sleep", "30"), null, null)) }

        assertTrue(process.alive())
        try {
            process.exitValue()
            fail("a running command had an exit value")
        } catch (e: IllegalThreadStateException) {
        }
        assertFalse(offMain { process.waitForTimeout(50, "MILLISECONDS") })

        process.destroy()

        assertTrue(offMain { process.waitForTimeout(5, "SECONDS") })
        assertFalse(process.alive())
    }

    @Test
    fun theLongestTimeoutWaitsForTheCommand() {
        val gate = java.io.File.createTempFile("porter-bridge", ".gate").apply { delete() }
        val process = offMain {
            tracked(adapter.newProcess(arrayOf("sh", "-c", "while [ ! -e '${gate.path}' ]; do sleep 0.05; done"), null, null))
        }
        val result = AtomicReference<Boolean?>()
        val waiter = Thread { result.set(process.waitForTimeout(Long.MAX_VALUE, "MILLISECONDS")) }
        try {
            waiter.start()
            // The command runs until the gate exists, so a wait that gave up has returned by now.
            waiter.join(500)
            assertTrue("the wait gave up on a running command", waiter.isAlive)

            gate.createNewFile()
            waiter.join(5_000)

            assertEquals(true, result.get())
        } finally {
            gate.createNewFile()
            process.destroy()
            waiter.join(5_000)
            gate.delete()
        }
    }

    @Test
    fun anExitedCommandReportsItsExitValue() {
        val process = offMain { tracked(adapter.newProcess(arrayOf("sh", "-c", "exit 4"), null, null)) }

        assertEquals(4, offMain { process.waitFor() })
        assertEquals(4, process.exitValue())
    }

    @Test
    fun destroyEndsACommandWhoseStreamsTheCallerStillHolds() {
        val process = offMain { shizukuProcess("sleep", "30") }
        val stdin = process.outputStream
        val stdout = process.inputStream
        try {
            process.destroy()

            assertTrue(offMain { process.waitForTimeout(5, TimeUnit.SECONDS) })
        } finally {
            stdin.close()
            stdout.close()
        }
    }

    @Test
    fun malformedEnvironmentEntriesAreDropped() {
        // Runtime.exec ignores what is not NAME=VALUE; handed to env, it would become the command.
        val process = offMain {
            tracked(adapter.newProcess(arrayOf("sh", "-c", "exit \$X"), arrayOf("NOT_AN_ENTRY", "-u", "X=5"), null))
        }

        assertEquals(5, offMain { process.waitFor() })
    }

    @Test
    fun aCommandEnvWouldNotRunAsTheCommandIsRefused() {
        for (command in listOf("-i", "A=1")) {
            try {
                adapter.newProcess(arrayOf(command), arrayOf("X=1"), null)
                fail("$command reached env as something other than the command")
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun anExitedCommandKeepsOnlyItsStderrAndUnaskedStdoutPipesUntilDestroyed() {
        // Counts the pipe ends this side keeps; what the caller got is left out, see openDescriptors.
        fun run(readStdout: Boolean) = offMain {
            shizukuProcess("sh", "-c", "echo out; echo err >&2").also { process ->
                if (readStdout) process.inputStream.use { it.readBytes() }
                process.waitFor()
            }
        }
        // The first commands bind the shell service and start what it keeps for good.
        repeat(3) { run(readStdout = true).destroy() }
        until { settled() }
        val before = openDescriptors()

        // Kept, as upstream's cache keeps them.
        val kept = ArrayList<ShizukuRemoteProcess>()
        try {
            repeat(20) { kept += run(readStdout = true) }
            repeat(20) { kept += run(readStdout = false) }

            // stderr for each, stdout for those that never asked for it.
            until { settled() && openDescriptors() - before <= 20 * 1 + 20 * 2 + SLACK }

            kept.forEach { it.destroy() }
            until { settled() && openDescriptors() - before <= SLACK }
        } finally {
            kept.forEach { it.destroy() }
        }
    }

    /** Runs [block], waits until no request is in flight, and returns what upstream reported. */
    private fun permissionResults(block: () -> Unit): List<Pair<Int, Int>> {
        val results = java.util.Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
        val listener = Shizuku.OnRequestPermissionResultListener { code, result -> results += code to result }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            block()
            until { adapter.work.children.none() }
            settleUpstream()
            return results.toList()
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    /** A command started through upstream's client, which keeps every one it hands out. Blocks, so not on main. */
    private fun shizukuProcess(vararg command: String): ShizukuRemoteProcess {
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        newProcess.isAccessible = true
        return (newProcess.invoke(null, command, null, null) as ShizukuRemoteProcess).also { started += it::destroy }
    }

    private fun tracked(process: IRemoteProcess): IRemoteProcess = process.also { started += it::destroy }

    /**
     * Runs what the adapter queued for upstream, then what upstream posted to the main thread.
     * Never called on [ShizukuServiceAdapter.UPSTREAM] itself.
     */
    private fun settleUpstream() {
        ShizukuServiceAdapter.UPSTREAM.submit {}.get(5, TimeUnit.SECONDS)
        ShizukuServiceAdapter.UPSTREAM.submit {}.get(5, TimeUnit.SECONDS)
        ShadowLooper.shadowMainLooper().idle()
    }

    /**
     * Leaves out every descriptor on a copy Robolectric's ParcelFileDescriptor.dup() made: it
     * copies into a file through a stream it never closes. That also hides a copy the caller got
     * and never closed, so only the pipe ends this side keeps are counted.
     */
    private fun openDescriptors(): Int = java.io.File("/proc/self/fd").listFiles()!!.count { fd ->
        val target = runCatching { java.nio.file.Files.readSymbolicLink(fd.toPath()).toString() }.getOrDefault("")
        !target.contains("/ShadowParcelFileDescriptor/dupfd-")
    }

    /** No pipe transfer or exit watch of the in-process shell service is still running. */
    private fun settled(): Boolean =
        Thread.getAllStackTraces().keys.none { it.isAlive && (it.name == "porter-shell-pipe" || it.name == "porter-shell-exit") }

    /** Waits for [condition] while running the main looper, where upstream reports to the app. */
    private fun until(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            ShadowLooper.shadowMainLooper().idle()
            Thread.sleep(5)
        }
    }

    /**
     * Runs [block] on a thread of its own while this one runs the main looper, which is where the
     * SDK hands a bound service over.
     */
    private fun <T> offMain(timeoutMs: Long = 10_000, block: () -> T): T {
        val result = AtomicReference<Result<T>?>()
        val worker = Thread { result.set(runCatching(block)) }
        worker.start()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (worker.isAlive) {
            check(System.currentTimeMillis() < deadline) { "the call did not finish within ${timeoutMs}ms" }
            ShadowLooper.shadowMainLooper().idle()
            Thread.sleep(5)
        }
        return result.get()!!.getOrThrow()
    }

    private companion object {
        const val PACKAGE = "eu.darken.porter.bridge.test"

        /** Descriptors the JVM and the in-process shell service open and close on their own. */
        const val SLACK = 4
    }
}
