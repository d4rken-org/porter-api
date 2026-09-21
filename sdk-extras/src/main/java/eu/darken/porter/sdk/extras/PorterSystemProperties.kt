@file:JvmName("PorterSystemProperties")

package eu.darken.porter.sdk.extras

import eu.darken.porter.sdk.PorterConnection

/*
 * Typed reads of the system properties, as the server sees them. Every call reaches the server,
 * and a server that answers nothing for the key reads as the default.
 */

public fun PorterConnection.getSystemPropertyInt(key: String, default: Int): Int =
    getSystemProperty(key, default.toString())?.let(Integer::decode) ?: default

public fun PorterConnection.getSystemPropertyLong(key: String, default: Long): Long =
    getSystemProperty(key, default.toString())?.let(java.lang.Long::decode) ?: default

public fun PorterConnection.getSystemPropertyBoolean(key: String, default: Boolean): Boolean =
    getSystemProperty(key, default.toString())?.toBoolean() ?: default
