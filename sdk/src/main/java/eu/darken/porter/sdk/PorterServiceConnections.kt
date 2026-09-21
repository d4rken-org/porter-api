package eu.darken.porter.sdk

internal object PorterServiceConnections {

    /**
     * Guards [CACHE] and, in every [PorterServiceConnection], its set of registered
     * [UserServiceListener]s and its terminal flag.
     */
    val LOCK = Any()

    private val CACHE = HashMap<String, PorterServiceConnection>()

    /** The instances evicted by a death whose delivery has neither run nor been called off. */
    private val PENDING = HashMap<String, MutableList<PorterServiceConnection>>()

    fun get(args: UserServiceArgs): PorterServiceConnection = synchronized(LOCK) {
        lookupOrCreate(args)
    }

    /** A connection and whether the registration that produced it inserted the listener. */
    class Registration(val connection: PorterServiceConnection, val inserted: Boolean)

    /**
     * Looks up or creates the connection for [args] and registers [listener] on it, as one locked
     * operation: a bind arriving after a death finds no cached instance and gets a fresh one, with
     * no window in which it could register on the instance that just died.
     */
    fun register(args: UserServiceArgs, listener: UserServiceListener?): Registration = synchronized(LOCK) {
        val connection = lookupOrCreate(args)
        Registration(connection, connection.addListener(listener))
    }

    private fun lookupOrCreate(args: UserServiceArgs): PorterServiceConnection =
        CACHE.getOrPut(key(args)) { PorterServiceConnection(args) }

    /** The cached connection for [args], and null when nothing is bound under it. */
    fun peek(args: UserServiceArgs): PorterServiceConnection? = synchronized(LOCK) {
        CACHE[key(args)]
    }

    private fun key(args: UserServiceArgs): String = args.tag ?: args.componentName.className

    fun remove(connection: PorterServiceConnection) {
        synchronized(LOCK) {
            evict(connection)
        }
    }

    /**
     * Evicts [connection] as [remove] does, and names it under every key it held so that an
     * unbind arriving before its death delivery runs can still call that delivery off.
     */
    fun retire(connection: PorterServiceConnection) {
        synchronized(LOCK) {
            for (key in evict(connection)) {
                PENDING.getOrPut(key) { ArrayList() }.add(connection)
            }
        }
    }

    /** Drops [connection] from the pending map, its delivery having run or been called off. */
    fun deliveryDone(connection: PorterServiceConnection) {
        synchronized(LOCK) {
            val iterator = PENDING.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.value.remove(connection)
                if (entry.value.isEmpty()) iterator.remove()
            }
        }
    }

    /** Calls off the death deliveries queued under [args] but not yet run. */
    fun cancelPending(args: UserServiceArgs) {
        synchronized(LOCK) {
            val pending = PENDING.remove(key(args)) ?: return
            for (connection in pending) {
                connection.cancelPendingDelivery()
            }
        }
    }

    private fun evict(connection: PorterServiceConnection): List<String> {
        val keys = CACHE.filterValues { it === connection }.keys.toList()
        for (key in keys) CACHE.remove(key)
        return keys
    }

    fun clearForTest() {
        synchronized(LOCK) {
            CACHE.clear()
            PENDING.clear()
        }
    }
}
