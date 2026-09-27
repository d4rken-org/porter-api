package eu.darken.porter.probe.bridge

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import rikka.shizuku.ShizukuSystemProperties
import ru.solrudev.ackpine.installer.PackageInstaller
import ru.solrudev.ackpine.installer.createSession
import ru.solrudev.ackpine.session.Session
import ru.solrudev.ackpine.session.await
import ru.solrudev.ackpine.session.parameters.Confirmation
import ru.solrudev.ackpine.shizuku.shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One action per launch, named by the `action` extra, each reporting on logcat under [TAG]:
 * `status`, `request`, `install`, `shell`, `shellmain`, `stdin`, `env`, `pm`, `sticky`, `streams`.
 */
open class ProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ProbeApp
        when (val action = intent.getStringExtra("action") ?: "status") {
            "status" -> status()
            "request" -> {
                log("REQUEST ping=${Shizuku.pingBinder()}")
                Shizuku.requestPermission(42)
            }
            "install" -> app.scope.launch { install() }
            "shell" -> app.scope.launch {
                run("SHELL", arrayOf("sh", "-c", "id -u; echo to-stderr >&2; exit 3"))
            }
            // On the main thread itself, the way an app calling Shizuku synchronously would.
            "shellmain" -> run("SHELLMAIN", arrayOf("sh", "-c", "id -u"))
            "stdin" -> app.scope.launch { run("STDIN", arrayOf("cat"), input = "hello-through-stdin\n") }
            "env" -> app.scope.launch {
                run("ENV", arrayOf("sh", "-c", "echo FOO=\$FOO HOME=\$HOME"), env = arrayOf("FOO=bar"))
            }
            "pm" -> app.scope.launch {
                run("PM", arrayOf("sh", "-c", "pm disable-user --user 0 eu.darken.porter.probe.bridgetarget"))
            }
            "streams" -> app.scope.launch { streams() }
            "sticky" -> {
                log("STICKY_REGISTER ping=${Shizuku.pingBinder()}")
                Shizuku.addBinderReceivedListenerSticky { log("STICKY_FIRED ping=${Shizuku.pingBinder()}") }
            }
            else -> log("UNKNOWN_ACTION $action")
        }
        finish()
    }

    /** Calls the method the way apps do: it is private since Shizuku-API 13. */
    private fun newProcess(cmd: Array<String>, env: Array<String>? = null): ShizukuRemoteProcess {
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        return newProcess.invoke(null, cmd, env, null) as ShizukuRemoteProcess
    }

    private fun run(label: String, cmd: Array<String>, env: Array<String>? = null, input: String? = null) {
        try {
            val process = newProcess(cmd, env)
            if (input != null) process.outputStream.use { it.write(input.toByteArray()) }
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            log("${label}_RESULT exit=$exit out=[$out] err=[$err] backend=${backend()}")
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log("${label}_THREW ${cause.javaClass.name}: ${cause.message}")
        }
    }

    /** Who holds which pipe, on real descriptors: what host tests cannot show. */
    private fun streams() {
        try {
            val twice = newProcess(arrayOf("sh", "-c", "echo err >&2"))
            twice.waitFor()
            val first = twice.errorStream
            val second = twice.errorStream
            first.close()
            val repeated = second.use { it.bufferedReader().readText().trim() }
            twice.destroy()

            val late = newProcess(arrayOf("sh", "-c", "echo out; echo err >&2"))
            late.waitFor()
            val lateOut = late.inputStream.use { it.bufferedReader().readText().trim() }
            val lateErr = late.errorStream.use { it.bufferedReader().readText().trim() }
            late.destroy()

            val held = newProcess(arrayOf("sleep", "30"))
            val heldIn = held.outputStream
            val heldOut = held.inputStream
            held.destroy()
            val killed = held.waitForTimeout(5, TimeUnit.SECONDS)
            heldIn.close()
            heldOut.close()

            val long = newProcess(arrayOf("sleep", "1"))
            val longWaited = long.waitForTimeout(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            long.destroy()

            val before = descriptors()
            val kept = List(20) { newProcess(arrayOf("sh", "-c", "echo out; echo err >&2")).also { p -> p.inputStream.use { it.readBytes() }; p.waitFor() } } +
                List(20) { newProcess(arrayOf("sh", "-c", "echo out; echo err >&2")).also { it.waitFor() } }
            Thread.sleep(500)
            val whileKept = descriptors() - before
            kept.forEach { it.destroy() }
            Thread.sleep(500)
            val afterDestroy = descriptors() - before

            log("STREAMS_RESULT repeated=[$repeated] lateOut=[$lateOut] lateErr=[$lateErr] killed=$killed longWaited=$longWaited " +
                "fdsWhileKept=$whileKept fdsAfterDestroy=$afterDestroy")
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log("STREAMS_THREW ${cause.javaClass.name}: ${cause.message}")
        }
    }

    private fun descriptors(): Int = File("/proc/self/fd").list()!!.size

    private fun backend(): String = when (val binder = Shizuku.getBinder()) {
        null -> "none"
        else -> if (binder.javaClass.name.startsWith("eu.darken.porter.bridge")) "porter-bridge" else "shizuku"
    }

    private fun status() {
        if (!Shizuku.pingBinder()) {
            log("STATUS ping=false backend=${backend()}")
            return
        }
        log("STATUS ping=true backend=${backend()} uid=${Shizuku.getUid()} version=${Shizuku.getVersion()} preV11=${Shizuku.isPreV11()} " +
            "selfPermission=${Shizuku.checkSelfPermission()} secontext=${Shizuku.getSELinuxContext()} " +
            "sdk=${runCatching { ShizukuSystemProperties.get("ro.build.version.sdk", "?") }.getOrElse { it.javaClass.simpleName }}")
    }

    private suspend fun install() {
        val apk = File(cacheDir, "target.apk")
        assets.open("target.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        log("INSTALL_START ping=${Shizuku.pingBinder()} backend=${backend()}")
        try {
            val session = PackageInstaller.getInstance(this).createSession(Uri.fromFile(apk)) {
                name = "bridge-target"
                confirmation = Confirmation.IMMEDIATE
                shizuku { }
            }
            when (val result = session.await()) {
                Session.State.Succeeded -> log("INSTALL_SUCCEEDED")
                is Session.State.Failed -> log("INSTALL_FAILED ${result.failure}")
            }
        } catch (e: Exception) {
            log("INSTALL_THREW ${e.javaClass.name}: ${e.message}")
        }
    }
}
