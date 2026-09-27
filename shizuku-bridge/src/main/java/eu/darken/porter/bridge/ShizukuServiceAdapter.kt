package eu.darken.porter.bridge

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterConnectionLostException
import eu.darken.porter.sdk.PorterException
import eu.darken.porter.sdk.extras.startProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuApplication
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IShizukuServiceConnection
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Porter connection, seen by upstream's client as a Shizuku server. It lives in this process:
 * upstream calls it directly on the calling thread, and it refuses every transaction from another
 * process, since it answers with this app's grant.
 */
internal class ShizukuServiceAdapter(
    private val connection: PorterConnection,
    private val scope: CoroutineScope,
) : IShizukuService.Stub() {

    /** Guards [alive], [buried], [application] and [recipients]. Nothing calls into upstream while holding it. */
    private val lock = Any()
    private var alive = true

    /** Set once the death notice went out; a dead binder refuses new recipients from then on. */
    private var buried = false
    private var application: IShizukuApplication? = null
    private val recipients = ArrayList<IBinder.DeathRecipient>()

    /** Set while a [publish] waits for [UPSTREAM], which does everything a second one would. */
    private val publishing = AtomicBoolean(false)

    /** This adapter's own work, which ends when it retires. */
    internal val work = SupervisorJob(scope.coroutineContext[Job])

    /**
     * Puts this connection in front of upstream: its binder, or, where upstream holds it already,
     * the attach reply upstream caches, which another server's late reply can have overwritten.
     */
    fun publish() {
        if (!publishing.compareAndSet(false, true)) return
        UPSTREAM.execute {
            publishing.set(false)
            if (!pingBinder()) return@execute
            // Once a server before API 11 attached, upstream keeps that parcel layout for good, and
            // this adapter reads the current one.
            if (Shizuku.isPreV11()) {
                Log.w(TAG, "upstream is in pre-API 11 mode; not serving it")
                return@execute
            }
            if (Shizuku.getBinder() !== this) {
                Shizuku.onBinderReceived(this, connection.packageName)
            } else {
                synchronized(lock) { application }?.let(::reportAttached)
            }
        }
    }

    /**
     * What a real server's death does: upstream's own recipient clears its state. Final: every
     * call after it fails as a dead binder would.
     */
    fun retire() {
        synchronized(lock) {
            if (!alive) return
            alive = false
            application = null
        }
        work.cancel()
        UPSTREAM.execute {
            // A publish that started before this retirement can still have linked upstream's recipient.
            val dying = synchronized(lock) {
                buried = true
                recipients.toList()
            }
            for (recipient in dying) {
                // Upstream unlinks its recipient once another binder replaces this one, and that
                // recipient clears whatever binder upstream holds.
                if (!synchronized(lock) { recipients.remove(recipient) }) continue
                try {
                    recipient.binderDied()
                } catch (e: Throwable) {
                    Log.w(TAG, "death recipient", e)
                }
            }
        }
    }

    override fun pingBinder(): Boolean = synchronized(lock) { alive }

    override fun isBinderAlive(): Boolean = pingBinder()

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
        synchronized(lock) {
            if (buried) throw DeadObjectException("the Porter connection behind this binder is gone")
            recipients.add(recipient)
        }
    }

    override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean =
        synchronized(lock) { recipients.remove(recipient) }

    override fun attachApplication(application: IShizukuApplication?, args: Bundle?) {
        application ?: return
        synchronized(lock) {
            ensureAlive()
            this.application = application
        }
        reportAttached(application)
    }

    private fun reportAttached(application: IShizukuApplication) {
        val reply = bindReply()
        report {
            if (Shizuku.isPreV11()) return@report
            if (PorterShizukuBridge.listening) ownReplies.incrementAndGet()
            application.bindApplication(reply)
        }
    }

    private fun bindReply(): Bundle = Bundle().apply {
        putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_UID, connection.uid)
        // Shizuku's API level, not Porter's protocol version: ShizukuBinderWrapper decides its
        // parcel layout from this number.
        putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_VERSION, SHIZUKU_API_VERSION)
        putInt(ShizukuApiConstants.BIND_APPLICATION_SERVER_PATCH_VERSION, ShizukuApiConstants.SERVER_PATCH_VERSION)
        putString(ShizukuApiConstants.BIND_APPLICATION_SERVER_SECONTEXT, connection.seLinuxContext)
        putBoolean(ShizukuApiConstants.BIND_APPLICATION_PERMISSION_GRANTED, connection.permission.value is PermissionState.Granted)
        putBoolean(ShizukuApiConstants.BIND_APPLICATION_SHOULD_SHOW_REQUEST_PERMISSION_RATIONALE, rationale())
    }

    override fun getVersion(): Int = alive { SHIZUKU_API_VERSION }

    override fun getUid(): Int = alive { connection.uid }

    override fun getSELinuxContext(): String? = alive { connection.seLinuxContext }

    override fun checkPermission(permission: String?): Int {
        ensureAlive()
        val granted = blocking { connection.checkRemotePermission(permission ?: throw NullPointerException("permission is null")) }
        return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    override fun getSystemProperty(name: String?, defaultValue: String?): String? =
        blockingWhileAlive { connection.getSystemProperty(name ?: throw NullPointerException("name is null"), defaultValue) }

    override fun setSystemProperty(name: String?, value: String?) {
        blockingWhileAlive { connection.setSystemProperty(name ?: throw NullPointerException("name is null"), value ?: "") }
    }

    override fun checkSelfPermission(): Boolean = blockingWhileAlive { connection.checkPermission() } is PermissionState.Granted

    override fun shouldShowRequestPermissionRationale(): Boolean = alive { rationale() }

    override fun requestPermission(requestCode: Int) {
        val application = synchronized(lock) {
            ensureAlive()
            application
        } ?: return
        CoroutineScope(scope.coroutineContext + work).launch {
            val allowed = try {
                connection.requestPermission() is PermissionState.Granted
            } catch (e: PorterConnectionLostException) {
                // The replacement connection brings its own adapter; this one's caller hears
                // nothing, as with a Shizuku server that died mid-dialog.
                return@launch
            } catch (e: PorterException) {
                false
            }
            report {
                application.dispatchRequestPermissionResult(requestCode, Bundle().apply {
                    putBoolean(ShizukuApiConstants.REQUEST_PERMISSION_REPLY_ALLOWED, allowed)
                })
            }
        }
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Every call answers with this app's grant. Upstream's provider hands this binder to
        // whoever may call the provider, so a call from another process is refused before dispatch.
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingPid() != Process.myPid()) {
            throw SecurityException("the Porter Shizuku bridge answers only its own process")
        }
        if (code != ShizukuApiConstants.BINDER_TRANSACTION_transact) return super.onTransact(code, data, reply, flags)
        // Layout from ShizukuBinderWrapper on an API 13 server: target, code, flags, then the
        // caller's own parcel. Only that last part goes on to Porter. The wrapper leaves the flags
        // out while upstream takes another server for an older one, and nothing here can tell.
        check(!Shizuku.isPreV11() && Shizuku.getVersion() >= SHIZUKU_API_VERSION) {
            "upstream holds another server's parcel layout"
        }
        data.enforceInterface(ShizukuApiConstants.BINDER_DESCRIPTOR)
        ensureAlive()
        val target = data.readStrongBinder()
        val targetCode = data.readInt()
        val targetFlags = data.readInt()
        val payload = Parcel.obtain()
        try {
            payload.appendFrom(data, data.dataPosition(), data.dataAvail())
            payload.setDataPosition(0)
            asShizuku { connection.wrap(target).transact(targetCode, payload, reply, targetFlags) }
        } finally {
            payload.recycle()
        }
        return true
    }

    override fun newProcess(cmd: Array<out String>?, env: Array<out String>?, dir: String?): IRemoteProcess {
        ensureAlive()
        val command = cmd ?: throw NullPointerException("cmd is null")
        require(command.isNotEmpty()) { "cmd is empty" }
        val full = if (env == null) {
            command
        } else {
            // An environment replaces the inherited one, as with Runtime.exec. `env -i` does that
            // as long as no entry reads as an option or the command, and the command reads as
            // neither an option nor an entry.
            require(!command[0].startsWith("-") && '=' !in command[0]) { "env would not run cmd[0] as the command: ${command[0]}" }
            val entries = env.filter { it.indexOf('=') > 0 && !it.startsWith("-") }
            arrayOf("env", "-i", *entries.toTypedArray(), *command)
        }
        return asShizuku { RemoteProcessAdapter(blocking { connection.startProcess(*full, dir = dir) }) }
    }

    override fun addUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int = unsupported("addUserService")

    override fun removeUserService(conn: IShizukuServiceConnection?, args: Bundle?): Int = unsupported("removeUserService")

    override fun exit() = unsupported("exit")

    override fun attachUserService(binder: IBinder?, options: Bundle?) = unsupported("attachUserService")

    override fun dispatchPackageChanged(intent: Intent?) = unsupported("dispatchPackageChanged")

    override fun isHidden(uid: Int): Boolean = unsupported("isHidden")

    override fun dispatchPermissionConfirmationResult(requestUid: Int, requestPid: Int, requestCode: Int, data: Bundle?) =
        unsupported("dispatchPermissionConfirmationResult")

    override fun getFlagsForUid(uid: Int, mask: Int): Int = unsupported("getFlagsForUid")

    override fun updateFlagsForUid(uid: Int, mask: Int, value: Int) = unsupported("updateFlagsForUid")

    private fun rationale(): Boolean = (connection.permission.value as? PermissionState.Denied)?.permanentlyDenied == true

    /**
     * Tells upstream something the way a server's oneway call would: later, and never on the
     * caller's thread, which may be the main one where upstream runs listeners inline.
     */
    private fun report(block: () -> Unit) {
        UPSTREAM.execute {
            // A report from an adapter upstream no longer holds would reach the listeners of the
            // binder it holds now.
            if (pingBinder() && Shizuku.getBinder() === this) block()
        }
    }

    private fun <T> blocking(call: suspend () -> T): T = asShizuku {
        try {
            runBlocking { call() }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while waiting for Porter", e)
        }
    }

    private fun <T> blockingWhileAlive(call: suspend () -> T): T {
        ensureAlive()
        return blocking(call)
    }

    private inline fun <T> alive(value: () -> T): T {
        ensureAlive()
        return value()
    }

    private fun ensureAlive() {
        if (!synchronized(lock) { alive }) throw DeadObjectException("the Porter connection behind this binder is gone")
    }

    private fun unsupported(name: String): Nothing =
        throw UnsupportedOperationException("$name is not supported by the Porter Shizuku bridge")

    internal companion object {
        const val TAG = "PorterShizukuBridge"
        const val SHIZUKU_API_VERSION = 13

        /**
         * Every call into upstream's client, in order: a publish after a retirement lands after
         * that retirement's death notice.
         */
        val UPSTREAM: ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "porter-bridge").apply { isDaemon = true }
        }

        /**
         * Attach replies adapters sent while the bridge listens. Upstream's application stub runs
         * its received-listeners once per reply, so a listener run beyond these is another
         * server's reply.
         */
        val ownReplies = AtomicInteger()
    }
}
