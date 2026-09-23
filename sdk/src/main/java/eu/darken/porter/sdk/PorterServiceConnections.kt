package eu.darken.porter.sdk

/**
 * The user service bindings of one [PorterConnection], keyed by service identity. Each connection
 * holds its own, so a server that replaced another never reaches a callback the replaced server
 * was given.
 */
internal class PorterServiceConnections {

    /**
     * Guards [cache], [closed] and, in every [PorterServiceConnection], its set of registered
     * [UserServiceListener]s and its terminal flag.
     */
    val lock = Any()

    private val cache = HashMap<String, PorterServiceConnection>()

    /** The instances evicted by a death whose delivery has neither run nor been called off. */
    private val pending = HashMap<String, MutableList<PorterServiceConnection>>()

    /** Set once, by [close]; nothing registers after. */
    private var closed = false

    /** A connection and whether the registration that produced it inserted the listener. */
    class Registration(val connection: PorterServiceConnection, val inserted: Boolean)

    /**
     * Looks up or creates the connection for [args] and registers [listener] on it, as one locked
     * operation: a bind arriving after a death finds no cached instance and gets a fresh one, with
     * no window in which it could register on the instance that just died.
     *
     * @return null once this registry is closed
     */
    fun register(args: UserServiceArgs, listener: UserServiceListener?): Registration? = synchronized(lock) {
        if (closed) return null
        val connection = cache.getOrPut(key(args)) { PorterServiceConnection(this, args) }
        Registration(connection, connection.addListener(listener))
    }

    /** Whether [close] has run. */
    fun isClosed(): Boolean = synchronized(lock) { closed }

    /** The cached connection for [args], and null when nothing is bound under it. */
    fun peek(args: UserServiceArgs): PorterServiceConnection? = synchronized(lock) {
        cache[key(args)]
    }

    /** Whether [connection] is still the binding its identity resolves to, and somebody collects it. */
    fun isWanted(connection: PorterServiceConnection): Boolean = synchronized(lock) {
        !closed && cache[key(connection.args)] === connection && connection.hasListeners()
    }

    private fun key(args: UserServiceArgs): String = args.tag ?: args.componentName.className

    fun remove(connection: PorterServiceConnection) {
        synchronized(lock) {
            evict(connection)
        }
    }

    /**
     * Evicts [connection] as [remove] does, and names it under every key it held so that an
     * unbind arriving before its death delivery runs can still call that delivery off.
     */
    fun retire(connection: PorterServiceConnection) {
        synchronized(lock) {
            for (key in evict(connection)) {
                pending.getOrPut(key) { ArrayList() }.add(connection)
            }
        }
    }

    /** Drops [connection] from the pending map, its delivery having run or been called off. */
    fun deliveryDone(connection: PorterServiceConnection) {
        synchronized(lock) {
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.value.remove(connection)
                if (entry.value.isEmpty()) iterator.remove()
            }
        }
    }

    /** Calls off the death deliveries queued under [args] but not yet run. */
    fun cancelPending(args: UserServiceArgs) {
        synchronized(lock) {
            val pending = pending.remove(key(args)) ?: return
            for (connection in pending) {
                connection.cancelPendingDelivery()
            }
        }
    }

    /**
     * Ends every binding as a death of its service would, and refuses every registration from now
     * on. For a connection that was replaced or died: whatever its server still pushes describes a
     * binding nobody collects any more.
     *
     * @return the bindings that were live, which the server may still hold
     */
    fun close(): List<PorterServiceConnection> {
        val bindings = synchronized(lock) {
            closed = true
            cache.values.toList()
        }
        for (binding in bindings) binding.died()
        return bindings
    }

    private fun evict(connection: PorterServiceConnection): List<String> {
        val keys = cache.filterValues { it === connection }.keys.toList()
        for (key in keys) cache.remove(key)
        return keys
    }
}
