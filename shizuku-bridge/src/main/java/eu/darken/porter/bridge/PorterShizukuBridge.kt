package eu.darken.porter.bridge

import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Serves `rikka.shizuku.Shizuku` from Porter: every Porter connection this process receives is
 * handed to upstream's client as its Shizuku binder, so code written against Shizuku-API runs on
 * Porter unchanged. A Porter connection takes the place of a Shizuku server's binder upstream
 * already holds or receives later, and nothing is handed back to it when Porter goes away.
 */
public object PorterShizukuBridge {

    private var job: Job? = null

    /** The adapter upstream should hold. */
    @Volatile
    private var current: ShizukuServiceAdapter? = null

    @Volatile
    internal var listening = false
        private set

    /** Serves upstream for as long as [scope] runs. Calling it again while it does changes nothing. */
    @Synchronized
    public fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        if (!listening) {
            // Upstream's provider hands over a Shizuku server's binder whenever that server sends it,
            // and the server's attach reply can arrive after Porter's.
            Shizuku.addBinderReceivedListener {
                val own = ShizukuServiceAdapter.ownReplies.getAndUpdate { maxOf(it - 1, 0) } > 0
                if (!own) current?.publish()
            }
            listening = true
        }
        val previous = job
        job = scope.launch {
            // A cancelled collector can still be retiring its adapter; its death notice has to reach
            // upstream before this one's first binder does.
            previous?.join()
            Porter.connection.collectLatest { connection ->
                if (connection == null || connection.backend != PorterBackend.PORTER) return@collectLatest
                val adapter = ShizukuServiceAdapter(connection, scope)
                try {
                    current = adapter
                    adapter.publish()
                    awaitCancellation()
                } finally {
                    if (current === adapter) current = null
                    adapter.retire()
                }
            }
        }
    }
}
