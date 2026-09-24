package eu.darken.porter.sdk.consumer

import android.content.Context
import kotlin.system.exitProcess

/*
 * Reached only through the ComponentNames in ConsumerApplication. The server constructs them by name,
 * trying the Context constructor first, so each shape the server accepts has a class of its own.
 */

class ConsumerService : IConsumerService.Stub() {
    override fun destroy() = exitProcess(0)
}

class ConsumerContextService(@Suppress("unused") private val context: Context) : IConsumerService.Stub() {
    override fun destroy() = exitProcess(0)
}
